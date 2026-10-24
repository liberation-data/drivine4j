package org.drivine.backend.postgres

import org.drivine.backend.sql.Cascade
import org.drivine.backend.sql.CascadeRecursion
import org.drivine.annotation.Direction
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
    private val predicates = PostgresPredicates(scope, rows, rootAlias, root, rootModel, viewModel)
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
        val grouping = "GROUP BY " + keys.values.flatten().joinToString(", ") { it.second }

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
                append("SELECT ").append(PostgresSql.jsonObject(entries)).append(" AS result")
                append("\nFROM (\n    ").append(grouped.under()).append("\n) ").append(groups).append(joins)
                if (spec.havings.isNotEmpty()) {
                    append("\nWHERE ").append(spec.havings.joinToString("\n  AND ") { "$groups.${it.aggregate.column} ${it.operator.cypherOperator} ${scope.bound(if (it.value is Boolean) it.value.toString() else it.value)}" })
                }
                append("\nORDER BY ").append(query.orders.joinToString(", ") { groupOrder(query, it, groups, keyNodes, keys[it.column].orEmpty().map { (column, _) -> column }) })
                spec.skip?.let { append("\nOFFSET ${scope.bound(it)} ROWS") }
                spec.limit?.let { append("\nFETCH NEXT ${scope.bound(it)} ROWS ONLY") }
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
        val kind = if (aggregate.function == GroupFunction.MIN || aggregate.function == GroupFunction.MAX) predicates.declaredKind(key) ?: SqlKind.TEXT else SqlKind.NUMBER
        val stored = property(root, key, kind)
        val value = if (filter == null) stored else "CASE WHEN $filter THEN $stored END"
        return if (aggregate.function == GroupFunction.SUM) "COALESCE(SUM($value), 0)" else "${aggregate.function.name}($value)"
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
        if (plain) return listOf(column to property(root, key, predicates.declaredKind(key) ?: SqlKind.TEXT))
        return listOfNotNull(column to property(root, key), if (field == null) "$column${GroupQuery.NUMBER}" to PostgresSql.number(root, key) else null)
    }

    /**
     * One term of a grouped read's order: a column of the row [groups], or for a node key the id of its
     * node. A key no field declares is ordered as the graph engines order it, from the [columns] it is
     * grouped by: the nodes with no value where the nulls go, and a number after every text.
     */
    private fun groupOrder(query: GroupQuery, order: GroupOrder, groups: String, nodes: Map<Key<*>, String>, columns: List<String>): String {
        if (columns.size > 1) {
            val key = "$groups.${order.column.column}"
            val number = "$key${GroupQuery.NUMBER}"
            val ascending = order.direction == OrderDirection.ASC
            val nullsFirst = order.nulls?.let { it == NullPlacement.FIRST } ?: !ascending
            val absent = "CASE WHEN $key IS NULL THEN ${if (nullsFirst) 0 else 1} ELSE ${if (nullsFirst) 1 else 0} END"
            return listOf(absent, "$number ${if (ascending) "ASC NULLS FIRST" else "DESC NULLS LAST"}", "$key ${order.direction.name}").joinToString(", ")
        }
        val node = nodes[order.column]
        val term = if (node == null) {
            "$groups.${order.column.column}"
        } else {
            val target = query.target((order.column as Key<*>).source as KeySource.Path)
            val id = requireNotNull(target.nodeIdProperty)
            property(node, id, SqlKind.ofType(target.fields.firstOrNull { it.propertyName == id }?.type))
        }
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> " NULLS FIRST"
            NullPlacement.LAST -> " NULLS LAST"
            null -> ""
        }
        return "$term ${order.direction.name}$nulls"
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
     * The nodes whose text under [keys] meets [query], scored by how often its terms occur and then
     * against the best of them, so the best scores one. Each term is a regular expression that matches
     * it as a whole word, counted as Oracle counts it without a text index, so the two score alike.
     * With [indexed], the nodes are first narrowed to those whose text holds every word of some scoring
     * term, which the trigram index of a full-text index answers (see [PostgresSchemaGrammar]): a node
     * that does not cannot match the term, and scores nothing, so the answer is the same.
     */
    fun matching(keys: List<String>, query: TextQuery, indexed: Boolean = false): String {
        val text = PostgresSql.searchedText("c", keys)
        fun count(pattern: String) = "(SELECT count(*) FROM regexp_matches(s.text, ${PostgresSql.text(scope.bound(pattern))}, 'g'))"
        // A node that matches a term holds each of its words: what the trigram index answers is whether it holds them.
        val narrowed = if (indexed) {
            listOf(query.scoredWords.joinToString(" OR ", "(", ")") { words -> words.joinToString(" AND ", "(", ")") { "$text LIKE ${PostgresSql.text(scope.bound("%${likeText(it)}%"))}" } })
        } else {
            emptyList()
        }
        val texts = Query("${PostgresSql.columns("c")}, $text AS text", Table(graph.nodes, "c"), listOfNotNull(PostgresSql.hasLabels("c", rootLabels())) + narrowed)
        val counted = Query(
            "${PostgresSql.columns("s")}, ${query.scored.joinToString(" + ") { count(it) }} AS score",
            Derived(texts.text(), "s"),
            query.required.map { "${count(it)} > 0" } + query.excluded.map { "${count(it)} = 0" },
        )
        return Query(
            "${PostgresSql.columns("h")}, CAST(h.score AS double precision) / MAX(h.score) OVER () AS score",
            Derived(counted.text(), "h"), listOf("h.score > 0"),
        ).text()
    }

    /** [word] as text a `LIKE` pattern matches as it is: its own `%`, `_` and backslash taken as themselves. */
    private fun likeText(word: String): String = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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
    }
}
