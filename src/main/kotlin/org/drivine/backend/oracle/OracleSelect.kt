package org.drivine.backend.oracle

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
import org.drivine.annotation.AggregateFunction
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphTypeLabels
import org.drivine.schema.SimilarityFunction
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/** One step from a node to the nodes it is related to: the relationship's type and direction, and the labels the far node carries. */
internal class Step(val type: String, val direction: Direction, val labels: List<String>, val maxDepth: Int = 1)

/**
 * Writes one statement that reads a graph kept by Oracle: a load, a count, or the predicate of a
 * delete. One instance writes one statement, and holds the values its parameters take.
 *
 * A load returns one JSON document for each root, shaped as the Cypher load's map is: a fragment is
 * its properties with its `labels`; a view is its root and each relationship field, a collection as
 * an array and a single one as an object or null.
 *
 * The root is read through `GRAPH_TABLE`, and so is every traversal. A nested `GRAPH_TABLE` cannot
 * name the row it is nested in, so each traversal returns the node it starts from as `src`, and is
 * joined to that row outside.
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

    /**
     * Whether the roots are found by something narrower than their labels: an id, or the node they are
     * related to. The labels are then tested on what is found, and not used to find it.
     */
    private var narrowed = false

    private val bindings = mutableMapOf<String, Any?>()
    private var names = 0

    /** A name no other part of the statement has. */
    private fun next(prefix: String): String = "${prefix}${names++}_"

    /** Binds [value] and gives the parameter that stands for it. */
    private fun bound(value: Any?): String {
        val name = "_p${bindings.size}"
        bindings[name] = value
        return "\$$name"
    }

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
            append("SELECT ").append(projection).append(" AS result\nFROM ").append(nodes(rootLabels(), root))
            append(where(predicates + requiredRelationships()))
            if (orders.isNotEmpty()) {
                append("\nORDER BY ").append(orders.joinToString(", ") { "${ordered(it.propertyPath.substringAfter('.'))} ${it.direction.name}" })
            }
            if (skip != null) append("\nOFFSET ").append(bound(skip)).append(" ROWS")
            if (limit != null) append("\nFETCH NEXT ").append(bound(limit)).append(" ROWS ONLY")
        }
        return GraphStatement(text, bindings)
    }

    fun count(predicates: List<String>): GraphStatement =
        GraphStatement("SELECT COUNT(*) FROM ${nodes(rootLabels(), root)}${where(predicates + requiredRelationships())}", bindings)

    /** The predicate that selects the roots of this statement from the node table, when it is aliased as [root]. */
    fun selecting(predicates: List<String>): String =
        (listOfNotNull(hasLabels(root, rootLabels(), scanned = !narrowed)) + predicates.map { "($it)" } + requiredRelationships()).joinToString("\n  AND ")

    fun bindings(): Map<String, Any?> = bindings

    private fun where(predicates: List<String>): String =
        if (predicates.isEmpty()) "" else "\nWHERE " + predicates.joinToString("\n  AND ") { "($it)" }

    /**
     * A predicate the caller wrote, with a property of the root written as Cypher writes it,
     * `issue.state` or ``issue.`metadata.source` ``, read as that property. So the plain comparisons a
     * Cypher predicate makes are the same text here; anything more is SQL, over the root's columns
     * `issue.props`, `issue.labels` and `issue.stamp`. Text inside a string literal is left as it is.
     */
    fun written(predicate: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < predicate.length) {
            if (predicate[at] == '\'') {
                var end = at + 1
                while (end < predicate.length) {
                    if (predicate[end] != '\'') end++ else if (predicate.getOrNull(end + 1) == '\'') end += 2 else break
                }
                val stop = minOf(end + 1, predicate.length)
                out.append(predicate, at, stop)
                at = stop
                continue
            }
            val match = SHORTHAND.matchAt(predicate, at)?.takeIf { at == 0 || !predicate[at - 1].let { c -> c.isLetterOrDigit() || c == '_' || c == '.' } }
            val key = match?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            if (match != null && match.groupValues[1] == rootAlias && predicate.getOrNull(match.range.last + 1) != '(') {
                // One of the row's own columns is the column; any other name is a property.
                out.append(if (key in COLUMNS) "$root.$key" else property(root, key!!))
                at = match.range.last + 1
            } else {
                out.append(predicate[at++])
            }
        }
        return out.toString()
    }

    /** The root whose id is bound as [parameter]. */
    fun hasId(parameter: String): String {
        val idProperty = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} does not have a @NodeId field" }
        narrowed = true
        return "${property(root, idProperty)} = \$$parameter"
    }

    /** A root property as it is ordered: by the type of the field that holds it, and as text when no field does. */
    private fun ordered(key: String): String =
        property(root, key, SqlKind.ofType(rootModel.fields.firstOrNull { it.propertyName == key }?.type))

    // ----- Row sources -----

    /** The nodes that carry [labels], as rows named [alias]. */
    private fun nodes(labels: List<String>, alias: String): String =
        "GRAPH_TABLE (${graph.name} MATCH (v IS node${hasLabels("v", labels, scanned = !narrowed)?.let { " WHERE $it" }.orEmpty()}) " +
            "COLUMNS (v.nid AS nid, v.labels AS labels, v.props AS props, v.stamp AS stamp)) $alias"

    /**
     * The nodes reached by [steps], as rows named [alias]: `src` is the node the path starts from.
     * A single step also gives the relationship, as `eid` and `eprops`. A step with a depth above one
     * is taken from one to that many times, and a node may then be reached more than once.
     */
    private fun reached(steps: List<Step>, alias: String): String {
        val single = steps.size == 1 && steps.single().maxDepth == 1
        val pattern = buildString {
            append("(a IS node)")
            steps.forEachIndexed { i, step ->
                val edge = "e$i"
                val body = "[$edge IS edge WHERE $edge.type = ${literal(step.type)}]"
                append(
                    when (step.direction) {
                        Direction.OUTGOING -> " -$body->"
                        Direction.INCOMING -> " <-$body-"
                        Direction.UNDIRECTED -> " -$body-"
                    }
                )
                if (step.maxDepth > 1) append("{1,${step.maxDepth}}")
                val node = if (i == steps.lastIndex) "b" else "m$i"
                append(" ($node IS node").append(hasLabels(node, step.labels)?.let { " WHERE $it" }.orEmpty()).append(")")
            }
        }
        val relationship = if (single) "e0.eid AS eid, e0.props AS eprops, " else ""
        return "GRAPH_TABLE (${graph.name} MATCH $pattern " +
            "COLUMNS (a.nid AS src, ${relationship}b.nid AS nid, b.labels AS labels, b.props AS props, b.stamp AS stamp)) $alias"
    }

    private fun steps(relationship: RelationshipModel, targetLabels: List<String>, maxDepth: Int = 1): List<Step> =
        if (relationship.isPath) {
            relationship.hops.mapIndexed { i, hop ->
                Step(hop.type, hop.direction, if (i == relationship.hops.lastIndex) targetLabels else listOfNotNull(hop.intermediateLabel))
            }
        } else {
            listOf(Step(relationship.type, relationship.direction, targetLabels, maxDepth))
        }

    // ----- Projection -----

    /** The view [model] rooted at the row [alias], as one JSON object. [field] is the relationship field it was reached through. */
    private fun view(alias: String, model: GraphViewModel, visits: Map<String, Int>, field: String?): String {
        val entries = mutableListOf(model.rootFragment.fieldName to document(alias))
        model.relationships.forEach { entries += it.fieldName to relationship(alias, it, visits, field) }
        model.aggregateFields.forEach { entries += it.fieldName to aggregate(alias, it) }
        return jsonObject(entries)
    }

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

        if (relationship.isPath) return distinct(parent, relationship, steps(relationship, labels), targetType, visits)
        if (relationship.isRecursive) {
            return if (depth == 0) nothing else recursive(parent, relationship, labels, visits, 1, depth, sort)
        }
        if (targetIsView && (visits[targetType.name] ?: 0) >= depth && targetType.name in visits) return nothing
        if (relationship.isCollection && !targetIsView && !relationship.isRelationshipFragment && depth > 1) {
            return distinct(parent, relationship, steps(relationship, labels, depth), targetType, visits)
        }

        val row = next("t")
        val target = element(row, targetType, visits, relationship.fieldName)
        val value = if (relationship.isRelationshipFragment) {
            // The relationship's own properties, with the node it leads to under the target field.
            "JSON_TRANSFORM($row.eprops, SET ${path(relationship.targetFieldName!!)} = $target)"
        } else {
            target
        }
        return gathered(parent, reached(steps(relationship, labels), row), row, value, relationship.isCollection, sort?.let { sorted(row, targetType, it) })
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
            "COALESCE((SELECT JSON_ARRAYAGG($value ORDER BY ${order ?: "$row.eid"} RETURNING JSON) FROM $source WHERE $row.src = $parent.nid), $EMPTY_ARRAY)"
        } else {
            "(SELECT $value FROM $source WHERE $row.src = $parent.nid ORDER BY $row.eid FETCH FIRST 1 ROWS ONLY)"
        }

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth.
     */
    private fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String {
        val path = next("r")
        val row = next("x")
        val value = element(row, targetType, visits, relationship.fieldName)
        val reachedIds = "SELECT $path.nid FROM ${reached(steps, path)} WHERE $path.src = $parent.nid"
        val from = "${graph.nodes} $row WHERE $row.nid IN ($reachedIds)"
        return if (relationship.isCollection) {
            "COALESCE((SELECT JSON_ARRAYAGG($value ORDER BY $row.nid RETURNING JSON) FROM $from), $EMPTY_ARRAY)"
        } else {
            "(SELECT $value FROM $from ORDER BY $row.nid FETCH FIRST 1 ROWS ONLY)"
        }
    }

    /**
     * A field of a view that holds views of the same class: each level holds the next, down to
     * [max] levels, where the field is empty.
     */
    private fun recursive(
        parent: String, relationship: RelationshipModel, labels: List<String>, visits: Map<String, Int>,
        depth: Int, max: Int, sort: CollectionSortSpec?,
    ): String {
        val nested = GraphViewModel.from(relationship.elementType)
        val row = next("t")
        val entries = mutableListOf(nested.rootFragment.fieldName to document(row))
        nested.relationships
            .filter { !it.isRecursive && it.fieldName != relationship.fieldName }
            .forEach { entries += it.fieldName to relationship(row, it, visits, relationship.fieldName) }
        entries += relationship.fieldName to
            if (depth >= max) (if (relationship.isCollection) EMPTY_ARRAY else "NULL") else recursive(row, relationship, labels, visits, depth + 1, max, null)
        return gathered(
            parent, reached(steps(relationship, labels), row), row, jsonObject(entries), relationship.isCollection,
            sort?.let { sorted(row, relationship.elementType, it) },
        )
    }

    private fun aggregate(parent: String, aggregate: AggregateFieldModel): String {
        val row = next("t")
        val source = "${reached(listOf(Step(aggregate.type, aggregate.direction, emptyList())), row)} WHERE $row.src = $parent.nid"
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

    private fun sorted(row: String, targetType: Class<*>, sort: CollectionSortSpec): String {
        val fragment = if (targetType.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(targetType).rootFragment.fragmentType else targetType
        val kind = SqlKind.ofType(runCatching { FragmentModel.from(fragment) }.getOrNull()?.fields?.firstOrNull { it.propertyName == sort.propertyName }?.type)
        return "${property(row, sort.propertyName, kind)} ${if (sort.ascending) "ASC" else "DESC"}, $row.eid"
    }

    /** A relationship field that is one object and may not be null leaves out the roots that have none. */
    private fun requiredRelationships(): List<String> =
        viewModel?.relationships.orEmpty().filter { !it.isNullable && !it.isCollection }.map { relationship ->
            val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
            exists(root, steps(relationship, GraphTypeLabels.labelsForType(targetType)), null)
        }

    private fun exists(parent: String, steps: List<Step>, row: String?, predicate: String? = null): String {
        val alias = row ?: next("t")
        return "EXISTS (SELECT 1 FROM ${reached(steps, alias)} WHERE $alias.src = $parent.nid${predicate?.let { " AND $it" }.orEmpty()})"
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
        val filters = requiredRelationships() + listOfNotNull(threshold?.let { "$root.score >= ${bound(it)}" }) + predicates
        val text = buildString {
            append("SELECT JSON_OBJECT('value' VALUE ").append(projection).append(", 'score' VALUE ").append(root).append(".score RETURNING JSON) AS result")
            append("\nFROM (\n").append(candidates).append("\n) ").append(root)
            append(where(filters))
            append("\nORDER BY ").append(root).append(".score DESC, ").append(root).append(".nid")
            if (limit != null) append("\nFETCH FIRST ").append(bound(limit)).append(" ROWS ONLY")
        }
        return GraphStatement(text, bindings)
    }

    /** The root's nodes that also carry [also] and have [key], each with its columns and `score`, ordered and cut by [tail]. */
    private fun candidates(also: List<String>, key: String?, score: String, tail: String): String {
        val has = listOfNotNull(hasLabels("v", rootLabels() + also, scanned = true), key?.let { OracleSql.hasProperty("v", it) }).joinToString(" AND ")
        return "SELECT c.nid, c.labels, c.props, c.stamp, $score AS score\n" +
            "FROM GRAPH_TABLE (${graph.name} MATCH (v IS node WHERE $has) COLUMNS (v.nid AS nid, v.labels AS labels, v.props AS props, v.stamp AS stamp)) c\n$tail"
    }

    /**
     * The [nearest] nodes to [vector] by the embedding stored under [key]: every node of the label is
     * measured, so the answer is exact. The score is the one the graph engines give: for cosine
     * `(1 + cos) / 2`, for Euclidean `1 / (1 + d²)`, each in `[0, 1]` with higher the nearer.
     */
    fun nearestTo(key: String, similarity: SimilarityFunction, partitionLabel: String?, vector: List<Float>, nearest: Int): String {
        val query = bound(vector)
        val stored = "TO_VECTOR(JSON_QUERY(c.props, ${path(key)} RETURNING CLOB))"
        val score = when (similarity) {
            SimilarityFunction.COSINE -> "(2 - VECTOR_DISTANCE($stored, TO_VECTOR($query), COSINE)) / 2"
            SimilarityFunction.EUCLIDEAN -> "1 / (1 + VECTOR_DISTANCE($stored, TO_VECTOR($query), EUCLIDEAN_SQUARED))"
        }
        return candidates(listOfNotNull(partitionLabel), key, score, "ORDER BY score DESC, c.nid\nFETCH FIRST ${bound(nearest)} ROWS ONLY")
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
        val occurrences = query.scored.joinToString(" + ") { "REGEXP_COUNT(s.text, ${bound(it)})" }
        val conditions = query.required.map { "REGEXP_COUNT(s.text, ${bound(it)}) > 0" } + query.excluded.map { "REGEXP_COUNT(s.text, ${bound(it)}) = 0" }
        val has = hasLabels("v", rootLabels(), scanned = true)
        return """
            SELECT h.nid, h.labels, h.props, h.stamp, h.score / MAX(h.score) OVER () AS score
            FROM (
                SELECT s.nid, s.labels, s.props, s.stamp, $occurrences AS score
                FROM (
                    SELECT c.nid, c.labels, c.props, c.stamp, $text AS text
                    FROM GRAPH_TABLE (${graph.name} MATCH (v IS node WHERE $has) COLUMNS (v.nid AS nid, v.labels AS labels, v.props AS props, v.stamp AS stamp)) c
                ) s${if (conditions.isEmpty()) "" else "\n                WHERE " + conditions.joinToString(" AND ")}
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
        val roots = "SELECT $root.nid FROM ${graph.nodes} $root WHERE ${selecting(predicates)}"
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
        if (paths.isEmpty()) return null
        return paths.joinToString("\n    UNION\n    ") { steps ->
            val row = next("c")
            "SELECT $row.nid FROM ${reached(steps, row)} WHERE $row.src IN ($roots)"
        }
    }

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     */
    fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        narrowed = true
        val row = next("t")
        val body = "[e IS edge WHERE e.type = ${literal(type)}]"
        val arrow = when (direction) {
            Direction.OUTGOING -> "-$body->"
            Direction.INCOMING -> "<-$body-"
            Direction.UNDIRECTED -> "-$body-"
        }
        val from = listOfNotNull("${property("a", key)} = \$$parameter", hasLabels("a", labels)).joinToString(" AND ")
        return "$root.nid IN (SELECT $row.nid FROM GRAPH_TABLE (${graph.name} MATCH (a IS node WHERE $from) $arrow (b IS node) COLUMNS (b.nid AS nid)) $row)"
    }

    // ----- Predicates from the query DSL -----

    /** The predicate [conditions] make on the root; null when there are none. */
    fun predicate(conditions: List<WhereCondition>): String? =
        all(grouped(conditions), mapOf(rootAlias to root), viewModel).ifEmpty { null }

    /**
     * Conditions on a relationship's alias are gathered into one condition on that relationship: they
     * are met together by one related node, not each by some related node.
     */
    private fun grouped(conditions: List<WhereCondition>): List<WhereCondition> {
        val relationships = viewModel?.relationships?.map { it.fieldName }?.toSet() ?: return conditions
        val kept = mutableListOf<WhereCondition>()
        val byRelationship = linkedMapOf<String, MutableList<WhereCondition>>()
        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val alias = condition.propertyPath.substringBefore('.')
                    val relationship = if (alias in relationships) alias else alias.substringBefore('_').takeIf { '_' in alias && it in relationships }
                    if (relationship != null) byRelationship.getOrPut(relationship) { mutableListOf() }.add(condition) else kept += condition
                }
                is WhereCondition.LabelCondition ->
                    kept += if (condition.alias in relationships) WhereCondition.RelationshipCondition(condition.alias, listOf(condition)) else condition
                else -> kept += condition
            }
        }
        byRelationship.forEach { (name, conditions) -> kept += WhereCondition.RelationshipCondition(name, conditions) }
        return kept
    }

    /**
     * @param rows the row each alias of the query stands for
     * @param model the view whose relationship fields [conditions] may name
     */
    private fun all(conditions: List<WhereCondition>, rows: Map<String, String>, model: GraphViewModel?): String =
        conditions.joinToString(" AND ") { one(it, rows, model) }

    private fun one(condition: WhereCondition, rows: Map<String, String>, model: GraphViewModel?): String = when (condition) {
        is WhereCondition.PropertyCondition -> {
            val alias = condition.propertyPath.substringBefore('.')
            val row = rows[alias]
            if (row != null) {
                compared(row, condition.propertyPath.substringAfter('.'), condition.operator, condition.value)
            } else {
                // A property of a related node, named outside any block: some related node has it.
                val relationship = alias.substringBefore('_')
                one(WhereCondition.RelationshipCondition(relationship, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.RelationshipCondition -> related(condition, rows, model)
        is WhereCondition.LabelCondition -> {
            val row = rows[condition.alias]
            if (row != null) {
                hasLabels(row, condition.labels) ?: "1 = 1"
            } else {
                one(WhereCondition.RelationshipCondition(condition.alias, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.AnyLabelCondition -> {
            val row = rows.getValue(condition.alias)
            "EXISTS (SELECT 1 FROM JSON_TABLE($row.labels, '$[*]' COLUMNS (l VARCHAR2(4000) PATH '$')) WHERE l IN (${OracleSql.values(bound(condition.labels), SqlKind.TEXT)}))"
        }
        is WhereCondition.ListMembershipCondition -> {
            val row = rows.getValue(condition.propertyPath.substringBefore('.'))
            holds(row, condition.propertyPath.substringAfter('.'), condition.value)
        }
        is WhereCondition.OrCondition -> condition.conditions.joinToString(" OR ", "(", ")") { one(it, rows, model) }
        is WhereCondition.NotCondition -> "NOT (${all(condition.conditions, rows, model)})"
    }

    /** Whether some node related through the field meets the conditions on it; none does, when the condition is negated. */
    private fun related(condition: WhereCondition.RelationshipCondition, rows: Map<String, String>, model: GraphViewModel?): String {
        requireNotNull(model) { "A condition on the relationship '${condition.relationshipName}' needs a @GraphView to find it in." }
        val relationship = model.relationships.find { it.fieldName == condition.relationshipName }
            ?: throw IllegalArgumentException(
                "Relationship '${condition.relationshipName}' not found in ${model.className}. Available relationships: ${model.relationships.map { it.fieldName }}"
            )
        val parent = rows.getValue(rootAlias)
        val row = next("t")
        val target = relationship.fieldName
        val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType

        // Conditions on `<target>_<nested>` are on a node the target is related to in turn.
        val (nested, direct) = condition.targetConditions.partition {
            it is WhereCondition.PropertyCondition && it.propertyPath.substringBefore('.').startsWith("${target}_")
        }
        val targetRows = linkedMapOf(target to row)
        val predicates = mutableListOf<String>()
        direct.forEach { targetCondition ->
            when (targetCondition) {
                is WhereCondition.ListMembershipCondition, is WhereCondition.NotCondition, is WhereCondition.AnyLabelCondition, is WhereCondition.RelationshipCondition ->
                    throw UnsupportedOperationException(
                        "${targetCondition::class.simpleName} is not supported inside a relationship any{}/none{} block; use it as a top-level root predicate."
                    )
                else -> predicates += one(targetCondition, targetRows, null)
            }
        }
        if (nested.isNotEmpty()) {
            require(targetType.isAnnotationPresent(GraphView::class.java)) {
                "Cannot filter on nested relationship '${target}_*' because ${targetType.simpleName} is not a @GraphView"
            }
            val targetModel = GraphViewModel.from(targetType)
            nested.groupBy { (it as WhereCondition.PropertyCondition).propertyPath.substringBefore('.').removePrefix("${target}_") }
                .forEach { (name, conditions) ->
                    val nestedRelationship = targetModel.relationships.find { it.fieldName == name }
                        ?: throw IllegalArgumentException("Nested relationship '$name' not found in ${targetModel.className}")
                    val nestedRow = next("t")
                    val nestedPredicate = all(conditions, mapOf("${target}_$name" to nestedRow), null)
                    // No labels: a condition on a relationship is met by whatever node it leads to.
                    predicates += exists(row, steps(nestedRelationship, emptyList()), nestedRow, nestedPredicate)
                }
        }
        val found = exists(parent, steps(relationship, emptyList()), row, predicates.joinToString(" AND ").ifEmpty { null })
        return if (condition.negate) "NOT ($found)" else found
    }

    /** `property operator value`, with the property read as the type of the value it is compared with. */
    private fun compared(row: String, key: String, operator: ComparisonOperator, value: Any?): String {
        val text = property(row, key)
        val typed = property(row, key, SqlKind.ofValue(value))
        return when (operator) {
            ComparisonOperator.IS_NULL -> "NOT ${OracleSql.hasProperty(row, key)}"
            ComparisonOperator.IS_NOT_NULL -> OracleSql.hasProperty(row, key)
            ComparisonOperator.EQUALS -> "$typed = ${bound(scalar(value))}"
            ComparisonOperator.NOT_EQUALS -> "$typed <> ${bound(scalar(value))}"
            ComparisonOperator.GREATER_THAN -> "$typed > ${bound(scalar(value))}"
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> "$typed >= ${bound(scalar(value))}"
            ComparisonOperator.LESS_THAN -> "$typed < ${bound(scalar(value))}"
            ComparisonOperator.LESS_THAN_OR_EQUAL -> "$typed <= ${bound(scalar(value))}"
            ComparisonOperator.IN -> "$typed IN (${OracleSql.values(bound(listed(value)), SqlKind.ofValue(value))})"
            ComparisonOperator.NOT_IN -> "NOT ($typed IN (${OracleSql.values(bound(listed(value)), SqlKind.ofValue(value))}))"
            ComparisonOperator.HAS_ELEMENT -> holds(row, key, value)
            ComparisonOperator.CONTAINS -> "INSTR($text, ${bound(value?.toString())}) > 0"
            ComparisonOperator.STARTS_WITH -> bound(value?.toString()).let { "SUBSTR($text, 1, LENGTH($it)) = $it" }
            ComparisonOperator.ENDS_WITH -> bound(value?.toString()).let { "SUBSTR($text, -LENGTH($it)) = $it" }
            ComparisonOperator.CONTAINS_IGNORE_CASE -> "INSTR(LOWER($text), ${bound(value?.toString()?.lowercase())}) > 0"
            ComparisonOperator.EQUALS_IGNORE_CASE -> "LOWER($text) = ${bound(value?.toString()?.lowercase())}"
            ComparisonOperator.MATCHES -> matches(text, value?.toString().orEmpty())
        }
    }

    /** A regular expression that must match the whole value, as Cypher's `=~` does. A leading `(?i)` ignores case. */
    private fun matches(text: String, pattern: String): String {
        val ignoreCase = pattern.startsWith("(?i)")
        val body = pattern.removePrefix("(?i)")
        return "REGEXP_LIKE($text, ${bound("^($body)$")}${if (ignoreCase) ", 'i'" else ""})"
    }

    /** Whether the list property [key] of [row] holds [value]. */
    private fun holds(row: String, key: String, value: Any?): String =
        "JSON_EXISTS($row.props, ${path(key, "[*]?(@ == \$v)")} PASSING ${bound(scalar(value))} AS \"v\")"

    /** A boolean is compared with the text JSON writes it as. */
    private fun scalar(value: Any?): Any? = if (value is Boolean) value.toString() else value

    private fun listed(value: Any?): Collection<*> = when (value) {
        is Collection<*> -> value.map(::scalar)
        is Array<*> -> value.map(::scalar)
        else -> listOf(scalar(value))
    }

    // ----- Keyset paging -----

    /**
     * The predicate that selects the rows after a cursor: for orders `a, b` and the cursor `(A, B)`,
     * `a > A OR (a = A AND b > B)`, each comparison turned round for a descending order. A row whose
     * ordered property is absent meets no comparison, and is not returned.
     */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String {
        require(orders.isNotEmpty()) { "seek requires at least one root orderBy property" + hint(collectionSortCount) }
        require(orders.size == values.size) {
            "seek supplied ${values.size} cursor values for ${orders.size} root orderBy properties" + hint(collectionSortCount)
        }
        orders.zip(values).forEachIndexed { index, (order, value) ->
            require(order.propertyPath == value.propertyPath) {
                "seek property #${index + 1} is ${value.propertyPath}, but orderBy property #${index + 1} is ${order.propertyPath}"
            }
        }
        val cursor = values.map { bound(scalar(it.value)) }
        fun key(index: Int) = ordered(orders[index].propertyPath.substringAfter('.'))
        return orders.indices.joinToString(" OR ", "(", ")") { branch ->
            val equal = (0 until branch).map { "${key(it)} = ${cursor[it]}" }
            val beyond = "${key(branch)} ${if (orders[branch].direction == OrderDirection.ASC) ">" else "<"} ${cursor[branch]}"
            (equal + beyond).joinToString(" AND ", "(", ")")
        }
    }

    private fun hint(collectionSortCount: Int): String =
        if (collectionSortCount == 0) "" else " ($collectionSortCount declared orderBy properties sort a relationship collection, which cannot participate in a keyset cursor)"

    companion object {
        /** What a predicate calls a fragment loaded on its own, as the Cypher statements do. */
        const val FRAGMENT_ALIAS = "n"

        /** `alias.name` or ``alias.`a name` ``. */
        private val SHORTHAND = Regex("([A-Za-z_][A-Za-z0-9_]*)\\.(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))")

        /** What a row of a node has of its own; a predicate that names one means the column. */
        private val COLUMNS = setOf("nid", "labels", "props", "stamp")
    }
}
