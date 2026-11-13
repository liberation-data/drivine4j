package org.drivine.backend.postgres

import org.drivine.connection.JacksonEncoder
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.backend.sql.Fragments
import org.drivine.backend.sql.Cascade
import org.drivine.backend.sql.CascadeRecursion
import org.drivine.annotation.Direction
import org.drivine.cypher.api.SearchIndex
import org.drivine.cypher.api.SearchStore
import org.drivine.backend.GraphStatement
import org.drivine.backend.postgres.PostgresSql.EMPTY_ARRAY
import org.drivine.backend.postgres.PostgresSql.document
import org.drivine.backend.postgres.PostgresSql.property
import org.drivine.backend.sql.CypherShorthand
import org.drivine.backend.sql.Derived
import org.drivine.backend.sql.OnceReached
import org.drivine.backend.sql.Query
import org.drivine.backend.sql.RowSource
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.SqlViewSelect
import org.drivine.backend.sql.StatementScope
import org.drivine.backend.sql.Step
import org.drivine.backend.sql.Table
import org.drivine.backend.sql.under
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.GroupQuery
import org.drivine.query.SqlParameters
import org.drivine.query.dsl.Aggregate
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GroupFunction
import org.drivine.query.dsl.GroupColumn
import org.drivine.query.dsl.GroupOrder
import org.drivine.query.dsl.HavingCondition
import org.drivine.query.dsl.Key
import org.drivine.query.dsl.KeySource
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.schema.SimilarityFunction

/**
 * Writes one statement that reads a graph kept by Postgres: a load, a count, or the predicate of a
 * delete. One instance writes one statement, and holds the values its parameters take, so it is
 * stateful: [hasId], [relatedTo] and [after] say something about the statement, and are called
 * before [load], [count] or [selecting] writes it.
 *
 * A load returns one `jsonb` document for each root, shaped as the Cypher load's map is: a fragment is
 * its properties with its `labels`; a view is its root and each relationship field, a collection as
 * an array and a single one as an object or null. A relationship field is a subquery over the nodes
 * [PostgresRowSources] reaches from the root's row; the predicates of the query DSL are written by
 * [PostgresPredicates], and a predicate written by hand is read by [CypherShorthand].
 *
 * A row is called what the model calls it, the root by its field and a related node by the field that
 * holds it, so a statement reads beside the Cypher it stands for.
 */
