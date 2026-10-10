package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.PersistenceManager
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification

/** A relationship or path field of a view, with the labels of the nodes at its two ends. */
internal class ViewField(val view: Class<*>, val relationship: RelationshipModel, val rootLabels: List<String>, val targetLabels: List<String>) {

    /** Whether this field reads relationships stored from nodes labelled [from] to nodes labelled [to]. */
    fun storesFrom(from: List<String>, to: List<String>): Boolean = when (relationship.direction) {
        Direction.OUTGOING -> rootLabels == from && targetLabels == to
        Direction.INCOMING -> targetLabels == from && rootLabels == to
        Direction.UNDIRECTED -> (rootLabels == from && targetLabels == to) || (targetLabels == from && rootLabels == to)
    }
}

/** What a set of views declares about relationships, and what the store holds of them. */
internal class StoredRelationships(private val persistenceManager: PersistenceManager) {

    /** The relationship fields a save writes, in [views] and in the views nested in them. */
    fun writtenFields(views: Array<out Class<*>>): List<ViewField> = fields(views).filterNot { it.relationship.readOnly }

    /** The `@GraphPath` fields in [views] and in the views nested in them. */
    fun pathFields(views: Array<out Class<*>>): List<ViewField> = fields(views).filter { it.relationship.isPath }

    /** How many relationships of [type] run from a node labelled [from] to a node labelled [to]. */
    fun count(from: List<String>, type: String, to: List<String>): Long = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (:${from.joinToString(":")})-[r:$type]->(:${to.joinToString(":")}) RETURN count(r)")
            .transform(Long::class.java)
    )

    private fun fields(views: Array<out Class<*>>): List<ViewField> {
        val seen = mutableSetOf<Class<*>>()
        return views.flatMap { fieldsOf(it, seen) }
    }

    private fun fieldsOf(view: Class<*>, seen: MutableSet<Class<*>>): List<ViewField> {
        if (!seen.add(view)) return emptyList()
        require(view.isAnnotationPresent(GraphView::class.java)) { "${view.simpleName} is not a @GraphView." }
        val model = GraphViewModel.from(view)
        val rootLabels = FragmentModel.from(model.rootFragment.fragmentType).labels
        return model.relationships.flatMap { relationship ->
            val target = if (relationship.isRelationshipFragment) {
                requireNotNull(relationship.targetNodeType) { "Relationship fragment '${relationship.fieldName}' has no target" }
            } else {
                relationship.elementType
            }
            val nested = target.isAnnotationPresent(GraphView::class.java)
            val targetFragment = if (nested) GraphViewModel.from(target).rootFragment.fragmentType else target
            listOf(ViewField(view, relationship, rootLabels, FragmentModel.from(targetFragment).labels)) +
                if (nested) fieldsOf(target, seen) else emptyList()
        }
    }
}
