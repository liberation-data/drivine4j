package org.drivine.backend.sql

import org.drivine.annotation.Direction
import org.drivine.model.RelationshipModel

/**
 * One step from a node to the nodes it is related to: the relationship's type and direction, and the
 * labels the far node carries. A step with a depth is taken [minDepth] to [maxDepth] times.
 */
internal class Step(val type: String, val direction: Direction, val labels: List<String>, val maxDepth: Int = 1, val minDepth: Int = 1) {

    companion object {
        /**
         * The steps that reach the nodes of [relationship]: for a path, one for each hop, the far node of
         * each but the last carrying the hop's intermediate label; otherwise one step to [maxDepth], with
         * [targetLabels] on the node it ends at.
         */
        fun reaching(relationship: RelationshipModel, targetLabels: List<String>, maxDepth: Int = 1): List<Step> =
            if (relationship.isPath) {
                relationship.hops.mapIndexed { i, hop ->
                    Step(hop.type, hop.direction, if (i == relationship.hops.lastIndex) targetLabels else listOfNotNull(hop.intermediateLabel))
                }
            } else {
                listOf(Step(relationship.type, relationship.direction, targetLabels, maxDepth))
            }
    }
}
