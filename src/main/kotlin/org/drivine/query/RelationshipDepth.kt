package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.model.RelationshipModel

/**
 * How deep a query reads a relationship field. Shared by the load and by a condition on the field, on
 * every store, so a condition tests the nodes the load reads, to the same depth.
 */
internal object RelationshipDepth {

    /** The depth a query reads [relationship] to: the one it gives for the field in [depthOverrides], or the field's `maxDepth`. */
    fun of(relationship: RelationshipModel, depthOverrides: Map<String, Int>): Int =
        depthOverrides[relationship.fieldName] ?: relationship.maxDepth

    /**
     * The most hops the nodes of [relationship] are away: its depth for a list of node fragments read
     * over several hops (`*1..depth`), and otherwise one. A view nested in itself is a hop at each level,
     * a relationship fragment is one relationship, and a `@GraphPath` takes its own hops.
     */
    fun hops(relationship: RelationshipModel, depthOverrides: Map<String, Int>): Int {
        val target = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
        val walked = relationship.isCollection && !relationship.isPath && !relationship.isRelationshipFragment &&
            !target.isAnnotationPresent(GraphView::class.java)
        val depth = of(relationship, depthOverrides)
        return if (walked && depth > 1) depth else 1
    }
}
