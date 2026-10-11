package org.drivine.backend.oracle

import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.backend.GraphStatement
import org.drivine.backend.oracle.OracleSql.EMPTY_ARRAY
import org.drivine.backend.oracle.OracleSql.document
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.backend.oracle.OracleSql.literal
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.backend.oracle.OracleSql.property
import org.drivine.model.AggregateFieldModel
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphTypeLabels
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.OrderSpec
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
 */
internal class OracleSelect(
    private val graph: OracleGraph,
    private val graphClass: Class<*>,
    private val collectionSorts: List<CollectionSortSpec> = emptyList(),
    private val depthOverrides: Map<String, Int> = emptyMap(),
) {
    private val viewModel: GraphViewModel? =
        if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null

    private val rootModel: FragmentModel = FragmentModel.from(viewModel?.rootFragment?.fragmentType ?: graphClass)

    /** The fragment the statement's roots are: the class itself, or a view's root. */
    val rootFragment: Class<*> = viewModel?.rootFragment?.fragmentType ?: graphClass

    /** What a predicate calls the root: `n` for a fragment, the root field's name for a view. */
    val rootAlias: String = viewModel?.rootFragment?.fieldName ?: FRAGMENT_ALIAS

    /**
     * The root's row in the statement. Not the name itself: a field may be called `user` or `order`,
     * which SQL keeps for its own, and no name SQL keeps ends in an underscore.
     */
    val root: String = "${rootAlias}_"

    /** The names of the root's relationship fields; empty for a fragment. */
    val relationshipFields: Set<String> = viewModel?.relationships?.map { it.fieldName }?.toSet().orEmpty()

    private val scope = OracleStatementScope()
    private val rows = OracleRowSources(graph, scope)
    private val predicates = OraclePredicates(scope, rows, rootAlias, root, rootModel, viewModel)
    private val shorthand = CypherShorthand(rootAlias, root)

    private fun rootLabels(): List<String> {
        val labels = if (viewModel != null) GraphTypeLabels.fragmentLabels(viewModel.rootFragment.fragmentType) else rootModel.labels
        require(labels.isNotEmpty()) { "No labels defined for fragment ${rootModel.className}. @NodeFragment must specify at least one label." }
        return labels
    }

    // ----- Statements -----

    /**
     * The load of the roots that meet [predicates].
     *
     * @param orders the orders on the root, in turn
     * @param skip and [limit] the page to return, bound as parameters so the text is the same for every page
     */
    fun load(predicates: List<String>, orders: List<OrderSpec> = emptyList(), skip: Int? = null, limit: Int? = null): GraphStatement {
        val projection = if (viewModel != null) view(root, viewModel, mapOf(viewModel.className to 1), null) else document(root)
        val text = buildString {
            append("SELECT ").append(projection).append(" AS result\nFROM ").append(rows.nodes(rootLabels(), root))
            append(where(predicates + requiredRelationships()))
            if (orders.isNotEmpty()) append("\nORDER BY ").append(orders.joinToString(", ") { this@OracleSelect.predicates.orderTerms(it) })
            if (skip != null) append("\nOFFSET ").append(scope.bound(skip)).append(" ROWS")
            if (limit != null) append("\nFETCH NEXT ").append(scope.bound(limit)).append(" ROWS ONLY")
        }
        return GraphStatement(text, bindings())
    }

    /** The count of the roots [predicates] select, and that have the relationships the view requires. */
    fun count(predicates: List<String>): GraphStatement =
        GraphStatement("SELECT COUNT(*) FROM ${rows.nodes(rootLabels(), root)}${where(predicates + requiredRelationships())}", bindings())

    /**
     * The predicate that selects the roots of this statement from the node table, when it is aliased
     * as [root]: those with the labels that [predicates] select. A delete selects them so, as the
     * Cypher delete does; a load and a count also ask for the relationships the view requires.
     */
    fun selecting(predicates: List<String>): String =
        (listOfNotNull(hasLabels(root, rootLabels(), scanned = !scope.narrowed)) + predicates.map { "($it)" }).joinToString("\n  AND ")

    /** The values the statement's parameters take, as written so far. */
    fun bindings(): Map<String, Any?> = scope.bindings()

    private fun where(predicates: List<String>): String =
        if (predicates.isEmpty()) "" else "\nWHERE " + predicates.joinToString("\n  AND ") { "($it)" }

    /** A predicate the caller wrote, with each property of the root written as Cypher writes it read as that property: see [CypherShorthand]. */
    fun written(predicate: String): String = shorthand.rewrite(predicate)

    /** The root whose id is bound as [parameter]. */
    fun hasId(parameter: String): String {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        scope.narrowed = true
        return "${property(root, idProperty)} = \$$parameter"
    }

    /** The predicate [conditions] of the query DSL make on the root; null when there are none. See [OraclePredicates.predicate]. */
    fun predicate(conditions: List<WhereCondition>): String? = predicates.predicate(conditions)

    /** The predicate that selects the rows after a keyset cursor. See [OraclePredicates.after]. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String = predicates.after(orders, values, collectionSortCount)

    // ----- Projection -----

    /** The view [model] rooted at the row [alias], as one JSON object. [field] is the relationship field it was reached through. */
    private fun view(alias: String, model: GraphViewModel, visits: Map<String, Int>, field: String?): String {
        val entries = mutableListOf(model.rootFragment.fieldName to document(alias))
        model.relationships.forEach { entries += it.fieldName to relationship(alias, it, visits, field) }
        model.aggregateFields.forEach { entries += it.fieldName to aggregate(alias, it) }
        return jsonObject(entries)
    }

    /** A JSON object of [entries], each a key and the expression for its value. */
    private fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "JSON_OBJECT(\n    ", "\n    RETURNING JSON)") { (key, value) -> "${literal(key)} VALUE $value" }

    /**
     * The value of a relationship field of the row [parent]: a subquery that gives an array, or one
     * object or null.
     *
     * A view that would be entered again past its depth is cut off, as an empty list or null, so a
     * cycle of views ends.
     */
    private fun relationship(parent: String, relationship: RelationshipModel, visits: Map<String, Int>, parentField: String?): String {
        val depth = depthOverrides[relationship.fieldName] ?: relationship.maxDepth
        val nothing = if (relationship.isCollection) EMPTY_ARRAY else "NULL"
        val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
        val labels = GraphTypeLabels.labelsForType(targetType)
        require(labels.isNotEmpty()) {
            "No labels defined for relationship target ${targetType.name}. @NodeFragment or @GraphView must specify at least one label."
        }
        val targetIsView = targetType.isAnnotationPresent(GraphView::class.java)
        val sort = sortOf(relationship, parentField)

        if (relationship.isPath) return distinct(parent, relationship, rows.steps(relationship, labels), targetType, visits)
        if (relationship.isRecursive) {
            return if (depth == 0) nothing else recursive(parent, relationship, labels, visits, 1, depth, sort)
        }
        if (targetIsView && (visits[targetType.name] ?: 0) >= depth && targetType.name in visits) return nothing
        if (relationship.isCollection && !targetIsView && !relationship.isRelationshipFragment && depth > 1) {
            return distinct(parent, relationship, rows.steps(relationship, labels, depth), targetType, visits)
        }

        val row = scope.next("t")
        val target = element(row, targetType, visits, relationship.fieldName)
        val value = if (relationship.isRelationshipFragment) {
            // The relationship's own properties, with the node it leads to under the target field.
            "JSON_TRANSFORM($row.eprops, SET ${path(relationship.targetFieldName!!)} = $target)"
        } else {
            target
        }
        return gathered(parent, rows.reached(rows.steps(relationship, labels), row), row, value, relationship.isCollection, sort?.let { sorted(row, targetType, it) })
    }

    /** The node [row] as an element of a relationship field: a fragment's map, or a nested view. */
    private fun element(row: String, type: Class<*>, visits: Map<String, Int>, field: String): String =
        if (type.isAnnotationPresent(GraphView::class.java)) {
            view(row, GraphViewModel.from(type), visits + (type.name to (visits[type.name] ?: 0) + 1), field)
        } else {
            document(row)
        }

    /** [value] for each row of [source] that starts from [parent]: all of them as an array, or the first. */
    private fun gathered(parent: String, source: String, row: String, value: String, collection: Boolean, order: String?): String =
        if (collection) {
            "COALESCE((\n    SELECT JSON_ARRAYAGG($value ORDER BY ${order ?: "$row.eid"} RETURNING JSON)\n    FROM ${source.indented()}\n    WHERE $row.src = $parent.nid), $EMPTY_ARRAY)"
        } else {
            "(\n    SELECT $value\n    FROM ${source.indented()}\n    WHERE $row.src = $parent.nid\n    ORDER BY $row.eid FETCH FIRST 1 ROWS ONLY)"
        }

    /** [this], a row source, with its later lines moved in under the clause that names it. */
    private fun String.indented(): String = replace("\n", "\n    ")

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth.
     */
    private fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String {
        val path = scope.next("r")
        val row = scope.next("x")
        val value = element(row, targetType, visits, relationship.fieldName)
        val reachedIds = "SELECT $path.nid FROM ${rows.reached(steps, path)} WHERE $path.src = $parent.nid"
        val from = "${rows.nodes(emptyList(), row).indented()}\n    WHERE $row.nid IN (\n        $reachedIds)"
        return if (relationship.isCollection) {
            "COALESCE((\n    SELECT JSON_ARRAYAGG($value ORDER BY $row.nid RETURNING JSON)\n    FROM $from), $EMPTY_ARRAY)"
        } else {
            "(\n    SELECT $value\n    FROM $from\n    ORDER BY $row.nid FETCH FIRST 1 ROWS ONLY)"
        }
    }

    /**
     * A field of a view that holds views of the same class: each level holds the next, down to
     * [max] levels, where the field is empty. A sort on the field sorts every level, so the tree is
     * in one order.
     */
    private fun recursive(
        parent: String, relationship: RelationshipModel, labels: List<String>, visits: Map<String, Int>,
        depth: Int, max: Int, sort: CollectionSortSpec?,
    ): String {
        val nested = GraphViewModel.from(relationship.elementType)
        val row = scope.next("t")
        val entries = mutableListOf(nested.rootFragment.fieldName to document(row))
        nested.relationships
            .filter { !it.isRecursive && it.fieldName != relationship.fieldName }
            .forEach { entries += it.fieldName to relationship(row, it, visits, relationship.fieldName) }
        entries += relationship.fieldName to
            if (depth >= max) (if (relationship.isCollection) EMPTY_ARRAY else "NULL") else recursive(row, relationship, labels, visits, depth + 1, max, sort)
        return gathered(
            parent, rows.reached(rows.steps(relationship, labels), row), row, jsonObject(entries), relationship.isCollection,
            sort?.let { sorted(row, relationship.elementType, it) },
        )
    }

    /**
     * An aggregate field: `COUNT` counts the relationships, as Cypher's `size(…)` of the pattern does,
     * and any other function reads the named property of each related node as a number.
     */
    private fun aggregate(parent: String, aggregate: AggregateFieldModel): String {
        val row = scope.next("t")
        val source = "${rows.reached(listOf(Step(aggregate.type, aggregate.direction, emptyList())), row)} WHERE $row.src = $parent.nid"
        if (aggregate.function == AggregateFunction.COUNT) return "(SELECT COUNT(*) FROM $source)"
        val key = requireNotNull(aggregate.property) { "@Aggregate(${aggregate.function}) on '${aggregate.fieldName}' requires a property" }
        return "(SELECT ${aggregate.function.name}(${property(row, key, SqlKind.NUMBER)}) FROM $source)"
    }

    /** The sort the query gave for [relationship]: by its own name at the top, by its parent's and its own beneath. */
    private fun sortOf(relationship: RelationshipModel, parentField: String?): CollectionSortSpec? =
        collectionSorts.find {
            if (parentField == null) {
                !it.isNested() && it.relationshipPath == relationship.fieldName
            } else {
                it.isNested() && it.parentRelationship() == parentField && it.nestedRelationship() == relationship.fieldName
            }
        }

    /**
     * The order of a relationship collection by [sort]: the property read as the type of the field
     * that declares it, with the relationship's id to break a tie, so the order is the same each time.
     */
    private fun sorted(row: String, targetType: Class<*>, sort: CollectionSortSpec): String {
        val fragment = if (targetType.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(targetType).rootFragment.fragmentType else targetType
        val kind = SqlKind.ofType(runCatching { FragmentModel.from(fragment) }.getOrNull()?.fields?.firstOrNull { it.propertyName == sort.propertyName }?.type)
        return "${property(row, sort.propertyName, kind)} ${if (sort.ascending) "ASC" else "DESC"}, $row.eid"
    }

    /** A relationship field that is one object and may not be null leaves out the roots that have none. */
    private fun requiredRelationships(): List<String> =
        viewModel?.relationships.orEmpty().filter { !it.isNullable && !it.isCollection }.map { relationship ->
            val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
            rows.exists(root, rows.steps(relationship, GraphTypeLabels.labelsForType(targetType)), null)
        }

    // ----- Scored search -----

    /**
     * The search whose candidates are the rows of [candidates], each with a `score`: one document
     * for each, `{value, score}`, best first. [predicates], the score's [threshold] and a view's
     * required relationships are applied to the candidates, not before them: a search finds its
     * nearest and then filters, as an index of the graph engines does.
     */
    fun scored(candidates: String, predicates: List<String>, threshold: Double?, limit: Int?): GraphStatement {
        val projection = if (viewModel != null) view(root, viewModel, mapOf(viewModel.className to 1), null) else document(root)
        val filters = requiredRelationships() + listOfNotNull(threshold?.let { "$root.score >= ${scope.bound(it)}" }) + predicates
        val text = buildString {
            append("SELECT JSON_OBJECT('value' VALUE ").append(projection).append(", 'score' VALUE ").append(root).append(".score RETURNING JSON) AS result")
            append("\nFROM (\n").append(candidates).append("\n) ").append(root)
            append(where(filters))
            append("\nORDER BY ").append(root).append(".score DESC, ").append(root).append(".nid")
            if (limit != null) append("\nFETCH FIRST ").append(scope.bound(limit)).append(" ROWS ONLY")
        }
        return GraphStatement(text, bindings())
    }

    /** The root's nodes that also carry [also] and have [key], each with its columns and `score`, ordered and cut by [tail]. */
    private fun candidates(also: List<String>, key: String?, score: String, tail: String): String {
        val has = listOfNotNull(hasLabels("v", rootLabels() + also, scanned = true), key?.let { OracleSql.hasProperty("v", it) }).joinToString(" AND ")
        return "SELECT ${OracleSql.columns("c")}, $score AS score\n" +
            "FROM ${rows.graphTable("(v IS node WHERE $has)", OracleSql.columns("v"), "c")}\n$tail"
    }

    /**
     * The [nearest] nodes to [vector] by the embedding stored under [key]: every node of the label is
     * measured, so the answer is exact. The score is the one the graph engines give: for cosine
     * `(1 + cos) / 2`, for Euclidean `1 / (1 + d²)`, each in `[0, 1]` with higher the nearer.
     */
    fun nearestTo(key: String, similarity: SimilarityFunction, partitionLabel: String?, vector: List<Float>, nearest: Int): String {
        val stored = "TO_VECTOR(JSON_QUERY(c.props, ${path(key)} RETURNING CLOB))"
        val score = OracleSql.score(similarity, "VECTOR_DISTANCE($stored, TO_VECTOR(${scope.bound(vector)}), ${OracleSql.metric(similarity)})")
        return candidates(listOfNotNull(partitionLabel), key, score, "ORDER BY score DESC, c.nid\nFETCH FIRST ${scope.bound(nearest)} ROWS ONLY")
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
     * The nodes whose text under [keys] meets [query], scored by how often its terms occur and then
     * against the best of them, so the best scores one. Every node of the label is read.
     *
     * Each term is counted on its own. Counting them as the rows of one bound array, in a subquery
     * the statement names twice, gave wrong counts on Oracle 23.26: every position of the text.
     */
    fun matching(keys: List<String>, query: TextQuery): String {
        val text = keys.joinToString(" || ' ' || ") { "LOWER(JSON_VALUE(c.props, ${path(it)} RETURNING CLOB))" }
        val occurrences = query.scored.joinToString(" + ") { "REGEXP_COUNT(s.text, ${scope.bound(it)})" }
        val conditions = query.required.map { "REGEXP_COUNT(s.text, ${scope.bound(it)}) > 0" } + query.excluded.map { "REGEXP_COUNT(s.text, ${scope.bound(it)}) = 0" }
        val has = hasLabels("v", rootLabels(), scanned = true)
        return """
            SELECT ${OracleSql.columns("h")}, h.score / MAX(h.score) OVER () AS score
            FROM (
                SELECT ${OracleSql.columns("s")}, $occurrences AS score
                FROM (
                    SELECT ${OracleSql.columns("c")}, $text AS text
                    FROM ${rows.graphTable("(v IS node WHERE $has)", OracleSql.columns("v"), "c")}
                ) s${if (conditions.isEmpty()) "" else "\n                WHERE " + conditions.joinToString(" AND ")}
            ) h
            WHERE h.score > 0
        """.trimIndent()
    }

    /**
     * The nodes whose text under [keys] meets [query], through an Oracle Text index: [texts] is the
     * table the index is over, which holds that text for each node of the index's label. Scored by
     * Oracle Text, each property's score added, and then against the best of them. The index holds
     * what has been committed, so a search does not see what its own transaction has written.
     */
    fun matchingThrough(texts: String, keys: List<String>, query: TextQuery): String {
        val text = scope.bound(query.oracleText)
        val contains = keys.mapIndexed { i, key -> "JSON_TEXTCONTAINS(s.doc, ${path(key)}, $text, ${i + 1})" }
        return """
            SELECT ${OracleSql.columns("h")}, h.score / MAX(h.score) OVER () AS score
            FROM (
                SELECT c.nid, c.labels, c.props, c.stamp, ${OracleSql.transform("c")} AS doc, k.score
                FROM (SELECT s.nid, ${keys.indices.joinToString(" + ") { "SCORE(${it + 1})" }} AS score FROM $texts s WHERE ${contains.joinToString(" OR ")}) k
                JOIN ${graph.nodes} c ON c.nid = k.nid
                WHERE ${hasLabels("c", rootLabels())}
            ) h
            WHERE h.score > 0
        """.trimIndent()
    }

    // ----- What a delete or a traversal on its own selects -----

    /**
     * The ids of the nodes a cascading delete takes with the roots that meet [predicates]: every node
     * reached through a relationship field the view declares and writes, and through those of the views
     * nested in it. A field that holds views of its own class is followed to its depth.
     */
    fun cascaded(predicates: List<String>): String? {
        val model = viewModel ?: return null
        val paths = cascadePaths(model)
        if (paths.isEmpty()) return null
        val roots = "SELECT $root.nid FROM ${graph.nodes} $root WHERE ${selecting(predicates)}"
        return paths.joinToString("\n    UNION\n    ") { steps ->
            val row = scope.next("c")
            "SELECT $row.nid FROM ${rows.reached(steps, row)} WHERE $row.src IN ($roots)"
        }
    }

    /**
     * The paths a cascading delete of [model] follows from a root: one for each relationship field it
     * writes, and for each field of a view nested in it, while that view is within its depth.
     */
    private fun cascadePaths(model: GraphViewModel): List<List<Step>> {
        val paths = mutableListOf<List<Step>>()
        fun walk(view: GraphViewModel, path: List<Step>, visits: Map<String, Int>) {
            // A read-only field (every path is one) is not the view's to delete: other code writes what it loads.
            view.relationships.filterNot { it.readOnly }.forEach { relationship ->
                val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
                val labels = GraphTypeLabels.labelsForType(targetType)
                if (labels.isEmpty()) return@forEach
                val depth = if (relationship.isRecursive) relationship.maxDepth.coerceAtLeast(1) else 1
                val reached = path + Step(relationship.type, relationship.direction, labels, depth)
                paths += reached
                val visited = visits[targetType.name] ?: 0
                if (!relationship.isRecursive && targetType.isAnnotationPresent(GraphView::class.java) && visited < relationship.maxDepth) {
                    walk(GraphViewModel.from(targetType), reached, visits + (targetType.name to visited + 1))
                }
            }
        }
        walk(model, emptyList(), mapOf(model.className to 1))
        return paths
    }

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     */
    fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        scope.narrowed = true
        val row = scope.next("t")
        val arrow = OracleSql.arrow("e", type, direction)
        val from = listOfNotNull("${property("a", key)} = \$$parameter", hasLabels("a", labels)).joinToString(" AND ")
        return "$root.nid IN (SELECT $row.nid FROM ${rows.graphTable("(a IS node WHERE $from) $arrow (b IS node)", "b.nid", row)})"
    }

    companion object {
        /** What a predicate calls a fragment loaded on its own, as the Cypher statements do. */
        const val FRAGMENT_ALIAS = "n"
    }
}
