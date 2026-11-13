package org.drivine.backend.sqlite

import org.drivine.cypher.store.ValueLayout
import org.drivine.query.dsl.HavingCondition
import org.drivine.query.dsl.GroupColumn
import org.drivine.connection.CypherCompile
import org.drivine.backend.sql.Fragments
import org.drivine.backend.sql.Cascade
import org.drivine.backend.sql.CascadeRecursion
import org.drivine.annotation.Direction
import org.drivine.cypher.api.SearchIndex
import org.drivine.cypher.api.SearchStore
import org.drivine.backend.GraphStatement
import org.drivine.backend.sqlite.SqliteSql.document
import org.drivine.backend.sqlite.SqliteSql.property
import org.drivine.backend.sql.CypherShorthand
import org.drivine.backend.sql.Derived
import org.drivine.backend.sql.OnceReached
import org.drivine.backend.sql.Paired
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
import org.drivine.query.dsl.GroupOrder
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
 * Writes one statement that reads a graph kept by SQLite: a load, a count, or the predicate of a
 * delete. One instance writes one statement, and holds the values its parameters take, so it is
 * stateful: [hasId], [relatedTo] and [after] say something about the statement, and are called
 * before [load], [count] or [selecting] writes it.
 *
 * A load returns one JSON document for each root, shaped as the Cypher load's map is: a fragment is
 * its properties with its `labels`; a view is its root and each relationship field, a collection as
 * an array and a single one as an object or null. A relationship field is a subquery over the nodes
 * [SqliteRowSources] reaches from the root's row; the predicates of the query DSL are written by
 * [SqlitePredicates], and a predicate written by hand is read by [CypherShorthand].
 *
 * A row is called what the model calls it, the root by its field and a related node by the field that
 * holds it, so a statement reads beside the Cypher it stands for.
 *
 * A statement that returns documents opens with [SqliteSql.JSON_ROWS], so its text values are read
 * back as the JSON they are.
 */
