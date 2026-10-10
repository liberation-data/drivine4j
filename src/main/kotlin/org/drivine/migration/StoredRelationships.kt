package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.PersistenceManager
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification

/**
 * Whether the nodes labelled [one] and the nodes labelled [other] can be the same nodes: one list
 * holds every label of the other, in any order. A pattern matches a node that has at least the
 * labels it names, so a subtype's nodes are its supertype's too.
 */
internal fun sameLabels(one: List<String>, other: List<String>): Boolean = one.containsAll(other) || other.containsAll(one)

/** A relationship or path field of a view, with the labels of the nodes at its two ends. */
internal class ViewField(val view: Class<*>, val relationship: RelationshipModel, val rootLabels: List<String>, val targetLabels: List<String>) {

    /**
     * Whether this field reads relationships stored from nodes labelled [from] to nodes labelled [to],
     * or from nodes that can be the same as those.
     */
    fun storesFrom(from: List<String>, to: List<String>): Boolean {
        val away = sameLabels(rootLabels, from) && sameLabels(targetLabels, to)
        val toward = sameLabels(targetLabels, from) && sameLabels(rootLabels, to)
        return when (relationship.direction) {
            Direction.OUTGOING -> away
            Direction.INCOMING -> toward
            Direction.UNDIRECTED -> away || toward
        }
    }
}

/** What a set of views declares about relationships, and what the store holds of them. */
internal class StoredRelationships(private val persistenceManager: PersistenceManager) {

    /**
     * The relationship fields declared in [views] and in the views nested in them, those a save skips
     * among them. A `@GraphPath` field is not one: it names no single relationship.
     */
    fun declaredFields(views: Array<out Class<*>>): List<ViewField> = declared(views).fields.filterNot { it.relationship.isPath }

    /** The `@GraphPath` fields in [views] and in the views nested in them. */
    fun pathFields(views: Array<out Class<*>>): List<ViewField> = declared(views).fields.filter { it.relationship.isPath }

    /** The labels of each kind of node [views] name: their roots and the targets of their fields, nested views included. */
    fun labelSets(views: Array<out Class<*>>): Set<List<String>> = declared(views).labelSets

    /** How many relationships of [type] run from a node labelled [from] to a node labelled [to]. */
    fun count(from: List<String>, type: String, to: List<String>): Long = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (:${from.joinToString(":")})-[r:$type]->(:${to.joinToString(":")}) RETURN count(r)")
            .transform(Long::class.java)
    )

    private class Declared {
        val fields = mutableListOf<ViewField>()
        val labelSets = linkedSetOf<List<String>>()
    }

    private fun declared(views: Array<out Class<*>>): Declared {
        val seen = mutableSetOf<Class<*>>()
        return Declared().also { into -> views.forEach { collect(it, seen, into) } }
    }

    private fun collect(view: Class<*>, seen: MutableSet<Class<*>>, into: Declared) {
        if (!seen.add(view)) return
        require(view.isAnnotationPresent(GraphView::class.java)) { "${view.simpleName} is not a @GraphView." }
        val model = GraphViewModel.from(view)
        val rootLabels = FragmentModel.from(model.rootFragment.fragmentType).labels
        into.labelSets += rootLabels
        model.relationships.forEach { relationship ->
            val target = if (relationship.isRelationshipFragment) {
                requireNotNull(relationship.targetNodeType) { "Relationship fragment '${relationship.fieldName}' has no target" }
            } else {
                relationship.elementType
            }
            val nested = target.isAnnotationPresent(GraphView::class.java)
            val targetFragment = if (nested) GraphViewModel.from(target).rootFragment.fragmentType else target
            val targetLabels = FragmentModel.from(targetFragment).labels
            into.labelSets += targetLabels
            into.fields += ViewField(view, relationship, rootLabels, targetLabels)
            if (nested) collect(target, seen, into)
        }
    }
}
