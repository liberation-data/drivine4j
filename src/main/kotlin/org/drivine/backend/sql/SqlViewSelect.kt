package org.drivine.backend.sql

import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.GraphView
import org.drivine.model.AggregateFieldModel
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphTypeLabels
import org.drivine.query.dsl.CollectionSortSpec

/**
 * The walk of a class's model that a SQL store's select makes to read its roots as JSON documents: a
 * view's relationship fields, each a subquery from the root's row, its recursion and its aggregates,
 * and the sorts the query gave them. Oracle and Postgres walk the model alike and write what they find
 * in their own SQL, so what is each store's own is a small dialect: a JSON object, an array gathered in
 * an order, a node's document, a property read as a kind, and the rows reached from a row by steps.
 *
 * A row is called what the model calls it, the root by its field and a related node by the field that
 * holds it, so a statement reads beside the Cypher it stands for.
 */
internal abstract class SqlViewSelect<Scope : StatementScope>(
    graphClass: Class<*>,
    private val collectionSorts: List<CollectionSortSpec>,
    private val depthOverrides: Map<String, Int>,
    protected val scope: Scope,
) : SqlSelect {

    protected val viewModel: GraphViewModel? =
        if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null

    protected val rootModel: FragmentModel = FragmentModel.from(viewModel?.rootFragment?.fragmentType ?: graphClass)

    /** The fragment the statement's roots are: the class itself, or a view's root. */
    final override val rootFragment: Class<*> = viewModel?.rootFragment?.fragmentType ?: graphClass

    /** What a predicate calls the root: `n` for a fragment, the root field's name for a view. */
    val rootAlias: String = viewModel?.rootFragment?.fieldName ?: FRAGMENT_ALIAS

    /**
     * The root's row in the statement. Not the field's name itself: a field may be called `user` or
     * `order`, which SQL keeps for its own, and no name SQL keeps ends in an underscore.
     */
    final override val root: String = scope.row(rootAlias)

    /** The labels of the roots: those of the fragment, or of a view's root. */
    protected fun rootLabels(): List<String> {
        val labels = if (viewModel != null) GraphTypeLabels.fragmentLabels(viewModel.rootFragment.fragmentType) else rootModel.labels
        require(labels.isNotEmpty()) { "No labels defined for fragment ${rootModel.className}. @NodeFragment must specify at least one label." }
        return labels
    }

    /** The values the statement's parameters take, as written so far. */
    final override fun bindings(): Map<String, Any?> = scope.bindings()

    // ----- The dialect -----

    /** An empty JSON array, the value of a collection with nothing in it. */
    protected abstract val emptyArray: String

    /** The node [row] as a document: its properties, its labels and its stamp. */
    protected abstract fun document(row: String): String

    /** A JSON object of [entries], each a key and the expression for its value. */
    protected abstract fun jsonObject(entries: List<Pair<String, String>>): String

    /** The JSON array of [value] over the rows gathered, in [order]. */
    protected abstract fun arrayOf(value: String, order: String): String

    /** The properties of the relationship of the row [row], with [target] under [field]. */
    protected abstract fun withTarget(row: String, field: String, target: String): String

    /** The property [key] of the row [row], read as [kind]. */
    protected abstract fun property(row: String, key: String, kind: SqlKind = SqlKind.TEXT): String

    /**
     * The rows named [row] reached from the row [parent] by [steps]: the query a relationship field or
     * an aggregate is, once it says what it selects of them.
     */
    protected abstract fun related(select: String, parent: String, steps: List<Step>, row: String, tail: List<String> = emptyList()): Query

    /** Whether some node is reached from the row [parent] by [steps], naming the reached node's row [row]. */
    protected abstract fun exists(parent: String, steps: List<Step>, row: String): String

    /**
     * A field that holds each node reached once, however many ways lead to it: a path of several
     * steps, or one step taken up to a depth.
     */
    protected abstract fun distinct(parent: String, relationship: RelationshipModel, steps: List<Step>, targetType: Class<*>, visits: Map<String, Int>): String

    // ----- Projection -----

    /** What the statement gives for each root: its document, or for a view the view's object. */
    protected fun projection(): String = if (viewModel != null) view(root, viewModel, mapOf(viewModel.className to 1), null) else document(root)

    /** The view [model] rooted at the row [alias], as one JSON object. [field] is the relationship field it was reached through. */
    private fun view(alias: String, model: GraphViewModel, visits: Map<String, Int>, field: String?): String {
        val entries = mutableListOf(model.rootFragment.fieldName to document(alias))
        model.relationships.forEach { entries += it.fieldName to relationship(alias, it, visits, field) }
        model.aggregateFields.forEach { entries += it.fieldName to aggregate(alias, it) }
        return jsonObject(entries)
    }

    /**
     * The value of a relationship field of the row [parent]: a subquery that gives an array, or one
     * object or null.
     *
     * A view that would be entered again past its depth is cut off, as an empty list or null, so a
     * cycle of views ends.
     *
     * [parentField] is the path to the view that holds the field, which a sort of the field names.
     * [viewPath] is the path a view the field holds is named under by a sort below it: [parentField],
     * but at a level of a recursive view the level's own, so a sort there orders every level alike.
     */
    private fun relationship(
        parent: String, relationship: RelationshipModel, visits: Map<String, Int>, parentField: String?, viewPath: String? = parentField,
    ): String {
        val depth = depthOverrides[relationship.fieldName] ?: relationship.maxDepth
        val nothing = if (relationship.isCollection) emptyArray else "NULL"
        val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
        val labels = GraphTypeLabels.labelsForType(targetType)
        require(labels.isNotEmpty()) {
            "No labels defined for relationship target ${targetType.name}. @NodeFragment or @GraphView must specify at least one label."
        }
        val targetIsView = targetType.isAnnotationPresent(GraphView::class.java)
        val sort = sortOf(relationship, parentField)

        if (relationship.isPath) return distinct(parent, relationship, Step.reaching(relationship, labels), targetType, visits)
        if (relationship.isRecursive) {
            return if (depth == 0) nothing else recursive(parent, relationship, labels, visits, 1, depth, sort)
        }
        if (targetIsView && (visits[targetType.name] ?: 0) >= depth && targetType.name in visits) return nothing
        if (relationship.isCollection && !targetIsView && !relationship.isRelationshipFragment && depth > 1) {
            return distinct(parent, relationship, Step.reaching(relationship, labels, depth), targetType, visits)
        }

        val row = scope.row(relationship.fieldName)
        // A view this holds is reached by the path to this one and the field, which a sort below it names.
        val target = element(row, targetType, visits, viewPath?.let { "${it}_${relationship.fieldName}" } ?: relationship.fieldName)
        // A relationship fragment is the relationship's own properties, with the node it leads to under the target field.
        val value = if (relationship.isRelationshipFragment) withTarget(row, relationship.targetFieldName!!, target) else target
        return gathered(parent, Step.reaching(relationship, labels), row, value, relationship.isCollection, sort?.let { sorted(row, targetType, it) })
    }

    /** The node [row] as an element of a relationship field: a fragment's map, or a nested view. */
    protected fun element(row: String, type: Class<*>, visits: Map<String, Int>, field: String): String =
        if (type.isAnnotationPresent(GraphView::class.java)) {
            view(row, GraphViewModel.from(type), visits + (type.name to (visits[type.name] ?: 0) + 1), field)
        } else {
            document(row)
        }

    /** [value] for each node, a row named [row], reached from [parent] by [steps]: all of them as an array, or the first. */
    private fun gathered(parent: String, steps: List<Step>, row: String, value: String, collection: Boolean, order: String?): String =
        if (collection) {
            "COALESCE(${related(arrayOf(value, order ?: "$row.eid"), parent, steps, row).nested()}, $emptyArray)"
        } else {
            related(value, parent, steps, row, tail = listOf(first("$row.eid"))).nested()
        }

    /** The clause that keeps the first row by [order]. A store without `FETCH FIRST` writes its own. */
    protected open fun first(order: String): String = "ORDER BY $order FETCH FIRST 1 ROWS ONLY"

    /**
     * The direction of a term of an order, with its nulls where Cypher puts them: last when ascending,
     * first when descending. Oracle and Postgres put them there unasked; a store that does not says so.
     */
    protected open fun direction(ascending: Boolean): String = if (ascending) "ASC" else "DESC"

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
            .forEach { entries += it.fieldName to relationship(row, it, visits, relationship.fieldName, viewPath = null) }
        entries += relationship.fieldName to
            if (depth >= max) (if (relationship.isCollection) emptyArray else "NULL") else recursive(row, relationship, labels, visits, depth + 1, max, sort)
        return gathered(
            parent, Step.reaching(relationship, labels), row, jsonObject(entries), relationship.isCollection,
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
        return related(value, parent, listOf(Step(aggregate.type, aggregate.direction, emptyList())), row).nested()
    }

    /** The sort the query gave for [relationship]: see [RelationshipConditions.sortOf]. */
    private fun sortOf(relationship: RelationshipModel, parentField: String?): CollectionSortSpec? =
        RelationshipConditions.sortOf(collectionSorts, relationship, parentField)

    /**
     * The order of a relationship collection by [sort]: the property read as the type of the field
     * that declares it, with the relationship's id to break a tie, so the order is the same each time.
     */
    private fun sorted(row: String, targetType: Class<*>, sort: CollectionSortSpec): String {
        val fragment = if (targetType.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(targetType).rootFragment.fragmentType else targetType
        val kind = SqlKind.ofType(runCatching { FragmentModel.from(fragment) }.getOrNull()?.fields?.firstOrNull { it.propertyName == sort.propertyName }?.type)
        return "${property(row, sort.propertyName, kind)} ${direction(sort.ascending)}, $row.eid"
    }

    /** A relationship field that is one object and may not be null leaves out the roots that have none. */
    protected fun requiredRelationships(): List<String> =
        viewModel?.relationships.orEmpty().filter { !it.isNullable && !it.isCollection }.map { relationship ->
            val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
            exists(root, Step.reaching(relationship, GraphTypeLabels.labelsForType(targetType)), scope.row(relationship.fieldName))
        }

    companion object {
        /** What a predicate calls a fragment loaded on its own, as the Cypher statements do. */
        const val FRAGMENT_ALIAS = "n"
    }
}
