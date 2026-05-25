package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.model.RelationshipModel

/**
 * Builds Cypher relationship-direction syntax from a [RelationshipModel]. Shared so the arrow
 * rendering is defined once for both load and delete query generation.
 */
internal object Directions {

    /** Single-hop direction, e.g. `-[:ASSIGNED_TO]->`. */
    fun directionString(rel: RelationshipModel): String = when (rel.direction) {
        Direction.OUTGOING -> "-[:${rel.type}]->"
        Direction.INCOMING -> "<-[:${rel.type}]-"
        Direction.UNDIRECTED -> "-[:${rel.type}]-"
    }

    /** Variable-length direction expanding 1..[maxDepth] hops, e.g. `-[:HAS_LOCATION*1..3]->`. */
    fun varLengthDirectionString(rel: RelationshipModel, maxDepth: Int): String = when (rel.direction) {
        Direction.OUTGOING -> "-[:${rel.type}*1..$maxDepth]->"
        Direction.INCOMING -> "<-[:${rel.type}*1..$maxDepth]-"
        Direction.UNDIRECTED -> "-[:${rel.type}*1..$maxDepth]-"
    }
}