internal class SqliteSelect(
    private val graph: SqliteGraph,
    graphClass: Class<*>,
    collectionSorts: List<CollectionSortSpec> = emptyList(),
    depthOverrides: Map<String, Int> = emptyMap(),
) : SqlViewSelect<StatementScope>(graphClass, collectionSorts, depthOverrides, StatementScope(MAX_NAME)) {
    private val rows = SqliteRowSources(graph, scope)
    /** The values this statement's conditions and orders compare, compiled by the module ([Fragments]). */
    private val fragments = Fragments(CypherCompile.sqliteFragments(graph.name), scope)
    private val predicates = SqlitePredicates(rootAlias, root, rootModel, viewModel, ::rootLabels, depthOverrides, fragments)
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
            page(skip, limit),
        )
        return GraphStatement("${SqliteSql.JSON_ROWS}\n" + Query(result, roots(), selected(predicates), page).text(), bindings())
    }

    /** SQLite's page, `LIMIT … OFFSET …`, with a limit of -1, none, where only a skip is given; null for neither. */
    private fun page(skip: Int?, limit: Int?): String? = when {
        skip == null && limit == null -> null
        skip == null -> "LIMIT ${scope.bound(limit)}"
        else -> "LIMIT ${limit?.let { scope.bound(it) } ?: "-1"} OFFSET ${scope.bound(skip)}"
    }

    /** The count of the roots [predicates] select, and that have the relationships the view requires. */
    override fun count(predicates: List<String>): GraphStatement =
        GraphStatement(Query("COUNT(*)", roots(), selected(predicates)).text(), bindings())

    /**
     * The grouped read [query] asks of the roots: one JSON document for each group, a key under `k0…`
     * and an aggregate under `a0…`.
     *
     * The groups are made by a query of their own, `GROUP BY` over the roots, and the statement reads
     * its rows: it filters them for a `having`, orders and pages them, and joins the node of a node key
     * back for its document.
     *
     * A property key is the property's value as the store holds it ([Fragments.value]), so values Cypher
     * tells apart are grouped apart, and a temporal value comes back as its kind. Where the groups are
     * ordered by a property key, the grouped query also gives a node of the group that has its value
     * (`k0r`); a least or greatest value is a node of the group that has it (`a0r`), the first in
     * Cypher's order. The statement joins each back, and the module reads, orders and compares the value
     * there as Cypher does.
     *
     * A node key is a path, and a path gives a root once for each way to a key node. The roots are then
     * read as the distinct pairs of a root and its key nodes, by their ids, and each root's row is
     * joined to its pairs again where a key, an aggregate or a predicate reads it. SQLite has no lateral
     * join, so each path is walked from every node and joined to the root by the node it starts from.
     */
    override fun groups(query: GroupQuery): GraphStatement {
        val spec = query.spec
        val paths = spec.keys.filter { it.source is KeySource.Path }
        val nodes = paths.associateWith { scope.row(it.column) }
        val filtered = listOfNotNull(predicate(spec.conditions))

        // The columns read through a node of the group that has the value: see the KDoc.
        val held = (query.orders.map { it.column } + spec.aggregates).filter { column ->
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
        val selected = keys.map { (key, expression) -> "$expression AS ${key.column}" } +
            spec.aggregates.map { "${aggregate(it)} AS ${it.column}${if (it in held) REPRESENTATIVE else ""}" } +
            held.filterIsInstance<Key<*>>().map { "MIN($root.nid) AS ${it.column}$REPRESENTATIVE" }
        val select = selected.joinToString(",\n    ")
        val grouping = "GROUP BY " + keys.values.joinToString(", ")

        val grouped = if (paths.isEmpty()) {
            // A grouped read's source is a fragment, which requires no relationship.
            Query(select, roots(), listOfNotNull(SqliteSql.hasLabels(root, rootLabels())) + filtered, listOf(grouping)).text()
        } else {
            // Each path is walked from every node, and joined to the root it starts from.
            val walked = paths.joinToString("") { key ->
                val path = key.source as KeySource.Path
                val steps = path.hops.mapIndexed { i, hop ->
                    Step(hop.type, hop.direction, if (i == path.hops.lastIndex) query.target(path).labels else listOfNotNull(hop.label.takeIf { it.isNotEmpty() }))
                }
                val reached = rows.reached(null, steps, nodes.getValue(key))
                "\nJOIN ${reached.reading(emptyList())} ON ${reached.alias}.src = $root.nid"
            }
            val where = listOfNotNull(SqliteSql.hasLabels(root, rootLabels())) + filtered
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
        val holders = held.associateWith { scope.row("${it.column}_holder") }
        // A value is JSON's text, which json() gives the document as the value it is.
        val entries = spec.keys.map { key -> key.column to (keyNodes[key]?.let(::document) ?: keyEntry(key, "$groups.${key.column}")) } +
            spec.aggregates.map { aggregate ->
                aggregate.column to (holders[aggregate]?.let { "json(${fragments.value(it, Fragments.property(requireNotNull(aggregate.propertyName)))})" } ?: "$groups.${aggregate.column}")
            }
        val joins = keyNodes.entries.joinToString("") { (key, node) -> "\nJOIN ${graph.nodes} $node ON $node.nid = $groups.${key.column}" } +
            holders.entries.joinToString("") { (column, holder) -> "\nLEFT JOIN ${graph.nodes} $holder ON $holder.nid = $groups.${column.column}$REPRESENTATIVE" }
        return GraphStatement(
            buildString {
                append(SqliteSql.JSON_ROWS).append("\nSELECT ").append(SqliteSql.plainObject(entries)).append(" AS result")
                append("\nFROM (\n    ").append(grouped.under()).append("\n) ").append(groups).append(joins)
                if (spec.havings.isNotEmpty()) {
                    append("\nWHERE ").append(spec.havings.joinToString("\n  AND ") { having(it, groups, holders) })
                }
                append("\nORDER BY ").append(query.orders.joinToString(", ") { groupOrder(query, it, groups, keyNodes, holders) })
                page(spec.skip, spec.limit)?.let { append("\n").append(it) }
            },
            bindings(),
        )
    }

    /** A property key's value: as the store holds it, which tells apart what Cypher does. The stamp is a column of its own, a text. */
    private fun keyValue(key: String): String =
        if (key == Stamps.PROPERTY) property(root, key) else fragments.value(root, Fragments.property(key))

    /** A property key's entry in a group's document: its JSON value, and the stamp's text. */
    private fun keyEntry(key: Key<*>, column: String): String =
        if ((key.source as KeySource.Property).propertyName == Stamps.PROPERTY) column else "json($column)"

    /**
     * An aggregate over the rows of a group, or over those that meet its filter. A filtered count is a
     * sum of ones and zeros; any other filtered aggregate skips the rows that do not meet the filter,
     * as it skips a null. SQL's sum over no values is null, and a grouped read's is 0. A least or
     * greatest value is the id of the group's first node by the property in Cypher's order, of those
     * that have it and meet the filter; null where none does.
     */
    private fun aggregate(aggregate: Aggregate<*>): String {
        val filter = predicate(aggregate.filter)
        if (aggregate.function == GroupFunction.COUNT) {
            return if (filter == null) "COUNT(*)" else "COALESCE(SUM(CASE WHEN $filter THEN 1 ELSE 0 END), 0)"
        }
        val key = requireNotNull(aggregate.propertyName)
        if (aggregate.function == GroupFunction.MIN || aggregate.function == GroupFunction.MAX) {
            val present = fragments.condition(root, rootLabels(), emptyMap()) { "${Fragments.property(key)} IS NOT NULL" }
            val kept = listOfNotNull(present, filter).joinToString(" AND ")
            val order = fragments.order(root, aggregate.function == GroupFunction.MAX) { Fragments.property(key) }.joinToString(", ")
            return "(json_group_array($root.nid ORDER BY $order) FILTER (WHERE $kept)) ->> '$[0]'"
        }
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
            NullPlacement.FIRST -> "NULLS FIRST"
            NullPlacement.LAST -> "NULLS LAST"
            null -> null
        }
        return "$groups.${order.column.column} ${SqliteSql.direction(!descending, nulls)}"
    }

    /** What a load or a count asks of a root: its labels, [predicates], and the relationships the view requires. */
    private fun selected(predicates: List<String>): List<String> =
        listOfNotNull(SqliteSql.hasLabels(root, rootLabels())) + predicates + requiredRelationships()

    /** The nodes the statement's roots are among. */
    private fun roots(): RowSource = rows.nodes(root)

    /**
     * The predicate that selects the roots of this statement from the node table, when it is aliased
     * as [root]: those with the labels that [predicates] select. A delete selects them so, as the
     * Cypher delete does; a load and a count also ask for the relationships the view requires.
     */
    override fun selecting(predicates: List<String>): String =
        (listOfNotNull(SqliteSql.hasLabels(root, rootLabels())) + predicates).joinToString("\n  AND ")

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
     * The query that counts the node whose `nid` is its one parameter, `?`, if it is a root that meets
     * [conditions]: 1, or 0. Null when there are no conditions to meet. The node is read from the node
     * table, as a delete reads the roots it selects. For the write function, which has found the node,
     * and runs the query where the statement's values are not to hand: they are written into it as
     * literals (see [SqliteSql.inline]).
     */
    override fun meeting(conditions: List<WhereCondition>): String? {
        val predicate = predicate(conditions) ?: return null
        val query = "SELECT COUNT(*)\nFROM ${graph.nodes} $root\nWHERE $root.nid = \$$NID\n  AND ${selecting(listOf(predicate))}"
        val values = bindings()
        val names = SqlParameters.positional(query).names
        return SqlParameters.positional(query) { if (names[it] == NID) "?" else SqliteSql.inline(values.getValue(names[it])) }.statement
    }

    /**
     * The predicate [conditions] of the query DSL make on the root; null when there are none. Whether it is
     * [indexed] changes nothing: a partial index over the nodes of a label is the planner's to choose.
     */
    override fun predicate(conditions: List<WhereCondition>, indexed: Boolean): String? = predicates.predicate(conditions)

    /** A collection sorted by a property of its nodes, as Cypher orders it, with the relationship's id to break a tie. */
    override fun sorted(row: String, targetType: Class<*>, sort: CollectionSortSpec): String =
        (fragments.ordered(row, Fragments.property(sort.propertyName), !sort.ascending, null) + "$row.eid").joinToString(", ")

    /** The predicate that selects the rows after a keyset cursor. See [SqlitePredicates.after]. */
    override fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String = predicates.after(orders, values, collectionSortCount)

    // ----- Projection -----

    override val emptyArray: String = SqliteSql.EMPTY_ARRAY

    override fun document(row: String): String = SqliteSql.document(row)

    override fun jsonObject(entries: List<Pair<String, String>>): String = SqliteSql.jsonObject(entries)

    override fun arrayOf(value: String, order: String): String = SqliteSql.arrayOf(value, order)

    override fun withTarget(row: String, field: String, target: String): String = "json_set($row.eprops, ${SqliteSql.path(field)}, json($target))"

    /** SQLite has no `FETCH FIRST`. */
    override fun first(order: String): String = "ORDER BY $order LIMIT 1"

    override fun direction(ascending: Boolean): String = SqliteSql.direction(ascending)

    override fun property(row: String, key: String, kind: SqlKind): String = SqliteSql.property(row, ValueLayout.escape(key), kind)

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
        val reached = rows.reached(SqliteRowSources.Anchor.row(parent), steps, path, extra = order?.columns.orEmpty())
        val grouped = Query(
            "$path.nid${order?.selected.orEmpty()}",
            order?.from(reached) ?: reached,
            tail = listOf("GROUP BY $path.nid"),
        ).text()
        val source = OnceReached(grouped, once, graph.nodes, row, order?.joined(once).orEmpty())
        val sort = order?.by(row) ?: "$row.nid"
        return if (relationship.isCollection) {
            "COALESCE(${Query(arrayOf(value, sort), source).nested()}, $emptyArray)"
        } else {
            Query(value, source, tail = listOf(first(sort))).nested()
        }
    }

    /**
     * The order of a path that one of its hops names, as Cypher reads it: each node reached keeps the
     * first node of that hop that leads to it, by the hop's property as Cypher orders it, the lowest or,
     * read highest first, the highest; a node of the hop that has no value is kept only where none has
     * one, and comes last whichever way the path is read. The module orders the property, over the hop's
     * node joined back by its id, as the node reached is ordered by the node it keeps.
     */
    private fun pathOrder(relationship: RelationshipModel, steps: List<Step>, path: String): PathOrder? {
        val i = relationship.orderedHop ?: return null
        val hop = relationship.hops[i]
        val key = Fragments.property(requireNotNull(hop.orderBy))
        val step = scope.row("step")
        val kept = scope.row("kept")
        fun terms(row: String) = fragments.ordered(row, key, !hop.ascending, NullPlacement.LAST).joinToString(", ")
        return PathOrder(
            listOf(ORDER_NODE to "${rows.stepNode(steps, i, path)}.nid"),
            path, graph.nodes, step, kept, terms(step), terms(kept),
        )
    }

    /**
     * The order of a path, by the hop's node each way to a node reached keeps: [columns] give each way's
     * node of the hop, a row [step] of [nodes] reads it while the ways are grouped by the node reached,
     * which keeps the first by [stepTerms], and a row [kept] reads that one for the order [keptTerms].
     */
    private class PathOrder(
        val columns: List<Pair<String, String>>,
        private val path: String,
        private val nodes: String,
        private val step: String,
        private val kept: String,
        private val stepTerms: String,
        private val keptTerms: String,
    ) {
        /** The ways to the nodes reached, each joined to its node of the hop. */
        fun from(reached: RowSource): RowSource = object : RowSource {
            override val alias: String get() = reached.alias
            override fun reading(readers: List<String>): String = "${reached.reading(readers)}\nJOIN $nodes $step ON $step.nid = $path.$ORDER_NODE"
        }

        /** What the ways grouped by the node reached keep of the order: the first node of the hop. */
        val selected: String get() = ", json_group_array($step.nid ORDER BY $stepTerms) ->> '$[0]' AS $ORDER_NODE"

        /** The join of the node of the hop that the grouped row [once] keeps. */
        fun joined(once: String): String = "\nJOIN $nodes $kept ON $kept.nid = $once.$ORDER_NODE"

        /** The order of the node reached, the row [row]: by the node of the hop it keeps, then by its own id. */
        fun by(row: String): String = "$keptTerms, $row.nid"
    }

    // ----- Scored search -----

    /**
     * The search whose candidates are the rows of [candidates], each with a `score`: one document for
     * each, `{value, score}`, best first. [predicates], the score's [threshold] and a view's required
     * relationships are applied to the candidates, not before them: a search finds its nearest and then
     * filters, as an index of the graph engines does.
     */
    override fun scored(candidates: String, predicates: List<String>, threshold: Double?, limit: Int?): GraphStatement {
        val result = "${SqliteSql.jsonObject(listOf("value" to projection(), "score" to "$root.score"))} AS result"
        val filters = requiredRelationships() + listOfNotNull(threshold?.let { "$root.score >= ${scope.bound(it)}" }) + predicates
        val best = listOfNotNull("ORDER BY $root.score DESC, $root.nid", limit?.let { "LIMIT ${scope.bound(it)}" })
        return GraphStatement("${SqliteSql.JSON_ROWS}\n" + Query(result, Derived(candidates, root), filters, best).text(), bindings())
    }

    /**
     * The [nearest] nodes to [vector] by the embedding stored under [key]: every node of the root's labels,
     * and [partitionLabel] when there is one, that holds an embedding of the vector's size is measured,
     * so the answer is exact. SQLite has no vector index: the embeddings are read from the JSON arrays
     * they are stored as, and measured by [SqliteSql.score].
     */
    fun nearestTo(key: String, similarity: SimilarityFunction, partitionLabel: String?, vector: List<Float>, nearest: Int): String {
        val has = listOfNotNull(SqliteSql.hasLabels("c", rootLabels() + listOfNotNull(partitionLabel)), SqliteSql.holdsEmbedding("c", key, vector.size))
        return Query(
            "${SqliteSql.columns("c")}, ${SqliteSql.score("c", key, similarity, scope.bound(vector))} AS score",
            Table(graph.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT ${scope.bound(nearest)}"),
        ).text()
    }

    /**
     * For each root among [ids] that holds an embedding under [key], its [topK] nearest among the
     * others, as one document a root, `{seed, near}`: see [org.drivine.backend.GraphBackend.nearestAmong].
     * Each pair is scored by [SqliteSql.score], as [nearestTo] scores a node against a vector, and only
     * the roots are measured, so the answer is exact. A pair whose embeddings differ in size has no
     * score, and is left out.
     *
     * SQLite has no `LATERAL`, so each root's nearest are not a query of their own: every pair is
     * scored once, numbered within its root best first, and a root keeps the first [topK].
     */
    fun nearestAmong(key: String, similarity: SimilarityFunction, ids: List<String>, topK: Int, threshold: Double?): GraphStatement {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        val members = Query(
            "c.nid, ${property("c", idProperty)} AS id, c.props", Table(graph.nodes, "c"),
            listOfNotNull(
                SqliteSql.hasLabels("c", rootLabels()), "${SqliteSql.type("c", key)} = 'array'",
                "${property("c", idProperty)} IN (${SqliteSql.values(scope.bound(ids), SqlKind.TEXT)})",
            ),
        )
        val scored = Query(
            "s.nid AS seed, o.nid, o.id, ${SqliteSql.score("o", key, similarity, SqliteSql.value("s", key))} AS score",
            Paired(MEMBERS, "s", "o", "o.nid <> s.nid"),
        )
        val ranked = Query(
            "p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place",
            Derived(scored.text(), "p"), listOf("p.score IS NOT NULL"),
        )
        val near = Query(
            SqliteSql.arrayOf(SqliteSql.plainObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
            Table(RANKED, "r"),
            listOfNotNull("r.seed = s.nid", "r.place <= ${scope.bound(topK)}", threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = SqliteSql.plainObject(listOf("seed" to "s.id", "near" to "json(${near.nested()})"))
        return GraphStatement(
            "${SqliteSql.JSON_ROWS}\nWITH $MEMBERS AS ${members.nested()},\n$RANKED AS ${ranked.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text(),
            bindings(),
        )
    }

    /**
     * The nodes whose text under [keys] meets the user's [query], each with its columns and its `score`: the candidates the
     * shared module's full-text search ranks (`org.drivine.cypher.api.FullTextSearch`), by BM25, as Postgres ranks them, joined
     * back to the node table. Each term is counted by the function [SqliteRegexpCountFunction] registers. There is no
     * full-text index yet: every node of the root's labels is read.
     */
    fun matching(keys: List<String>, query: String): String =
        Query("${SqliteSql.columns("c")}, m.score", OnceReached(ranked(SearchStore.SQLITE, graph.name, keys, query, SearchIndex()), "m", graph.nodes, "c")).text()

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
            recursion to Query("COUNT(DISTINCT $row.nid)", rows.reached(SqliteRowSources.Anchor.among(roots(predicates)), beyond, row), left).text()
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
        return Query("$row.nid", rows.reached(SqliteRowSources.Anchor.among(roots(predicates)), steps, row)).text()
    }

    // ----- A traversal on its own -----

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     */
    override fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        val from = scope.row("from")
        val row = scope.row("related")
        val found = Query("$from.nid", Table(graph.nodes, from), listOfNotNull("${property(from, key)} = CAST(\$$parameter AS text)", SqliteSql.hasLabels(from, labels)))
        val reached = rows.reached(SqliteRowSources.Anchor.among(found.text()), listOf(Step(type, direction, emptyList())), row)
        return "$root.nid IN ${Query("$row.nid", reached).nested()}"
    }

    companion object {
        /** As much of a field's name as a row is called by: as on Postgres, a name of 55, with room for a count and an underscore. */
        private const val MAX_NAME = 55

        /** The parameter the query [meeting] writes is given the `nid` of the node it asks about as. */
        private const val NID = "_nid"

        /** What [nearestAmong] calls the roots among its ids, which each of them measures the others of. */
        private const val MEMBERS = "members"

        /** The column of a path's ways that holds a way's node of the hop the path is ordered by. */
        private const val ORDER_NODE = "order_node"

        /** What follows a grouped column's name for the id of a node of the group that has its value: see [groups]. */
        private const val REPRESENTATIVE = "r"

        /** What [nearestAmong] calls every pair of its roots, scored and numbered within the first of the pair. */
        private const val RANKED = "ranked"
    }
}
