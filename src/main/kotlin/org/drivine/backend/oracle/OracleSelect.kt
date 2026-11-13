package org.drivine.backend.oracle

import org.drivine.query.dsl.HavingCondition
import org.drivine.query.dsl.GroupColumn
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.connection.JacksonEncoder
import org.drivine.backend.sql.Fragments
import org.drivine.backend.sql.Cascade
import org.drivine.backend.sql.CascadeRecursion
import org.drivine.backend.sql.CypherShorthand
import org.drivine.backend.sql.Derived
import org.drivine.backend.sql.OnceReached
import org.drivine.backend.sql.Query
import org.drivine.backend.sql.RowSource
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.SqlViewSelect
import org.drivine.backend.sql.Step
import org.drivine.backend.sql.Table
import org.drivine.backend.sql.under
import org.drivine.annotation.Direction
import org.drivine.cypher.api.SearchIndex
import org.drivine.cypher.api.SearchStore
import org.drivine.backend.GraphStatement
import org.drivine.backend.oracle.OracleSql.EMPTY_ARRAY
import org.drivine.backend.oracle.OracleSql.document
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.backend.oracle.OracleSql.literal
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.backend.oracle.OracleSql.property
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.GroupQuery
import org.drivine.query.dsl.Aggregate
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GroupFunction
import org.drivine.query.dsl.GroupOrder
import org.drivine.query.dsl.Key
import org.drivine.query.dsl.KeySource
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.schema.SimilarityFunction

/**
 * Writes one statement that reads a graph kept by Oracle: a load, a count, or the predicate of a
 * delete. One instance writes one statement, and holds the values its parameters take, so it is
 * stateful: [hasId], [relatedTo] and [after] say something about the statement, and are called
 * before [load], [count] or [selecting] writes it.
 *
 * A load returns one JSON document for each root, shaped as the Cypher load's map is: a fragment is
 * its properties with its `labels`; a view is its root and each relationship field, a collection as
 * an array and a single one as an object or null.
 *
 * The root is read through `GRAPH_TABLE`, and so is every traversal: see [OracleRowSources]. The
 * predicates of the query DSL are written by [OraclePredicates], and a predicate written by hand is
 * read by [CypherShorthand]. What is here is the projection of a view, a scored search, and what a
 * cascading delete selects.
 *
 * A statement is written to be read beside the Cypher it stands for. A row is called what the model
 * calls it, the root by its field and a related node by the field that holds it, and the node of a
 * `MATCH` has its row's name: `(claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node …)`
 * is Cypher's `(claim)-[:MENTIONS]->(people)`. Every query in it is a [Query], so each is laid out
 * the same way, under the line that opens it, and names in `COLUMNS` only what it reads.
 */
