package org.drivine.backend.sql

import org.drivine.annotation.Direction
import org.drivine.model.RelationshipModel
import org.drivine.query.GraphTypeLabels
import org.drivine.query.RelationshipDepth

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

        /**
         * The steps to the nodes a query reads of [relationship], with [depthOverrides] given for it: those
         * that carry its target's labels, as many hops away as [RelationshipDepth.hops] says. The load
         * of the field reads them, and a condition on it tests them.
         */
        fun read(relationship: RelationshipModel, depthOverrides: Map<String, Int>): List<Step> =
            reaching(relationship, GraphTypeLabels.targetLabels(relationship), RelationshipDepth.hops(relationship, depthOverrides))

        /**
         * [steps] as paths of single hops: a step taken to a depth is a path of each length from its least
         * depth to its most, the node between two hops of any labels and the last of the step's, and other
         * steps are the one path they are. A condition asks whether some node such a step reaches meets it,
         * which a path of each length answers by joins: a walk from each node, which the load reads, is
         * answered by a recursive query that the store runs for every node of the graph where a condition
         * correlates it with one (SQLite: 14 s for a count over 5,000 nodes, two hops). The nodes are the same,
         * since each store reads a path of hops, as it walks, over a relationship once: two hops of one type
         * are two relationships, so u — v alone does not reach u back over the relationship to v.
         */
        fun spelled(steps: List<Step>): List<List<Step>> {
            val walked = steps.singleOrNull()?.takeIf { it.maxDepth > 1 } ?: return listOf(steps)
            return (walked.minDepth..walked.maxDepth).map { length ->
                List(length) { i -> Step(walked.type, walked.direction, if (i == length - 1) walked.labels else emptyList()) }
            }
        }

        /** Whether some node [steps] reach meets a condition: [exists] asked of each path they are spelled as ([spelled]), joined by `OR`. */
        fun anyPath(steps: List<Step>, exists: (List<Step>) -> String): String =
            spelled(steps).map(exists).let { if (it.size == 1) it.single() else it.joinToString(" OR ", "(", ")") }
    }
}
