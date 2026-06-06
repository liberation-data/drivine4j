package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.model.RelationshipModel

/**
 * Builds Cypher relationship-direction syntax from a [RelationshipModel]. Shared so the arrow
 * rendering is defined once for both load and delete query generation.
 */
internal object Directions {

    /** A single hop arrow for [type] in [direction], e.g. `-[:ACTED_IN]->`. The one renderer. */
    fun hopArrow(type: String, direction: Direction): String = when (direction) {
        Direction.OUTGOING -> "-[:$type]->"
        Direction.INCOMING -> "<-[:$type]-"
        Direction.UNDIRECTED -> "-[:$type]-"
    }

    /** Single-hop direction from a relationship, e.g. `-[:ASSIGNED_TO]->`. */
    fun directionString(rel: RelationshipModel): String = hopArrow(rel.type, rel.direction)

    /** Variable-length direction expanding 1..[maxDepth] hops, e.g. `-[:HAS_LOCATION*1..3]->`. */
    fun varLengthDirectionString(rel: RelationshipModel, maxDepth: Int): String = when (rel.direction) {
        Direction.OUTGOING -> "-[:${rel.type}*1..$maxDepth]->"
        Direction.INCOMING -> "<-[:${rel.type}*1..$maxDepth]-"
        Direction.UNDIRECTED -> "-[:${rel.type}*1..$maxDepth]-"
    }
}