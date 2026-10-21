package org.drivine.backend.sql

import org.drivine.annotation.Direction

/**
 * One step from a node to the nodes it is related to: the relationship's type and direction, and the
 * labels the far node carries. A step with a depth is taken [minDepth] to [maxDepth] times.
 */
internal class Step(val type: String, val direction: Direction, val labels: List<String>, val maxDepth: Int = 1, val minDepth: Int = 1)
