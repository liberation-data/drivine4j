package org.drivine.backend.sqlite

import org.drivine.backend.sql.Cascade
import org.drivine.backend.sql.CascadeRecursion
import org.drivine.annotation.Direction
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
import org.drivine.backend.sql.TextQuery
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
    private val predicates = SqlitePredicates(scope, rows, rootAlias, root, rootModel, viewModel, depthOverrides)
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

        // A key is one column, and a text key no field declares is two: see keyColumns.
        val pairs = scope.row("pair")
        val keys = spec.keys.associateWith { key ->
            when (val from = key.source) {
                is KeySource.Property -> keyColumns(query, key.column, from.propertyName)
                is KeySource.Path -> listOf(key.column to "$pairs.${key.column}")
            }
        }
        val selected = keys.values.flatten().map { (column, expression) -> "$expression AS $column" } +
            spec.aggregates.map { "${aggregate(it)} AS ${it.column}" }
        val select = selected.joinToString(",\n    ")
        // A moment is grouped by the instant it is, and its text is the key's value: SQLite gives a column the
        // GROUP BY does not name from a row of the group.
        val momentKeys = spec.keys.filter { (it.source as? KeySource.Property)?.let { p -> isMoment(query, p.propertyName) } == true }.map { it.column }.toSet()
        val grouping = "GROUP BY " + keys.values.flatten().joinToString(", ") { (column, expression) -> if (column in momentKeys) SqliteSql.epoch(expression) else expression }
        // A least or greatest moment is its text too (see extreme), and is compared and ordered as the instant it is.
        val moments = momentKeys + spec.aggregates.filter(::isMomentExtreme).map { it.column }

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
            // The root's own row is read only where a key or an aggregate reads a property of it.
            val readsRoot = selected.any { "$root." in it }
            val from = "(\n    ${distinct.under()}\n) $pairs" + if (readsRoot) "\nJOIN ${graph.nodes} $root ON $root.nid = $pairs.nid" else ""
            "SELECT $select\nFROM $from\n$grouping"
        }

        val groups = scope.row("g")
        val keyNodes = paths.associateWith { scope.row("${it.column}_node") }
        val entries = spec.keys.flatMap { key -> keyNodes[key]?.let { listOf(key.column to document(it)) } ?: keys.getValue(key).map { (column, _) -> column to "$groups.$column" } } +
            spec.aggregates.map { it.column to "$groups.${it.column}" }
        val joins = keyNodes.entries.joinToString("") { (key, node) -> "\nJOIN ${graph.nodes} $node ON $node.nid = $groups.${key.column}" }
        return GraphStatement(
            buildString {
                append(SqliteSql.JSON_ROWS).append("\nSELECT ").append(SqliteSql.plainObject(entries)).append(" AS result")
                append("\nFROM (\n    ").append(grouped.under()).append("\n) ").append(groups).append(joins)
                if (spec.havings.isNotEmpty()) {
                    append("\nWHERE ").append(spec.havings.joinToString("\n  AND ") {
                        val column = "$groups.${it.aggregate.column}"
                        val value = scope.bound(if (it.value is Boolean) it.value.toString() else it.value)
                        if (it.aggregate.column in moments) "${SqliteSql.epoch(column)} ${it.operator.cypherOperator} ${SqliteSql.epoch(value)}"
                        else "$column ${it.operator.cypherOperator} $value"
                    })
                }
                append("\nORDER BY ").append(query.orders.joinToString(", ") { groupOrder(query, it, groups, keyNodes, keys[it.column].orEmpty().map { (column, _) -> column }, moments) })
                page(spec.skip, spec.limit)?.let { append("\n").append(it) }
            },
            bindings(),
        )
    }

    /**
     * An aggregate over the rows of a group, or over those that meet its filter. A filtered count is a
     * sum of ones and zeros; any other filtered aggregate skips the rows that do not meet the filter,
     * as it skips a null. SQL's sum over no values is null, and a grouped read's is 0.
     */
    private fun aggregate(aggregate: Aggregate<*>): String {
        val filter = predicate(aggregate.filter)
        if (aggregate.function == GroupFunction.COUNT) {
            return if (filter == null) "COUNT(*)" else "COALESCE(SUM(CASE WHEN $filter THEN 1 ELSE 0 END), 0)"
        }
        val key = requireNotNull(aggregate.propertyName)
        // A sum and a mean read a number; the least and the greatest are of the kind the field declares.
        if (isMomentExtreme(aggregate)) return extreme(aggregate.function, key, filter)
        val kind = if (aggregate.function == GroupFunction.MIN || aggregate.function == GroupFunction.MAX) predicates.declaredKind(key) ?: SqlKind.TEXT else SqlKind.NUMBER
        val stored = property(root, key, kind)
        val value = if (filter == null) stored else "CASE WHEN $filter THEN $stored END"
        return if (aggregate.function == GroupFunction.SUM) "COALESCE(SUM($value), 0)" else "${aggregate.function.name}($value)"
    }

    /** Whether [aggregate] is the least or the greatest of a property declared as a moment: see [extreme]. */
    private fun isMomentExtreme(aggregate: Aggregate<*>): Boolean =
        (aggregate.function == GroupFunction.MIN || aggregate.function == GroupFunction.MAX) &&
            aggregate.propertyName?.let(predicates::declaredKind).let { it == SqlKind.INSTANT || it == SqlKind.LOCAL_DATE_TIME }

    /**
     * The least or the greatest moment under [key], as the text it is stored as. Its nanoseconds since
     * the epoch are written before the text at one width, a negative one after `0` and the rest after
     * `1`, so the least of the two together is the text of the least moment.
     */
    private fun extreme(function: GroupFunction, key: String, filter: String?): String {
        val text = property(root, key)
        val epoch = SqliteSql.epoch(text)
        val ordered = "CASE WHEN $epoch < 0 THEN '0' || printf('%019d', $epoch + 9223372036854775807) ELSE '1' || printf('%019d', $epoch) END || $text"
        val value = if (filter == null) ordered else "CASE WHEN $filter THEN $ordered END"
        return "substr(${function.name}($value), 21)"
    }

    /**
     * The columns a property key is grouped by, each with the expression that gives it: the key's own,
     * `k0`, and for a property no field declares, `k0n`, its value where it is stored as a number and
     * null for anything else: as text, a number would order `10` before `9`, and come back as text. A
     * field that is no text is read as the kind it declares, and is one column.
     */
    private fun keyColumns(query: GroupQuery, column: String, key: String): List<Pair<String, String>> {
        val field = query.field(key)
        val plain = key == Stamps.PROPERTY || (field != null && field.type != String::class.java)
        // A moment's value is its text, grouped by the instant it is: see groups.
        if (isMoment(query, key)) return listOf(column to property(root, key))
        if (plain) return listOf(column to property(root, key, predicates.declaredKind(key) ?: SqlKind.TEXT))
        return listOfNotNull(column to property(root, key), if (field == null) "$column${GroupQuery.NUMBER}" to SqliteSql.number(root, key) else null)
    }

    /** Whether the field that declares the root property [key] holds a moment. */
    private fun isMoment(query: GroupQuery, key: String): Boolean =
        key != Stamps.PROPERTY && query.field(key)?.type?.let(SqlKind::ofType)?.let { it == SqlKind.INSTANT || it == SqlKind.LOCAL_DATE_TIME } == true

    /**
     * One term of a grouped read's order: a column of the row [groups], or for a node key the id of its
     * node. A key no field declares is ordered as the graph engines order it, from the [columns] it is
     * grouped by: the nodes with no value where the nulls go, and a number after every text. A key or
     * an aggregate in [moments] is ordered as the instant it is.
     */
    private fun groupOrder(query: GroupQuery, order: GroupOrder, groups: String, nodes: Map<Key<*>, String>, columns: List<String>, moments: Set<String>): String {
        if (columns.size > 1) {
            val key = "$groups.${order.column.column}"
            val number = "$key${GroupQuery.NUMBER}"
            val ascending = order.direction == OrderDirection.ASC
            val nullsFirst = order.nulls?.let { it == NullPlacement.FIRST } ?: !ascending
            val absent = "CASE WHEN $key IS NULL THEN ${if (nullsFirst) 0 else 1} ELSE ${if (nullsFirst) 1 else 0} END"
            return listOf(absent, "$number ${if (ascending) "ASC NULLS FIRST" else "DESC NULLS LAST"}", "$key ${SqliteSql.direction(ascending)}").joinToString(", ")
        }
        val node = nodes[order.column]
        val term = if (node == null) {
            // A moment orders as the instant it is.
            "$groups.${order.column.column}".let { if (order.column.column in moments) SqliteSql.epoch(it) else it }
        } else {
            val target = query.target((order.column as Key<*>).source as KeySource.Path)
            val id = requireNotNull(target.nodeIdProperty)
            property(node, id, SqlKind.ofType(target.fields.firstOrNull { it.propertyName == id }?.type))
        }
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> "NULLS FIRST"
            NullPlacement.LAST -> "NULLS LAST"
            null -> null
        }
        return "$term ${SqliteSql.direction(order.direction == OrderDirection.ASC, nulls)}"
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

    override fun property(row: String, key: String, kind: SqlKind): String = SqliteSql.property(row, key, kind)

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
            rows.reached(SqliteRowSources.Anchor.row(parent), steps, path, extra = order?.columns.orEmpty()),
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
                "order_number" to SqliteSql.number(node, key),
                "order_text" to "CASE WHEN ${SqliteSql.type(node, key)} = 'text' THEN ${property(node, key)} END",
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
            "CASE WHEN $row.order_kind = 1 THEN $row.order_number END ${SqliteSql.direction(ascending)}",
            "CASE WHEN $row.order_kind = 0 THEN $row.order_text END ${SqliteSql.direction(ascending)}",
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
     * The nodes whose text under [keys] meets [query], scored by how often its terms occur and then
     * against the best of them, so the best scores one. Each term is a regular expression that matches
     * it as a whole word, counted by the function [SqliteRegexpCountFunction] registers as Postgres
     * counts it, so the two score alike. There is no full-text index yet: every node of the root's
     * labels is read.
     */
    fun matching(keys: List<String>, query: TextQuery): String {
        val text = SqliteSql.searchedText("c", keys)
        fun count(pattern: String) = "${SqliteFunctions.REGEXP_COUNT}(s.text, ${SqliteSql.text(scope.bound(pattern))})"
        val texts = Query("${SqliteSql.columns("c")}, $text AS text", Table(graph.nodes, "c"), listOfNotNull(SqliteSql.hasLabels("c", rootLabels())))
        val counted = Query(
            "${SqliteSql.columns("s")}, ${query.scored.joinToString(" + ") { count(it) }} AS score",
            Derived(texts.text(), "s"),
            query.required.map { "${count(it)} > 0" } + query.excluded.map { "${count(it)} = 0" },
        )
        return Query(
            "${SqliteSql.columns("h")}, CAST(h.score AS REAL) / MAX(h.score) OVER () AS score",
            Derived(counted.text(), "h"), listOf("h.score > 0"),
        ).text()
    }

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

        /** What [nearestAmong] calls every pair of its roots, scored and numbered within the first of the pair. */
        private const val RANKED = "ranked"
    }
}