internal class PostgresSelect(
    private val graph: PostgresGraph,
    graphClass: Class<*>,
    collectionSorts: List<CollectionSortSpec> = emptyList(),
    depthOverrides: Map<String, Int> = emptyMap(),
) : SqlViewSelect<StatementScope>(graphClass, collectionSorts, depthOverrides, StatementScope(MAX_NAME)) {
    private val rows = PostgresRowSources(graph, scope)
    /** The values this statement's conditions and orders compare, compiled by the module ([Fragments]). */
    private val fragments = Fragments(PostgresCypherCompiler(graph.name, JacksonEncoder), scope)
    private val predicates = PostgresPredicates(scope, rows, rootAlias, root, rootModel, viewModel, depthOverrides, fragments)
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
        val page = listOfNotNull(
            orders.takeIf { it.isNotEmpty() }?.let { "ORDER BY " + it.joinToString(", ", transform = this.predicates::orderTerms) },
            skip?.let { "OFFSET ${scope.bound(it)} ROWS" },
            limit?.let { "FETCH NEXT ${scope.bound(it)} ROWS ONLY" },
        )
        return GraphStatement(Query(result, roots(), selected(predicates), page).text(), bindings())
    }

    /** The count of the roots [predicates] select, and that have the relationships the view requires. */
    override fun count(predicates: List<String>): GraphStatement =
        GraphStatement(Query("COUNT(*)", roots(), selected(predicates)).text(), bindings())

    /**
     * The grouped read [query] asks of the roots: one `jsonb` document for each group, a key under `k0…`
     * and an aggregate under `a0…`.
     *
     * The groups are made by a query of their own, `GROUP BY` over the roots, and the statement reads
     * its rows: it filters them for a `having`, orders and pages them, and joins the node of a node key
     * back for its document.
     *
     * A property key is the property's value as the store holds it ([Fragments.value]), so values Cypher
     * tells apart are grouped apart, and a temporal value comes back as its kind. Where the groups are
     * ordered by a property key, or ordered or filtered by a least or greatest value, the grouped query
     * also gives a node of the group that has that value (`k0r`, `a0r`), which the statement joins back,
     * so the module orders and compares the value as Cypher does, over a row of its own.
     *
     * A node key is a path, and a path gives a root once for each way to a key node. The roots are then
     * read as the distinct pairs of a root and its key nodes, by their ids, each path walked from the
     * root's row, and each root's row is joined to its pairs again where a key, an aggregate or a
     * predicate reads it.
     */
    override fun groups(query: GroupQuery): GraphStatement {
        val spec = query.spec
        val paths = spec.keys.filter { it.source is KeySource.Path }
        val nodes = paths.associateWith { scope.row(it.column) }
        val filtered = listOfNotNull(predicate(spec.conditions))

        // The columns the groups are ordered or filtered by through a node that has the value: see the KDoc.
        val compared = (query.orders.map { it.column } + spec.havings.map { it.aggregate }).filter { column ->
            when (column) {
                is Key<*> -> (column.source as? KeySource.Property)?.propertyName?.let { it != Stamps.PROPERTY } == true
                is Aggregate<*> -> column.function == GroupFunction.MIN || column.function == GroupFunction.MAX
            }
        }.toSet()

        val pairs = scope.row("pair")
        val keys = spec.keys.associateWith { key ->
            when (val from = key.source) {
                is KeySource.Property -> keyValue(from.propertyName)
                is KeySource.Path -> "$pairs.${key.column}"
            }
        }
        val representatives = compared.filterIsInstance<Key<*>>().map { "MIN($root.nid) AS ${it.column}$REPRESENTATIVE" }
        val selected = keys.map { (key, expression) -> "$expression AS ${key.column}" } +
            spec.aggregates.flatMap { aggregate ->
                aggregate(aggregate).let { (value, node) -> listOf("$value AS ${aggregate.column}") + if (aggregate in compared) listOf("$node AS ${aggregate.column}$REPRESENTATIVE") else emptyList() }
            } + representatives
        val select = selected.joinToString(",\n    ")
        val grouping = "GROUP BY " + keys.values.joinToString(", ")

        val grouped = if (paths.isEmpty()) {
            // A grouped read's source is a fragment, which requires no relationship.
            Query(select, roots(), listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + filtered, listOf(grouping)).text()
        } else {
            // Each path is walked from the root's row: a lateral join, which may read the row it is joined to.
            val walked = paths.joinToString("") { key ->
                val path = key.source as KeySource.Path
                val steps = path.hops.mapIndexed { i, hop ->
                    Step(hop.type, hop.direction, if (i == path.hops.lastIndex) query.target(path).labels else listOfNotNull(hop.label.takeIf { it.isNotEmpty() }))
                }
                val reached = rows.reached(PostgresRowSources.Anchor.row(root), steps, nodes.getValue(key))
                "\nJOIN LATERAL ${reached.reading(emptyList())} ON TRUE"
            }
            val where = listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + filtered
            val distinct = "SELECT DISTINCT $root.nid, ${paths.joinToString(", ") { "${nodes.getValue(it)}.nid AS ${it.column}" }}\n" +
                "FROM ${graph.nodes} $root$walked" + if (where.isEmpty()) "" else "\nWHERE ${where.joinToString("\n  AND ")}"
            // The root's own row is read only where a key or an aggregate reads it.
            val readsRoot = selected.any { "$root." in it }
            val from = "(\n    ${distinct.under()}\n) $pairs" + if (readsRoot) "\nJOIN ${graph.nodes} $root ON $root.nid = $pairs.nid" else ""
            "SELECT $select\nFROM $from\n$grouping"
        }

        val groups = scope.row("g")
        val keyNodes = paths.associateWith { scope.row("${it.column}_node") }
        // A group always has a node with its key's value, and may have none with a least or greatest one.
        val holders = compared.associateWith { scope.row("${it.column}_holder") }
        val entries = spec.keys.map { key -> key.column to (keyNodes[key]?.let(::document) ?: "$groups.${key.column}") } +
            spec.aggregates.map { it.column to "$groups.${it.column}" }
        val joins = keyNodes.entries.joinToString("") { (key, node) -> "\nJOIN ${graph.nodes} $node ON $node.nid = $groups.${key.column}" } +
            holders.entries.joinToString("") { (column, holder) -> "\nLEFT JOIN ${graph.nodes} $holder ON $holder.nid = $groups.${column.column}$REPRESENTATIVE" }
        return GraphStatement(
            buildString {
                append("SELECT ").append(PostgresSql.jsonObject(entries)).append(" AS result")
                append("\nFROM (\n    ").append(grouped.under()).append("\n) ").append(groups).append(joins)
                if (spec.havings.isNotEmpty()) {
                    append("\nWHERE ").append(spec.havings.joinToString("\n  AND ") { having(it, groups, holders) })
                }
                append("\nORDER BY ").append(query.orders.joinToString(", ") { groupOrder(query, it, groups, keyNodes, holders) })
                spec.skip?.let { append("\nOFFSET ${scope.bound(it)} ROWS") }
                spec.limit?.let { append("\nFETCH NEXT ${scope.bound(it)} ROWS ONLY") }
            },
            bindings(),
        )
    }

    /**
     * A property key's value: as the store holds it, which tells apart what Cypher does. The stamp is a
     * column of its own, a text.
     */
    private fun keyValue(key: String): String =
        if (key == Stamps.PROPERTY) property(root, key) else fragments.value(root, Fragments.property(key))

    /**
     * An aggregate over the rows of a group, or over those that meet its filter, and for a least or
     * greatest value the id of a node that has it. A filtered count is a sum of ones and zeros; any
     * other filtered aggregate skips the rows that do not meet the filter, as it skips a null. SQL's sum
     * over no values is null, and a grouped read's is 0. A least or greatest value is the first of the
     * group's values as Cypher orders them, nulls left out.
     */
    private fun aggregate(aggregate: Aggregate<*>): Pair<String, String?> {
        val filter = predicate(aggregate.filter)
        if (aggregate.function == GroupFunction.COUNT) {
            return (if (filter == null) "COUNT(*)" else "COALESCE(SUM(CASE WHEN $filter THEN 1 ELSE 0 END), 0)") to null
        }
        val key = requireNotNull(aggregate.propertyName)
        if (aggregate.function == GroupFunction.MIN || aggregate.function == GroupFunction.MAX) {
            val present = fragments.condition(root, rootLabels(), emptyMap()) { "${Fragments.property(key)} IS NOT NULL" }
            val kept = listOfNotNull(present, filter).joinToString(" AND ")
            val order = fragments.order(root, aggregate.function == GroupFunction.MAX) { Fragments.property(key) }.joinToString(", ")
            fun first(of: String) = "(array_agg($of ORDER BY $order) FILTER (WHERE $kept))[1]"
            return first(fragments.value(root, Fragments.property(key))) to first("$root.nid")
        }
        val stored = property(root, key, SqlKind.NUMBER)
        val value = if (filter == null) stored else "CASE WHEN $filter THEN $stored END"
        return (if (aggregate.function == GroupFunction.SUM) "COALESCE(SUM($value), 0)" else "${aggregate.function.name}($value)") to null
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

    /** What a load or a count asks of a root: its labels, [predicates], and the relationships the view requires. */
    private fun selected(predicates: List<String>): List<String> =
        listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + predicates + requiredRelationships()

    /** The nodes the statement's roots are among. */
    private fun roots(): RowSource = rows.nodes(root)

    /**
     * The predicate that selects the roots of this statement from the node table, when it is aliased
     * as [root]: those with the labels that [predicates] select. A delete selects them so, as the
     * Cypher delete does; a load and a count also ask for the relationships the view requires.
     */
    override fun selecting(predicates: List<String>): String =
        (listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + predicates).joinToString("\n  AND ")

    /**
     * A predicate the caller wrote, with each property of the root written as Cypher writes it read as
     * that property: see [CypherShorthand]. In parentheses, since it may hold an `OR`, and is joined to
     * the statement's own predicates by `AND`.
     */
    override fun written(predicate: String): String = "(${shorthand.rewrite(predicate)})"

    /**
     * The root whose id is bound as [parameter]. A property is read as text, so the id is compared as
     * its text: a number bound as one is not compared with a text.
     */
    override fun hasId(parameter: String): String {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        return "${property(root, idProperty)} = CAST(\$$parameter AS text)"
    }

    /**
     * The query that counts the node whose `nid` is its parameter `$1`, if it is a root that meets
     * [conditions]: 1, or 0. Null when there are no conditions to meet. The node is read from the node
     * table, as a delete reads the roots it selects. For the write function, which has found the node
     * and locked it, and runs the query where its values cannot be bound: they are written into it as
     * literals (see [PostgresSql.inline]).
     */
    override fun meeting(conditions: List<WhereCondition>): String? {
        val predicate = predicate(conditions) ?: return null
        val query = "SELECT COUNT(*)\nFROM ${graph.nodes} $root\nWHERE $root.nid = \$1\n  AND ${selecting(listOf(predicate))}"
        val values = bindings()
        val names = SqlParameters.positional(query).names
        return SqlParameters.positional(query) { PostgresSql.inline(values.getValue(names[it])) }.statement
    }

    /**
     * The predicate [conditions] of the query DSL make on the root; null when there are none. Whether it is
     * [indexed] changes nothing: a partial index over the nodes of a label is the planner's to choose.
     */
    override fun predicate(conditions: List<WhereCondition>, indexed: Boolean): String? = predicates.predicate(conditions)

    /** The predicate that selects the rows after a keyset cursor. See [PostgresPredicates.after]. */
    override fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String = predicates.after(orders, values, collectionSortCount)

    // ----- Projection -----

    override val emptyArray: String = EMPTY_ARRAY

    override fun document(row: String): String = PostgresSql.document(row)

    override fun jsonObject(entries: List<Pair<String, String>>): String = PostgresSql.jsonObject(entries)

    override fun arrayOf(value: String, order: String): String = PostgresSql.arrayOf(value, order)

    override fun withTarget(row: String, field: String, target: String): String = "($row.eprops || jsonb_build_object(${PostgresSql.literal(field)}, $target))"

    override fun property(row: String, key: String, kind: SqlKind): String = PostgresSql.property(row, key, kind)

    override fun related(select: String, parent: String, steps: List<Step>, row: String, tail: List<String>): Query = rows.related(select, parent, steps, row, tail)

    override fun exists(parent: String, steps: List<Step>, row: String): String = rows.exists(parent, steps, row)

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth. The path gives a node once for each way to it, so its
     * ways are grouped by the node reached, and the node is read again from the node table.
     */
    override fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String {
        val row = scope.row(relationship.fieldName)
        val path = scope.row("reached")
        val once = scope.row("once")
        val value = element(row, targetType, visits, relationship.fieldName)
        val order = pathOrder(relationship, steps, path)
        val grouped = Query(
            "$path.nid${order?.selected.orEmpty()}",
            rows.reached(PostgresRowSources.Anchor.row(parent), steps, path, extra = order?.columns.orEmpty()),
            tail = listOf("GROUP BY $path.nid"),
        ).text()
        val source = OnceReached(grouped, once, graph.nodes, row)
        val sort = order?.by(once) ?: "$row.nid"
        return if (relationship.isCollection) {
            "COALESCE(${Query(arrayOf(value, sort), source).nested()}, $emptyArray)"
        } else {
            Query(value, source, tail = listOf(first(sort))).nested()
        }
    }

    /**
     * The order of a path that one of its hops names, as Cypher reads it: each node reached keeps the
     * value of the first node of that hop that leads to it, the lowest or, read highest first, the
     * highest. The property's type is not known here, since the node the hop reaches is mapped to no
     * class, so it is read twice: as a number where it is stored as one, and as text. As the graph
     * engines order them, every text comes before every number, lowest first; and a node of the hop
     * that has no value comes last whichever way the path is read.
     */
    private fun pathOrder(relationship: RelationshipModel, steps: List<Step>, path: String): PathOrder? {
        val i = relationship.orderedHop ?: return null
        val hop = relationship.hops[i]
        val node = rows.stepNode(steps, i, path)
        val key = requireNotNull(hop.orderBy)
        return PathOrder(
            listOf(
                "order_number" to PostgresSql.number(node, key),
                "order_text" to "CASE WHEN jsonb_typeof(${PostgresSql.value(node, key)}) = 'string' THEN ${property(node, key)} END",
            ),
            hop.ascending,
            path,
        )
    }

    /**
     * The order of a path, read from the [columns] of each way to a node and kept for the node by
     * [selected]: `order_kind` is 0 for a text and 1 for a number, and null where the node of the hop
     * has no value; `order_number` is the value of a number, and `order_text` of a text.
     */
    private class PathOrder(val columns: List<Pair<String, String>>, private val ascending: Boolean, private val path: String) {
        private val keep = if (ascending) "MIN" else "MAX"
        private val direction = if (ascending) "ASC" else "DESC"

        /** What the ways grouped by the node reached keep of the order. */
        val selected: String
            get() = listOf(
                ", $keep(CASE WHEN $path.order_number IS NOT NULL THEN 1 WHEN $path.order_text IS NOT NULL THEN 0 END) AS order_kind",
                ", $keep($path.order_number) AS order_number",
                ", $keep($path.order_text) AS order_text",
            ).joinToString(" ")

        /** The order of the row [row]: the nodes with no value last, texts before numbers, then the value, with the node's id last. */
        fun by(row: String): String = listOf(
            "$row.order_kind $direction NULLS LAST",
            "CASE WHEN $row.order_kind = 1 THEN $row.order_number END $direction",
            "CASE WHEN $row.order_kind = 0 THEN $row.order_text END $direction",
            "$row.nid",
        ).joinToString(", ")
    }

    // ----- Scored search -----

    /**
     * The search whose candidates are the rows of [candidates], each with a `score`: one document for
     * each, `{value, score}`, best first. [predicates], the score's [threshold] and a view's required
     * relationships are applied to the candidates, not before them: a search finds its nearest and then
     * filters, as an index of the graph engines does.
     */
    override fun scored(candidates: String, predicates: List<String>, threshold: Double?, limit: Int?): GraphStatement {
        val result = "${PostgresSql.jsonObject(listOf("value" to projection(), "score" to "$root.score"))} AS result"
        val filters = requiredRelationships() + listOfNotNull(threshold?.let { "$root.score >= ${scope.bound(it)}" }) + predicates
        val best = listOfNotNull("ORDER BY $root.score DESC, $root.nid", limit?.let { "LIMIT ${scope.bound(it)}" })
        return GraphStatement(Query(result, Derived(candidates, root), filters, best).text(), bindings())
    }

    /**
     * The [nearest] nodes to [vector] by the embedding stored under [key]: every node of the root's labels,
     * and [partitionLabel] when there is one, that holds an embedding of the vector's size is measured,
     * so the answer is exact.
     */
    fun nearestTo(key: String, similarity: SimilarityFunction, partitionLabel: String?, vector: List<Float>, nearest: Int): String {
        val distance = "${PostgresSql.embedding("c", key)} ${PostgresSql.distance(similarity)} CAST(${scope.bound(vector)} AS vector)"
        val has = listOfNotNull(PostgresSql.hasLabels("c", rootLabels() + listOfNotNull(partitionLabel)), PostgresSql.holdsEmbedding("c", key, vector.size))
        return Query(
            "${PostgresSql.columns("c")}, ${PostgresSql.score(similarity, distance)} AS score",
            Table(graph.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT ${scope.bound(nearest)}"),
        ).text()
    }

    /**
     * The [nearest] nodes to [vector] through the vector index over the embeddings under [key] of the
     * nodes of [label], which are of [dimensions]: the index gives its nearest, approximately, and they
     * are then tested for the root's own labels. The search reads the embedding as the index does, and
     * names what the index holds, so the index answers it.
     */
    fun nearestThrough(label: String, key: String, dimensions: Int, similarity: SimilarityFunction, vector: List<Float>, nearest: Int): String {
        val distance = "${PostgresSql.embedding("s", key, dimensions)} ${PostgresSql.distance(similarity)} CAST(${scope.bound(vector)} AS vector($dimensions))"
        val indexed = Query(
            "s.nid, $distance AS distance", Table(graph.nodes, "s"),
            listOf(PostgresSql.hasLabel("s", label), PostgresSql.holdsEmbedding("s", key, dimensions)),
            listOf("ORDER BY distance", "LIMIT ${scope.bound(nearest)}"),
        )
        return """
            SELECT ${PostgresSql.columns("c")}, ${PostgresSql.score(similarity, "k.distance")} AS score
            FROM ${indexed.nested()} k
            JOIN ${graph.nodes} c ON c.nid = k.nid
            WHERE ${PostgresSql.hasLabels("c", rootLabels())}
        """.trimIndent()
    }

    /**
     * For each root among [ids] that holds an embedding under [key], its [topK] nearest among the
     * others, as one document a root, `{seed, near}`: see [org.drivine.backend.GraphBackend.nearestAmong].
     * Each pair is scored as [nearestTo] and [nearestThrough] score it.
     *
     * With no [dimensions], no vector index has been made, and each root measures only the other
     * roots, whose embeddings are of its size: the answer is exact. With them, each root asks the
     * index over the embeddings of [label] for its [searchK] nearest, as [nearestThrough] does, and
     * keeps those among the roots: approximate, as the index is.
     */
    fun nearestAmong(key: String, similarity: SimilarityFunction, ids: List<String>, topK: Int, threshold: Double?, label: String, dimensions: Int?, searchK: Int): GraphStatement {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        val embedded = dimensions?.let { PostgresSql.holdsEmbedding("c", key, it) } ?: "jsonb_typeof(${PostgresSql.value("c", key)}) = 'array'"
        val members = Query(
            "c.nid, ${property("c", idProperty)} AS id, c.props", Table(graph.nodes, "c"),
            listOfNotNull(PostgresSql.hasLabels("c", rootLabels()), embedded, "${property("c", idProperty)} IN (${PostgresSql.values(scope.bound(ids), SqlKind.TEXT)})"),
        )
        val nearest = if (dimensions == null) {
            val distance = "${PostgresSql.embedding("o", key)} ${PostgresSql.distance(similarity)} ${PostgresSql.embedding("s", key)}"
            Query(
                "o.nid, o.id, ${PostgresSql.score(similarity, distance)} AS score", Table(MEMBERS, "o"),
                listOf("o.nid <> s.nid", "jsonb_array_length(${PostgresSql.value("o", key)}) = jsonb_array_length(${PostgresSql.value("s", key)})"),
                listOf("ORDER BY score DESC, o.nid", "LIMIT ${scope.bound(topK)}"),
            )
        } else {
            val distance = "${PostgresSql.embedding("x", key, dimensions)} ${PostgresSql.distance(similarity)} ${PostgresSql.embedding("s", key, dimensions)}"
            val indexed = Query(
                "x.nid, $distance AS distance", Table(graph.nodes, "x"),
                listOf(PostgresSql.hasLabel("x", label), PostgresSql.holdsEmbedding("x", key, dimensions)),
                listOf("ORDER BY distance", "LIMIT ${scope.bound(searchK)}"),
            )
            Query(
                "o.nid, o.id, ${PostgresSql.score(similarity, "k.distance")} AS score", OnceReached(indexed.text(), "k", MEMBERS, "o"),
                listOf("o.nid <> s.nid"), listOf("ORDER BY score DESC, o.nid", "LIMIT ${scope.bound(topK)}"),
            )
        }
        val near = Query(
            PostgresSql.arrayOf(PostgresSql.jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
            Derived(nearest.text(), "r"), listOfNotNull(threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = PostgresSql.jsonObject(listOf("seed" to "s.id", "near" to "COALESCE(${near.nested()}, $EMPTY_ARRAY)"))
        return GraphStatement("WITH $MEMBERS AS ${members.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text(), bindings())
    }

    /**
     * The nodes whose text under [keys] meets the user's [query], each with its columns and its `score`: the candidates the
     * shared module's full-text search ranks (`org.drivine.cypher.api.FullTextSearch`), by BM25, as Oracle ranks them without
     * a text index, joined back to the node table.
     *
     * With [indexed], the trigram index of a full-text index is made (see [PostgresSchemaGrammar]), and the module narrows
     * the nodes it counts by it; the scores are the same.
     */
    fun matching(keys: List<String>, query: String, indexed: Boolean = false): String =
        Query("${PostgresSql.columns("c")}, m.score", OnceReached(ranked(SearchStore.POSTGRES, graph.name, keys, query, SearchIndex(trigram = indexed)), "m", graph.nodes, "c")).text()

    // ----- What a delete selects -----

    /**
     * The ids of the nodes a cascading delete takes with the roots that meet [predicates]: every node
     * reached through a relationship field the view declares and writes, and through those of the views
     * nested in it. A field that holds views of its own class is followed to its depth, and every level
     * of it holds what the root does. Null when the view has no such field.
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
                "$row.nid NOT IN (\n    ${reachedFromRoots(within, predicates).under()})",
                "$row.nid NOT IN (\n    ${parents.under()})",
            )
            recursion to Query("COUNT(DISTINCT $row.nid)", rows.reached(PostgresRowSources.Anchor.among(roots(predicates)), beyond, row), left).text()
        }
    }

    /** Each step of the paths a cascade follows, once for each type and direction: a node one such step below another is held by it. */
    override fun cascadeSteps(): List<Step> =
        viewModel?.let { model -> Cascade.of(model).steps }.orEmpty()

    /** The roots that meet [predicates], as a query of their ids. */
    fun roots(predicates: List<String>): String = "SELECT $root.nid\nFROM ${graph.nodes} $root\nWHERE ${selecting(predicates)}"

    /** The ids of the nodes [steps] reach from the roots that meet [predicates]. */
    private fun reachedFromRoots(steps: List<Step>, predicates: List<String>): String {
        val row = scope.row("cascaded")
        return Query("$row.nid", rows.reached(PostgresRowSources.Anchor.among(roots(predicates)), steps, row)).text()
    }

    // ----- A traversal on its own -----

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     */
    override fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        val from = scope.row("from")
        val row = scope.row("related")
        val found = Query("$from.nid", Table(graph.nodes, from), listOfNotNull("${property(from, key)} = CAST(\$$parameter AS text)", PostgresSql.hasLabels(from, labels)))
        val reached = rows.reached(PostgresRowSources.Anchor.among(found.text()), listOf(Step(type, direction, emptyList())), row)
        return "$root.nid IN ${Query("$row.nid", reached).nested()}"
    }

    companion object {
        /** As much of a field's name as a row is called by: Postgres allows 63, and a count and an underscore follow. */
        private const val MAX_NAME = 55

        /** What [nearestAmong] calls the roots among its ids, which each of them measures the others of. */
        private const val MEMBERS = "members"

        /** What follows a grouped column's name for the id of a node of the group that has its value: see [groups]. */
        private const val REPRESENTATIVE = "r"
    }
}