internal class OracleSelect(
    private val graph: OracleGraph,
    graphClass: Class<*>,
    collectionSorts: List<CollectionSortSpec> = emptyList(),
    depthOverrides: Map<String, Int> = emptyMap(),
    labelled: LabelledIndexes = LabelledIndexes.NONE,
) : SqlViewSelect<OracleStatementScope>(graphClass, collectionSorts, depthOverrides, OracleStatementScope()) {
    /** The names of the root's relationship fields; empty for a fragment. */
    val relationshipFields: Set<String> = viewModel?.relationships?.map { it.fieldName }?.toSet().orEmpty()

    private val rows = OracleRowSources(graph, scope)
    /** The values this statement's conditions and orders compare, compiled by the module ([Fragments]): see [hinted]. */
    private val fragments = Fragments(
        OracleCypherCompiler(graph.name, nullsEqualStored = true, eraStored = true, indexedProperties = labelled.leading, encoder = JacksonEncoder), scope,
    )
    private val predicates = OraclePredicates(scope, rootAlias, root, rootModel, viewModel, ::rootLabels, depthOverrides, fragments)
    private val shorthand = CypherShorthand(rootAlias, root) { row, key -> property(row, key) }

    // ----- Statements -----

    /**
     * The load of the roots that meet [predicates].
     *
     * @param orders the orders on the root, in turn
     * @param skip and [limit] the page to return, bound as parameters so the text is the same for every page
     */
    override fun load(predicates: List<String>, orders: List<OrderTerm>, skip: Int?, limit: Int?): GraphStatement {
        val result = "${projection()} AS result"
        val selected = predicates + requiredRelationships()
        val page = listOfNotNull(
            orders.takeIf { it.isNotEmpty() }?.let { "ORDER BY " + it.joinToString(", ", transform = this.predicates::orderTerms) },
            skip?.let { "OFFSET ${scope.bound(it)} ROWS" },
            limit?.let { "FETCH NEXT ${scope.bound(it)} ROWS ONLY" },
        )
        return GraphStatement(hinted(Query(result, roots(), selected, page).text()), bindings())
    }

    /** The count of the roots [predicates] select, and that have the relationships the view requires. */
    override fun count(predicates: List<String>): GraphStatement =
        GraphStatement(hinted(Query("COUNT(*)", roots(), predicates + requiredRelationships()).text()), bindings())

    /**
     * The grouped read [query] asks of the roots: one JSON document for each group, a key under `k0…`
     * and an aggregate under `a0…`.
     *
     * The groups are made by a query of their own, `GROUP BY` over the roots, and the statement reads
     * its rows: it filters them for a `having`, orders and pages them, and joins the node of a node key
     * back for its document, which is JSON and no key a `GROUP BY` takes.
     *
     * A property key is the property's value as the store holds it ([Fragments.value]), so values Cypher
     * tells apart are grouped apart, and a temporal value comes back as its kind. Where the groups are
     * ordered by a property key, the grouped query also gives a node of the group that has its value
     * (`k0r`); a least or greatest value is a node of the group that has it (`a0r`), the first in
     * Cypher's order. The statement joins each back, by the keys Oracle keeps ([OracleSql.sameKey]), and
     * the module reads, orders and compares the value there as Cypher does.
     *
     * A node key is a path, and a path gives a root once for each way to a key node. The roots are
     * then read as the distinct pairs of a root and its key nodes, by their ids, and each root's row
     * is joined to its pairs, so a predicate and an aggregate read a row of the node table, as they do
     * where there is no path.
     */
    override fun groups(query: GroupQuery): GraphStatement {
        val spec = query.spec
        val paths = spec.keys.filter { it.source is KeySource.Path }
        val pairs = scope.row("pair")
        val nodes = paths.associateWith { scope.row(it.column) }
        val filtered = listOfNotNull(predicate(spec.conditions))

        // The columns read through a node of the group that has the value: see the KDoc.
        val held = (query.orders.map { it.column } + spec.aggregates).filter { column ->
            when (column) {
                is Key<*> -> (column.source as? KeySource.Property)?.propertyName?.let { it != Stamps.PROPERTY } == true
                is Aggregate<*> -> column.function == GroupFunction.MIN || column.function == GroupFunction.MAX
            }
        }.toSet()

        val keys = spec.keys.associateWith { key ->
            when (val from = key.source) {
                is KeySource.Property -> keyValue(from.propertyName)
                is KeySource.Path -> "$pairs.${key.column}"
            }
        }
        // The node of a least or greatest value is the first of its group by a window over the rows before they are grouped,
        // and not `KEEP (DENSE_RANK FIRST …)`: ordered by a value of JSON, that ends Oracle's (23.26) server process (`ORA-03113`).
        val firsts = spec.aggregates.filter { it.function == GroupFunction.MIN || it.function == GroupFunction.MAX }.associateWith(::first)
        // Over the rows of the window, a node key is a column of each: see below.
        val grouped = keys.mapValues { (key, expression) -> if (firsts.isNotEmpty() && key.source is KeySource.Path) "$root.${key.column}" else expression }
        val selected = grouped.map { (key, expression) -> "$expression AS ${key.column}" } +
            spec.aggregates.map { aggregate ->
                val value = firsts[aggregate]?.let { "CASE WHEN COUNT(CASE WHEN ${it.kept} THEN 1 END) > 0 THEN MIN($root.${aggregate.column}$FIRST) END" } ?: aggregate(aggregate)
                "$value AS ${aggregate.column}${if (aggregate in held) REPRESENTATIVE else ""}"
            } +
            held.filterIsInstance<Key<*>>().map { "MIN($root.nid) AS ${it.column}$REPRESENTATIVE" }
        val select = selected.joinToString(",\n    ")
        val grouping = listOf("GROUP BY " + grouped.values.joinToString(", "))
        val windows = firsts.map { (aggregate, first) ->
            "FIRST_VALUE($root.nid) OVER (PARTITION BY ${keys.values.joinToString(", ")} ORDER BY CASE WHEN ${first.kept} THEN 0 ELSE 1 END, ${first.order}) AS ${aggregate.column}$FIRST"
        }
        // The rows grouped, with the windows' columns where there are any: the root's own, a node key's, and each window's.
        fun windowed(source: RowSource, where: List<String>): RowSource {
            if (windows.isEmpty()) return source
            val columns = listOf("$root.nid", "$root.labels", "$root.props", "$root.stamp") + paths.map { "$pairs.${it.column} AS ${it.column}" } + windows
            return Derived(Query(columns.joinToString(",\n    "), source, where).text(), root)
        }

        val groupedRows = if (paths.isEmpty()) {
            val source = windowed(roots(), filtered)
            Query(select, source, if (windows.isEmpty()) filtered else emptyList(), grouping).text()
        } else {
            val matched = rows.graphTable(
                pattern(query, paths, nodes),
                listOf(GraphTable.Column("nid", "$root.nid")) + paths.map { GraphTable.Column(it.column, "${nodes.getValue(it)}.nid") },
                "r",
            )
            val distinct = "SELECT DISTINCT r.nid, ${paths.joinToString(", ") { "r.${it.column}" }}\nFROM ${matched.whole()}"
            // The root's own row is read only where a key, an aggregate, a window or a predicate reads a property of it.
            val readsRoot = (selected + filtered + windows).any { "$root." in it }
            val joined = "(\n    ${distinct.under()}\n) $pairs" + if (readsRoot) "\nJOIN ${graph.nodes} $root ON ${OracleSql.sameKey("$root.nid", "$pairs.nid")}" else ""
            val source = object : RowSource {
                override val alias: String get() = pairs
                override fun reading(readers: List<String>): String = joined
            }
            val windowedSource = windowed(source, filtered)
            Query(select, windowedSource, if (windows.isEmpty()) filtered else emptyList(), grouping).text()
        }

        val groups = scope.row("g")
        // A group always has a node with its key's value, and may have none with a least or greatest one.
        val holders = held.associateWith { scope.row("${it.column}_holder") }
        val entries = spec.keys.map { key -> key.column to (nodes[key]?.let(OracleSql::transform) ?: "$groups.${key.column}") } +
            spec.aggregates.map { aggregate ->
                aggregate.column to (holders[aggregate]?.let { fragments.value(it, Fragments.property(requireNotNull(aggregate.propertyName))) } ?: "$groups.${aggregate.column}")
            }
        val joins = nodes.entries.joinToString("") { (key, node) -> "\nJOIN ${graph.nodes} $node ON $node.nid = $groups.${key.column}" } +
            holders.entries.joinToString("") { (column, holder) ->
                "\nLEFT JOIN ${graph.nodes} $holder ON ${OracleSql.sameKey("$holder.nid", "$groups.${column.column}$REPRESENTATIVE")}"
            }
        return GraphStatement(
            hinted(buildString {
                append("SELECT ").append(jsonObject(entries)).append(" AS result")
                append("\nFROM (\n    ").append(groupedRows.under()).append("\n) ").append(groups).append(joins)
                if (spec.havings.isNotEmpty()) {
                    append("\nWHERE ").append(spec.havings.joinToString("\n  AND ") { having(it, groups, holders) })
                }
                append("\nORDER BY ").append(query.orders.joinToString(", ") { groupOrder(query, it, groups, nodes, holders) })
                spec.skip?.let { append("\nOFFSET ${scope.bound(it)} ROWS") }
                spec.limit?.let { append("\nFETCH NEXT ${scope.bound(it)} ROWS ONLY") }
            }),
            bindings(),
        )
    }

    /**
     * The pattern of a grouped read by node keys: the root and from it the way to each key's node,
     * named as [nodes] names it. The root is named in each way, and is one node. Its conditions are
     * asked of its row of the node table: see [groups].
     */
    private fun pattern(query: GroupQuery, paths: List<Key<*>>, nodes: Map<Key<*>, String>): String {
        val met = scope.rootHasLabels(root, rootLabels())
        return paths.mapIndexed { index, key ->
            val path = key.source as KeySource.Path
            buildString {
                append(if (index == 0) OracleSql.node(root, met) else OracleSql.node(root))
                path.hops.forEachIndexed { i, hop ->
                    val last = i == path.hops.lastIndex
                    val node = if (last) nodes.getValue(key) else scope.row("via")
                    val labels = if (last) query.target(path).labels else listOfNotNull(hop.label.takeIf { it.isNotEmpty() })
                    append("\n        ").append(OracleSql.arrow("e${index + 1}_${i + 1}", hop.type, hop.direction))
                    append(" ").append(OracleSql.node(node, hasLabels(node, labels)))
                }
            }
        }.joinToString(",\n          ") + walkedOnce(paths)
    }

    /**
     * Cypher walks a relationship once in a path, and a pattern here may walk it twice: there and back
     * over one, to the node it started from. Two hops of one type in a path are held to be two
     * relationships, so both engines find the same nodes.
     */
    private fun walkedOnce(paths: List<Key<*>>): String {
        val apart = paths.flatMapIndexed { index, key ->
            val hops = (key.source as KeySource.Path).hops
            hops.indices.flatMap { i -> (i + 1..hops.lastIndex).filter { hops[it].type == hops[i].type }.map { j -> "e${index + 1}_${i + 1}.eid <> e${index + 1}_${j + 1}.eid" } }
        }
        return if (apart.isEmpty()) "" else "\n    WHERE " + apart.joinToString(" AND ")
    }

    /** A property key's value: as the store holds it, which tells apart what Cypher does. The stamp is a column of its own, a text. */
    private fun keyValue(key: String): String =
        if (key == Stamps.PROPERTY) property(root, key) else fragments.value(root, Fragments.property(key))

    /**
     * A least or greatest value of a group: the rows [kept] for it, those that have the property and meet the aggregate's
     * filter, and the [order] whose first of them has it, as Cypher orders the property. See [groups].
     */
    private class First(val kept: String, val order: String)

    /** The rows and the order [aggregate], a least or greatest value, is the first of: see [First]. */
    private fun first(aggregate: Aggregate<*>): First {
        val key = requireNotNull(aggregate.propertyName)
        val present = fragments.condition(root, rootLabels(), emptyMap()) { "${Fragments.property(key)} IS NOT NULL" }
        val order = fragments.order(root, aggregate.function == GroupFunction.MAX) { Fragments.property(key) }.joinToString(", ")
        return First(listOfNotNull(present, predicate(aggregate.filter)).joinToString(" AND "), order)
    }

    /**
     * An aggregate over the rows of a group, or over those that meet its filter: a count, a sum or a mean. A filtered count
     * is a sum of ones and zeros; any other filtered aggregate skips the rows that do not meet the filter, as it skips a
     * null. SQL's sum over no values is null, and a grouped read's is 0. A least or greatest value is [first]'s.
     */
    private fun aggregate(aggregate: Aggregate<*>): String {
        val filter = predicate(aggregate.filter)
        if (aggregate.function == GroupFunction.COUNT) {
            return if (filter == null) "COUNT(*)" else "COALESCE(SUM(CASE WHEN $filter THEN 1 ELSE 0 END), 0)"
        }
        val key = requireNotNull(aggregate.propertyName)
        val stored = property(root, key, SqlKind.NUMBER)
        val value = if (filter == null) stored else "CASE WHEN $filter THEN $stored END"
        return if (aggregate.function == GroupFunction.SUM) "COALESCE(SUM($value), 0)" else "${aggregate.function.name}($value)"
    }

    /**
     * One condition of a `having`: a count, a sum or a mean is a number, compared as one; a least or
     * greatest value is compared as Cypher compares it, by the module, on the node of the group that has
     * it ([holders]), and is no match where the group has none.
     */
    private fun having(having: HavingCondition, groups: String, holders: Map<GroupColumn<*>, String>): String {
        val aggregate = having.aggregate
        val holder = holders[aggregate] ?: return "$groups.${aggregate.column} ${having.operator.cypherOperator} ${scope.bound(if (having.value is Boolean) having.value.toString() else having.value)}"
        val key = requireNotNull(aggregate.propertyName)
        return fragments.condition(holder, rootLabels(), mapOf("value" to Fragments.value(having.operator, having.value))) { names ->
            Fragments.compared(key, having.operator, names)
        }
    }

    /**
     * One term of a grouped read's order: a count, a sum or a mean as the number it is; a property key or
     * a least or greatest value as Cypher orders it, over the node of the group that has it ([holders]);
     * a node key by its node's id.
     */
    private fun groupOrder(query: GroupQuery, order: GroupOrder, groups: String, nodes: Map<Key<*>, String>, holders: Map<GroupColumn<*>, String>): String {
        val descending = order.direction == OrderDirection.DESC
        holders[order.column]?.let { holder ->
            val key = when (val column = order.column) {
                is Key<*> -> (column.source as KeySource.Property).propertyName
                is Aggregate<*> -> requireNotNull(column.propertyName)
            }
            return fragments.ordered(holder, Fragments.property(key), descending, order.nulls).joinToString(", ")
        }
        nodes[order.column]?.let { node ->
            val target = query.target((order.column as Key<*>).source as KeySource.Path)
            return fragments.ordered(node, Fragments.property(requireNotNull(target.nodeIdProperty)), descending, order.nulls).joinToString(", ")
        }
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> " NULLS FIRST"
            NullPlacement.LAST -> " NULLS LAST"
            null -> ""
        }
        return "$groups.${order.column.column} ${order.direction.name}$nulls"
    }

    /**
     * The nodes the statement's roots are among: those with the root's labels. They give the columns
     * the statement reads, and every column once a predicate written by hand is in it: what that
     * reads cannot be told from its text.
     */
    private fun roots(): RowSource = rows.nodes(rootLabels(), root).let { if (handWritten) it.entire() else it }

    /**
     * The predicate that selects the roots of this statement from the node table, when it is aliased
     * as [root]: those with the labels that [predicates] select. A delete selects them so, as the
     * Cypher delete does; a load and a count also ask for the relationships the view requires.
     */
    override fun selecting(predicates: List<String>): String =
        (listOfNotNull(scope.rootHasLabels(root, rootLabels())) + predicates).joinToString("\n  AND ")

    /**
     * A predicate the caller wrote, with each property of the root written as Cypher writes it read as
     * that property: see [CypherShorthand]. From then on the root gives every column. In parentheses, since it may hold an `OR`, and is joined to
     * the statement's own predicates by `AND`; those are written so that they need none.
     */
    override fun written(predicate: String): String {
        handWritten = true
        return "(${shorthand.rewrite(predicate)})"
    }

    /** Whether the statement holds a predicate written by hand, which may read any column of the root, named any way SQL allows. */
    private var handWritten = false

    /** The root whose id is bound as [parameter]. */
    override fun hasId(parameter: String): String {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        scope.narrowed = true
        return "${property(root, idProperty)} = \$$parameter"
    }

    /**
     * The query that counts the node whose `nid` is its parameter [NID], if it is a root
     * that meets [conditions]: 1, or 0. Null when there are no conditions to meet. The node is read
     * from the node table, as a delete reads the roots it selects. For a block that has found the
     * node and locked it: see [OracleWriteBlock].
     */
    override fun meeting(conditions: List<WhereCondition>): String? {
        scope.narrowed = true
        val predicate = predicate(conditions) ?: return null
        return hinted("SELECT COUNT(*)\nFROM ${graph.nodes} $root\nWHERE $root.nid = \$$NID\n  AND ${selecting(listOf(predicate))}")
    }

    /**
     * The predicate [conditions] of the query DSL make on the root; null when there are none. [indexed]
     * when every root the statement reads by its labels meets it. See [OraclePredicates.predicate].
     */
    override fun predicate(conditions: List<WhereCondition>, indexed: Boolean): String? = predicates.predicate(conditions, indexed)

    /**
     * [sql], one statement this select's predicates and orders are in, with the optimizer hints their fragments ask for
     * ([Fragments.hints]) in its first `SELECT` or `DELETE`, the outermost: without `NO_JSON_TABLE_TRANSFORM`, Oracle 23 can
     * give a comparison the wrong truth. Called once the statement is written, when every fragment in it is.
     */
    fun hinted(sql: String): String {
        if (fragments.hints.isEmpty()) return sql
        val keyword = LEADING_KEYWORD.find(sql) ?: error("No SELECT or DELETE begins the statement to hint: $sql")
        val at = keyword.range.last + 1
        return sql.substring(0, at) + hint() + sql.substring(at)
    }

    /** The comment of [hinted]'s hints, with a space before it, for a statement written elsewhere; empty where there are none. */
    fun hint(): String = if (fragments.hints.isEmpty()) "" else " /*+ ${fragments.hints.joinToString(" ")} */"

    /** A collection sorted by a property of its nodes, as Cypher orders it, with the relationship's id to break a tie. */
    override fun sorted(row: String, targetType: Class<*>, sort: CollectionSortSpec): String =
        (fragments.ordered(row, Fragments.property(sort.propertyName), !sort.ascending, null) + "$row.eid").joinToString(", ")

    /** The predicate that selects the rows after a keyset cursor. See [OraclePredicates.after]. */
    override fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String = predicates.after(orders, values, collectionSortCount)

    // ----- Projection -----

    override val emptyArray: String = EMPTY_ARRAY

    override fun document(row: String): String = OracleSql.document(row)

    override fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "JSON_OBJECT(\n    ", "\n    RETURNING JSON)") { (key, value) -> "${literal(key)} VALUE ${value.under()}" }

    override fun arrayOf(value: String, order: String): String = "JSON_ARRAYAGG($value ORDER BY $order RETURNING JSON)"

    override fun withTarget(row: String, field: String, target: String): String = "JSON_TRANSFORM($row.eprops, SET ${path(field)} = $target)"

    override fun property(row: String, key: String, kind: SqlKind): String = OracleSql.property(row, key, kind)

    override fun related(select: String, parent: String, steps: List<Step>, row: String, tail: List<String>): Query = rows.related(select, parent, steps, row, tail)

    override fun exists(parent: String, steps: List<Step>, row: String): String = rows.exists(parent, steps, row)

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth. The path gives a node once for each way to it, so its
     * rows are grouped by the node reached, beside the root they were reached from.
     *
     * The nodes are read where the path ends and not on their own, as those whose id the path
     * reaches: Oracle answers that by reading every node of the graph and asking the path of each.
     */
    override fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String {
        val row = scope.row(relationship.fieldName)
        val path = scope.row("reached")
        val value = element(row, targetType, visits, relationship.fieldName)
        val order = pathOrder(relationship, steps, path)
        val once = Derived(
            Query(
                "$path.src, $path.nid, ANY_VALUE($path.doc) AS doc${order?.selected.orEmpty()}",
                rows.reached(parent, steps, path, extra = order?.columns.orEmpty()),
                tail = listOf("GROUP BY $path.src, $path.nid"),
            ).text(),
            row,
        )
        val reached = listOf("$row.src = $parent.nid")
        val sort = order?.by(row) ?: "$row.nid"
        return if (relationship.isCollection) {
            "COALESCE(${Query(arrayOf(value, sort), once, reached).nested()}, $emptyArray)"
        } else {
            Query(value, once, reached, listOf(first(sort))).nested()
        }
    }

    /**
     * The order of a path that one of its hops names, as Cypher reads it: each node reached keeps the
     * value of the first node of that hop that leads to it, the lowest or, read highest first, the
     * highest. The property's type is not known here, since the node the hop reaches is mapped to no
     * class, so it is read twice: as a number where it is stored as one, and as text. As the graph
     * engines order them, every text comes before every number, lowest first; and a node of the hop
     * that has no value comes last whichever way the path is read. `JSON_VALUE` reads a stored `""`
     * as null, so whether the node holds one is read on its own: it is a text, and the lowest.
     */
    private fun pathOrder(relationship: RelationshipModel, steps: List<Step>, path: String): PathOrder? {
        val i = relationship.orderedHop ?: return null
        val hop = relationship.hops[i]
        val node = rows.stepNode(steps, i, path)
        val key = requireNotNull(hop.orderBy)
        return PathOrder(
            listOf(
                GraphTable.Column("order_number", SqlKind.NUMBER.read("$node.props", path(key, ".numberOnly()"))),
                GraphTable.Column("order_text", property(node, key, SqlKind.TEXT)),
                GraphTable.Column("order_empty", "CASE WHEN JSON_EXISTS($node.props, ${path(key, "?(@ == \"\")")}) THEN 1 END"),
            ),
            hop.ascending,
            path,
        )
    }

    /**
     * The order of a path, read from the [columns] of each way to a node and kept for the node by
     * [selected]: `order_kind` is 0 for a text and 1 for a number, and null where the node of the hop
     * has no value; `order_number` is the value of a number, and `order_text` of a text other than
     * `""`. Each is ordered only where the node's value is of its kind, so a node kept as a text is
     * not ordered by a number that also leads to it.
     */
    private class PathOrder(val columns: List<GraphTable.Column>, private val ascending: Boolean, private val path: String) {
        private val keep = if (ascending) "MIN" else "MAX"
        private val direction = if (ascending) "ASC" else "DESC"

        /**
         * What the rows grouped by the node reached keep of the order. Read lowest first, a node that
         * a `""` leads to keeps no text, which orders first among the texts; read highest first, it
         * keeps the highest text, and none only where every text that leads to it is `""`.
         */
        val selected: String
            get() {
                val text = "CASE WHEN $path.order_number IS NULL THEN $path.order_text END"
                val kept = if (ascending) "CASE WHEN MAX($path.order_empty) IS NULL THEN MIN($text) END" else "MAX($text)"
                return """
                    , $keep(CASE WHEN $path.order_number IS NOT NULL THEN 1 WHEN $path.order_text IS NOT NULL OR $path.order_empty = 1 THEN 0 END) AS order_kind
                    , $keep($path.order_number) AS order_number
                    , $kept AS order_text
                """.trimIndent().lines().joinToString(" ")
            }

        /**
         * The order of the row [row]: the nodes with no value last, texts before numbers, then the
         * value, with the node's id last so that equal values are read in one order. A text with no
         * value is `""`, the lowest text.
         */
        fun by(row: String): String = listOf(
            "$row.order_kind $direction NULLS LAST",
            "CASE WHEN $row.order_kind = 1 THEN $row.order_number END $direction",
            "CASE WHEN $row.order_kind = 0 THEN $row.order_text END $direction NULLS ${if (ascending) "FIRST" else "LAST"}",
            "$row.nid",
        ).joinToString(", ")
    }

    // ----- Scored search -----

    /**
     * The search whose candidates are the rows of [candidates], each with a `score`: one document
     * for each, `{value, score}`, best first. [predicates], the score's [threshold] and a view's
     * required relationships are applied to the candidates, not before them: a search finds its
     * nearest and then filters, as an index of the graph engines does.
     */
    override fun scored(candidates: String, predicates: List<String>, threshold: Double?, limit: Int?): GraphStatement {
        val result = "${jsonObject(listOf("value" to projection(), "score" to "$root.score"))} AS result"
        val filters = requiredRelationships() + listOfNotNull(threshold?.let { "$root.score >= ${scope.bound(it)}" }) + predicates
        val best = listOfNotNull("ORDER BY $root.score DESC, $root.nid", limit?.let { "FETCH FIRST ${scope.bound(it)} ROWS ONLY" })
        return GraphStatement(hinted(Query(result, Derived(candidates, root), filters, best).text()), bindings())
    }

    /** The root's nodes that also carry [also] and meet [where], each with its columns and `score`, ordered and cut by [tail]. */
    private fun candidates(also: List<String>, where: List<String>, score: String, tail: List<String>): String {
        val has = (listOf(hasLabels("c", rootLabels() + also, scanned = true)) + where).joinToString(" AND ")
        return Query("${OracleSql.columns("c")}, $score AS score", scanned("c", has), tail = tail).text()
    }

    /** Every node that meets [has], as rows named [row] with all a node's columns: what a search without an index reads. */
    private fun scanned(row: String, has: String): GraphTable = rows.graphTable(OracleSql.node(row, has), GraphTable.node(row), row)

    /**
     * The [nearest] nodes to [vector] by the embedding stored under [key]: every node of the root's
     * labels, and [partitionLabel] when there is one, that holds an embedding of the vector's size is
     * measured, so the answer is exact. A node with another size is left out, as the other stores leave
     * it out, rather than failing the statement. The score is the one the graph engines give: for cosine
     * `(1 + cos) / 2`, for Euclidean `1 / (1 + d²)`, each in `[0, 1]` with higher the nearer.
     */
    fun nearestTo(key: String, similarity: SimilarityFunction, partitionLabel: String?, vector: List<Float>, nearest: Int): String {
        val score = OracleSql.score(similarity, "VECTOR_DISTANCE(${OracleSql.embedding("c", key)}, TO_VECTOR(${scope.bound(vector)}), ${OracleSql.metric(similarity)})")
        val holds = listOf(OracleSql.hasProperty("c", key), OracleSql.holdsEmbedding("c", key, vector.size))
        return candidates(listOfNotNull(partitionLabel), holds, score, listOf("ORDER BY score DESC, c.nid", "FETCH FIRST ${scope.bound(nearest)} ROWS ONLY"))
    }

    /**
     * The [nearest] nodes to [vector] through a vector index: [embeddings] is the table the index is
     * over, which holds the embedding of each node of the index's label. The index gives its nearest,
     * approximately, and they are then tested for the root's own labels.
     */
    fun nearestThrough(embeddings: String, similarity: SimilarityFunction, vector: List<Float>, nearest: Int): String {
        val distance = "VECTOR_DISTANCE(s.embedding, TO_VECTOR(${scope.bound(vector)}), ${OracleSql.metric(similarity)})"
        return """
            SELECT c.nid, c.labels, c.props, c.stamp, ${OracleSql.transform("c")} AS doc, ${OracleSql.score(similarity, "k.distance")} AS score
            FROM (SELECT s.nid, $distance AS distance FROM $embeddings s ORDER BY distance FETCH APPROX FIRST ${scope.bound(nearest)} ROWS ONLY) k
            JOIN ${graph.nodes} c ON c.nid = k.nid
            WHERE ${hasLabels("c", rootLabels())}
        """.trimIndent()
    }

    /**
     * For each root among [ids] that holds an embedding under [key], its [topK] nearest among the
     * others, as one document a root, `{seed, near}`: see [org.drivine.backend.GraphBackend.nearestAmong].
     * Each pair is scored as [nearestTo] and [nearestThrough] score it.
     *
     * With no [embeddings], no vector index has been made, and every pair of roots whose embeddings are
     * of one size is measured: the answer is exact. With the table the index is over, each root asks
     * the index for its [searchK] nearest, as [nearestThrough] does, and keeps those among the roots:
     * approximate, as the index is. Only the roots the table holds are searched from: it holds only
     * embeddings of the index's size, and Oracle refuses to measure two sizes against each other, so a
     * root with another is left out, as the Postgres select leaves it out. Either way each root's
     * neighbours are numbered best first, and the first [topK] kept.
     */
    fun nearestAmong(key: String, similarity: SimilarityFunction, ids: List<String>, topK: Int, threshold: Double?, embeddings: String?, searchK: Int): GraphStatement {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        val members = Query(
            "c.nid, ${property("c", idProperty)} AS id, ${OracleSql.embedding("c", key)} AS embedding", Table(graph.nodes, "c"),
            listOfNotNull(
                hasLabels("c", rootLabels()), OracleSql.hasProperty("c", key), "${property("c", idProperty)} IN (${OracleSql.values(scope.bound(ids), SqlKind.TEXT)})",
                embeddings?.let { "c.nid IN (SELECT e.nid FROM $it e)" },
            ),
        )
        val metric = OracleSql.metric(similarity)
        val pairs = if (embeddings == null) {
            "SELECT s.nid AS seed, o.nid, o.id, ${OracleSql.score(similarity, "VECTOR_DISTANCE(o.embedding, s.embedding, $metric)")} AS score\n" +
                "FROM $MEMBERS s\nJOIN $MEMBERS o ON o.nid <> s.nid AND VECTOR_DIMENSION_COUNT(o.embedding) = VECTOR_DIMENSION_COUNT(s.embedding)"
        } else {
            "SELECT s.nid AS seed, o.nid, o.id, ${OracleSql.score(similarity, "k.distance")} AS score\n" +
                "FROM $MEMBERS s\nCROSS APPLY (\n" +
                "    SELECT e.nid, VECTOR_DISTANCE(e.embedding, s.embedding, $metric) AS distance FROM $embeddings e\n" +
                "    ORDER BY distance FETCH APPROX FIRST ${scope.bound(searchK)} ROWS ONLY) k\n" +
                "JOIN $MEMBERS o ON o.nid = k.nid AND o.nid <> s.nid"
        }
        val ranked = Query(
            "p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place", Derived(pairs, "p"),
        )
        val near = Query(
            arrayOf(jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
            Table(RANKED, "r"),
            listOfNotNull("r.seed = s.nid", "r.place <= ${scope.bound(topK)}", threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = jsonObject(listOf("seed" to "s.id", "near" to "COALESCE(${near.nested()}, $EMPTY_ARRAY)"))
        return GraphStatement(
            "WITH $MEMBERS AS ${members.nested()},\n$RANKED AS ${ranked.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text(),
            bindings(),
        )
    }

    /**
     * The nodes whose text under [keys] meets the user's [query], each with its columns, its document made as the graph's
     * vertex makes it, and its `score`: the candidates the shared module's full-text search ranks
     * (`org.drivine.cypher.api.FullTextSearch`), joined back to the node table.
     *
     * Without [texts], every node of the label is read and scored by BM25, as Postgres scores them. With it, the search is
     * through an Oracle Text index: [texts] is the table the index is over, and Oracle Text scores, each property's score
     * added. The index holds what has been committed, so a search does not see what its own transaction has written.
     */
    fun matching(keys: List<String>, query: String, texts: String? = null): String = Query(
        "c.nid, c.labels, c.props, c.stamp, ${OracleSql.transform("c")} AS doc, m.score",
        OnceReached(ranked(SearchStore.ORACLE, graph.name, keys, query, SearchIndex(oracleText = texts)), "m", graph.nodes, "c"),
    ).text()

    // ----- What a delete or a traversal on its own selects -----

    /**
     * The ids of the nodes a cascading delete takes with the roots that meet [predicates]: every node
     * reached through a relationship field the view declares and writes, and through those of the views
     * nested in it. A field that holds views of its own class is followed to its depth, and every level
     * of it holds what the root does.
     */
    override fun cascaded(predicates: List<String>): String? {
        val model = viewModel ?: return null
        val paths = Cascade.of(model).paths
        if (paths.isEmpty()) return null
        return paths.joinToString("\nUNION\n") { steps -> reachedFromRoots(steps, predicates) }
    }

    /**
     * For each field that holds views of its own class and that a cascading delete of the roots that
     * meet [predicates] follows: the field, and the query that counts the nodes it reaches one level
     * below its depth that it does not reach within it, nor starts from. Such a node is related to one
     * the delete takes, and would be left behind with all it holds.
     */
    override fun cascadeDepthChecks(predicates: List<String>): List<Pair<CascadeRecursion, String>> {
        val model = viewModel ?: return emptyList()
        return Cascade.of(model).recursions.map { recursion ->
            val step = recursion.step
            val within = recursion.path + step
            val beyond = recursion.path + Step(step.type, step.direction, step.labels, step.maxDepth + 1, minDepth = step.maxDepth + 1)
            val parents = if (recursion.path.isEmpty()) roots(predicates) else reachedFromRoots(recursion.path, predicates)
            val row = scope.row("beyond")
            val left = listOf(
                "$row.src IN (\n    ${roots(predicates).under()})",
                "$row.nid NOT IN (\n    ${reachedFromRoots(within, predicates).under()})",
                "$row.nid NOT IN (\n    ${parents.under()})",
            )
            recursion to hinted(Query("COUNT(DISTINCT $row.nid)", rows.reached(root, beyond, row, trail = false), left).text())
        }
    }

    /** Each step of the paths a cascade follows, once for each type and direction: a node one such step below another is held by it. */
    override fun cascadeSteps(): List<Step> =
        viewModel?.let { model -> Cascade.of(model).steps }.orEmpty()

    /** The roots that meet [predicates], as a query of their ids. */
    private fun roots(predicates: List<String>): String = "SELECT $root.nid\nFROM ${graph.nodes} $root\nWHERE ${selecting(predicates)}"

    /** The ids of the nodes [steps] reach from the roots that meet [predicates]. */
    private fun reachedFromRoots(steps: List<Step>, predicates: List<String>): String {
        val row = scope.row("cascaded")
        return Query("$row.nid", rows.reached(root, steps, row, trail = false), listOf("$row.src IN (\n    ${roots(predicates).under()})")).text()
    }

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     *
     * The node is found first, from the node table, and the relationship is tested against it. Tested
     * in the pattern, the id is all that is read of that node when it has no label, and Oracle Free
     * 23.26 drops such a node from the plan when a range index is over the property, and the test
     * with it. Here that ended the server process (`ORA-07445` in `kkocfbMarkBindFroCB`, which the
     * client sees as `ORA-03113`); a count of such a pattern answers for every node.
     */
    override fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        scope.narrowed = true
        val from = scope.row("from")
        val row = scope.row("related")
        val found = Query("$from.nid", Table(graph.nodes, from), listOfNotNull("${property(from, key)} = \$$parameter", hasLabels(from, labels)))
        val reached = rows.reached(from, listOf(Step(type, direction, emptyList())), row)
        return "$root.nid IN ${Query("$row.nid", reached, listOf("$row.src IN ${found.nested()}")).nested()}"
    }

    companion object {
        /** The parameter of [meeting] for the `nid` of the node it counts: no value is bound to it, the block gives its own. */
        const val NID = "_nid"

        /** What [nearestAmong] calls the roots among its ids, which each of them measures the others of. */
        private const val MEMBERS = "members"

        /** What follows a grouped column's name for the id of a node of the group that has its value: see [groups]. */
        private const val REPRESENTATIVE = "r"

        /** What follows a least or greatest value's name for the column of the window that gives its node: see [groups]. */
        private const val FIRST = "_first"

        /** The keyword a statement [hinted] begins with, after any blank: the hints follow it. */
        private val LEADING_KEYWORD = Regex("^\\s*(SELECT|DELETE)\\b", RegexOption.IGNORE_CASE)

        /** What [nearestAmong] calls each root's neighbours, scored and numbered best first. */
        private const val RANKED = "ranked"
    }
}
