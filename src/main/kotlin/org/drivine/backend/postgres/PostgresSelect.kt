package org.drivine.backend.postgres

import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.backend.GraphStatement
import org.drivine.backend.postgres.PostgresSql.EMPTY_ARRAY
import org.drivine.backend.postgres.PostgresSql.document
import org.drivine.backend.postgres.PostgresSql.property
import org.drivine.backend.sql.CypherShorthand
import org.drivine.backend.sql.Query
import org.drivine.backend.sql.RowSource
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.StatementScope
import org.drivine.backend.sql.Step
import org.drivine.backend.sql.Table
import org.drivine.backend.sql.under
import org.drivine.model.AggregateFieldModel
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphTypeLabels
import org.drivine.query.SqlParameters
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

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

    private val scope = StatementScope(MAX_NAME)

    /** The root's row in the statement. Not the field's name itself: a field may be called `user` or `order`, which SQL keeps for its own. */
    val root: String = scope.row(rootAlias)

    private val rows = PostgresRowSources(graph, scope)
    private val predicates = PostgresPredicates(scope, rows, rootAlias, root, rootModel, viewModel)
    private val shorthand = CypherShorthand(rootAlias, root) { row, key -> property(row, key) }

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
    fun load(predicates: List<String>, orders: List<OrderTerm> = emptyList(), skip: Int? = null, limit: Int? = null): GraphStatement {
        val result = "${projection()} AS result"
        val page = listOfNotNull(
            orders.takeIf { it.isNotEmpty() }?.let { "ORDER BY " + it.joinToString(", ", transform = this.predicates::orderTerms) },
            skip?.let { "OFFSET ${scope.bound(it)} ROWS" },
            limit?.let { "FETCH NEXT ${scope.bound(it)} ROWS ONLY" },
        )
        return GraphStatement(Query(result, roots(), selected(predicates), page).text(), bindings())
    }

    /** The count of the roots [predicates] select, and that have the relationships the view requires. */
    fun count(predicates: List<String>): GraphStatement =
        GraphStatement(Query("COUNT(*)", roots(), selected(predicates)).text(), bindings())

    /** What a load or a count asks of a root: its labels, [predicates], and the relationships the view requires. */
    private fun selected(predicates: List<String>): List<String> =
        listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + predicates + requiredRelationships()

    /** The nodes the statement's roots are among. */
    private fun roots(): RowSource = rows.nodes(root)

    /** What a load returns for a root: a fragment's map, or a view's object. */
    private fun projection(): String = if (viewModel != null) view(root, viewModel, mapOf(viewModel.className to 1), null) else document(root)

    /**
     * The predicate that selects the roots of this statement from the node table, when it is aliased
     * as [root]: those with the labels that [predicates] select. A delete selects them so, as the
     * Cypher delete does; a load and a count also ask for the relationships the view requires.
     */
    fun selecting(predicates: List<String>): String =
        (listOfNotNull(PostgresSql.hasLabels(root, rootLabels())) + predicates).joinToString("\n  AND ")

    /** The values the statement's parameters take, as written so far. */
    fun bindings(): Map<String, Any?> = scope.bindings()

    /**
     * A predicate the caller wrote, with each property of the root written as Cypher writes it read as
     * that property: see [CypherShorthand]. In parentheses, since it may hold an `OR`, and is joined to
     * the statement's own predicates by `AND`.
     */
    fun written(predicate: String): String = "(${shorthand.rewrite(predicate)})"

    /**
     * The root whose id is bound as [parameter]. A property is read as text, so the id is compared as
     * its text: a number bound as one is not compared with a text.
     */
    fun hasId(parameter: String): String {
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
    fun meeting(conditions: List<WhereCondition>): String? {
        val predicate = predicate(conditions) ?: return null
        val query = "SELECT COUNT(*)\nFROM ${graph.nodes} $root\nWHERE $root.nid = \$1\n  AND ${selecting(listOf(predicate))}"
        val values = bindings()
        val names = SqlParameters.positional(query).names
        return SqlParameters.positional(query) { PostgresSql.inline(values.getValue(names[it])) }.statement
    }

    /** The predicate [conditions] of the query DSL make on the root; null when there are none. */
    fun predicate(conditions: List<WhereCondition>): String? = predicates.predicate(conditions)

    /** The predicate that selects the rows after a keyset cursor. See [PostgresPredicates.after]. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String = predicates.after(orders, values, collectionSortCount)

    // ----- Projection -----

    /** The view [model] rooted at the row [alias], as one JSON object. [field] is the relationship field it was reached through. */
    private fun view(alias: String, model: GraphViewModel, visits: Map<String, Int>, field: String?): String {
        val entries = mutableListOf(model.rootFragment.fieldName to document(alias))
        model.relationships.forEach { entries += it.fieldName to relationship(alias, it, visits, field) }
        model.aggregateFields.forEach { entries += it.fieldName to aggregate(alias, it) }
        return PostgresSql.jsonObject(entries)
    }

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

        val row = scope.row(relationship.fieldName)
        val target = element(row, targetType, visits, relationship.fieldName)
        val value = if (relationship.isRelationshipFragment) {
            // The relationship's own properties, with the node it leads to under the target field.
            "($row.eprops || jsonb_build_object(${PostgresSql.literal(relationship.targetFieldName!!)}, $target))"
        } else {
            target
        }
        return gathered(parent, rows.steps(relationship, labels), row, value, relationship.isCollection, sort?.let { sorted(row, targetType, it) })
    }

    /** The node [row] as an element of a relationship field: a fragment's map, or a nested view. */
    private fun element(row: String, type: Class<*>, visits: Map<String, Int>, field: String): String =
        if (type.isAnnotationPresent(GraphView::class.java)) {
            view(row, GraphViewModel.from(type), visits + (type.name to (visits[type.name] ?: 0) + 1), field)
        } else {
            document(row)
        }

    /** [value] for each node, a row named [row], reached from [parent] by [steps]: all of them as an array, or the first. */
    private fun gathered(parent: String, steps: List<Step>, row: String, value: String, collection: Boolean, order: String?): String =
        if (collection) {
            "COALESCE(${rows.related(PostgresSql.arrayOf(value, order ?: "$row.eid"), parent, steps, row).nested()}, $EMPTY_ARRAY)"
        } else {
            rows.related(value, parent, steps, row, tail = listOf(first("$row.eid"))).nested()
        }

    /** The clause that keeps the first row by [order]. */
    private fun first(order: String): String = "ORDER BY $order FETCH FIRST 1 ROWS ONLY"

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth. The path gives a node once for each way to it, so its
     * ways are grouped by the node reached, and the node is read again from the node table.
     */
    private fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String {
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
            "COALESCE(${Query(PostgresSql.arrayOf(value, sort), source).nested()}, $EMPTY_ARRAY)"
        } else {
            Query(value, source, tail = listOf(first(sort))).nested()
        }
    }

    /** The nodes [grouped] gives once each, as rows named [once], joined to their rows of [nodes], named [row]. */
    private class OnceReached(private val grouped: String, private val once: String, private val nodes: String, private val row: String) : RowSource {
        override val alias: String get() = row
        override fun reading(readers: List<String>): String = "(\n    ${grouped.under()}\n) $once\nJOIN $nodes $row ON $row.nid = $once.nid"
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
        val row = scope.row(relationship.fieldName)
        val entries = mutableListOf(nested.rootFragment.fieldName to document(row))
        nested.relationships
            .filter { !it.isRecursive && it.fieldName != relationship.fieldName }
            .forEach { entries += it.fieldName to relationship(row, it, visits, relationship.fieldName) }
        entries += relationship.fieldName to
            if (depth >= max) (if (relationship.isCollection) EMPTY_ARRAY else "NULL") else recursive(row, relationship, labels, visits, depth + 1, max, sort)
        return gathered(
            parent, rows.steps(relationship, labels), row, PostgresSql.jsonObject(entries), relationship.isCollection,
            sort?.let { sorted(row, relationship.elementType, it) },
        )
    }

    /**
     * An aggregate field: `COUNT` counts the relationships, as Cypher's `size(…)` of the pattern does,
     * and any other function reads the named property of each related node as a number.
     */
    private fun aggregate(parent: String, aggregate: AggregateFieldModel): String {
        val row = scope.row(aggregate.fieldName)
        val value = if (aggregate.function == AggregateFunction.COUNT) {
            "COUNT(*)"
        } else {
            val key = requireNotNull(aggregate.property) { "@Aggregate(${aggregate.function}) on '${aggregate.fieldName}' requires a property" }
            "${aggregate.function.name}(${property(row, key, SqlKind.NUMBER)})"
        }
        return rows.related(value, parent, listOf(Step(aggregate.type, aggregate.direction, emptyList())), row).nested()
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
            rows.exists(root, rows.steps(relationship, GraphTypeLabels.labelsForType(targetType)), scope.row(relationship.fieldName))
        }

    // ----- What a delete selects -----

    /**
     * The ids of the nodes a cascading delete takes with the roots that meet [predicates]: every node
     * reached through a relationship field the view declares and writes, and through those of the views
     * nested in it. A field that holds views of its own class is followed to its depth, and every level
     * of it holds what the root does. Null when the view has no such field.
     */
    fun cascaded(predicates: List<String>): String? {
        val model = viewModel ?: return null
        val paths = cascade(model).paths
        if (paths.isEmpty()) return null
        return paths.joinToString("\nUNION\n") { steps -> reachedFromRoots(steps, predicates) }
    }

    /**
     * For each field that holds views of its own class and that a cascading delete of the roots that
     * meet [predicates] follows: the field, and the query that counts the nodes it reaches one level
     * below its depth that it does not reach within it, nor starts from. Such a node is related to one
     * the delete takes, and would be left behind with all it holds.
     */
    fun cascadeDepthChecks(predicates: List<String>): List<Pair<CascadeRecursion, String>> {
        val model = viewModel ?: return emptyList()
        return cascade(model).recursions.map { recursion ->
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
    fun cascadeSteps(): List<Step> =
        viewModel?.let { model -> cascade(model).paths.flatten().distinctBy { it.type to it.direction } }.orEmpty()

    /** The roots that meet [predicates], as a query of their ids. */
    fun roots(predicates: List<String>): String = "SELECT $root.nid\nFROM ${graph.nodes} $root\nWHERE ${selecting(predicates)}"

    /** The ids of the nodes [steps] reach from the roots that meet [predicates]. */
    private fun reachedFromRoots(steps: List<Step>, predicates: List<String>): String {
        val row = scope.row("cascaded")
        return Query("$row.nid", rows.reached(PostgresRowSources.Anchor.among(roots(predicates)), steps, row)).text()
    }

    /**
     * A field that holds views of its own class, of [view], that a cascade follows by [step] once it
     * has come by [path].
     */
    class CascadeRecursion(val view: String, val field: String, val path: List<Step>, val step: Step)

    /** The paths a cascade follows from a root, and the fields that hold views of their own class among them. */
    private class Cascade(val paths: List<List<Step>>, val recursions: List<CascadeRecursion>)

    /**
     * The paths a cascading delete of [model] follows from a root: one for each relationship field it
     * writes, and for each field of a view nested in it, while that view is within its depth. The
     * steps after a field that holds views of its own class continue from its far node, which stands
     * for every level of it: they are the nested view's other fields, as a load reads at each level.
     */
    private fun cascade(model: GraphViewModel): Cascade {
        val paths = mutableListOf<List<Step>>()
        val recursions = mutableListOf<CascadeRecursion>()
        fun walk(view: GraphViewModel, path: List<Step>, visits: Map<String, Int>, followRecursive: Boolean) {
            // A read-only field (every path is one) is not the view's to delete: other code writes what it loads.
            view.relationships.filterNot { it.readOnly || (it.isRecursive && !followRecursive) }.forEach { relationship ->
                val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
                val labels = GraphTypeLabels.labelsForType(targetType)
                if (labels.isEmpty()) return@forEach
                val depth = if (relationship.isRecursive) relationship.maxDepth.coerceAtLeast(1) else 1
                val step = Step(relationship.type, relationship.direction, labels, depth)
                val reached = path + step
                paths += reached
                val visited = visits[targetType.name] ?: 0
                if (relationship.isRecursive) {
                    recursions += CascadeRecursion(view.className, relationship.fieldName, path, step)
                    // Its own field is the step taken to its depth already.
                    walk(GraphViewModel.from(targetType), reached, visits, followRecursive = false)
                } else if (targetType.isAnnotationPresent(GraphView::class.java) && visited < relationship.maxDepth) {
                    walk(GraphViewModel.from(targetType), reached, visits + (targetType.name to visited + 1), followRecursive = true)
                }
            }
        }
        walk(model, emptyList(), mapOf(model.className to 1), followRecursive = true)
        return Cascade(paths, recursions)
    }

    // ----- A traversal on its own -----

    /**
     * Whether the root is a node that the node of [labels] whose id is bound as [parameter] is related
     * to by a relationship of [type] in [direction].
     */
    fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String {
        val from = scope.row("from")
        val row = scope.row("related")
        val found = Query("$from.nid", Table(graph.nodes, from), listOfNotNull("${property(from, key)} = CAST(\$$parameter AS text)", PostgresSql.hasLabels(from, labels)))
        val reached = rows.reached(PostgresRowSources.Anchor.among(found.text()), listOf(Step(type, direction, emptyList())), row)
        return "$root.nid IN ${Query("$row.nid", reached).nested()}"
    }

    companion object {
        /** What a predicate calls a fragment loaded on its own, as the Cypher statements do. */
        const val FRAGMENT_ALIAS = "n"

        /** As much of a field's name as a row is called by: Postgres allows 63, and a count and an underscore follow. */
        private const val MAX_NAME = 55
    }
}
