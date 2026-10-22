package org.drivine.cypher.ast

/**
 * A relationship of a pattern, `-[variable:TYPE|OTHER *min..max {key: value}]->`. Its [types] are
 * alternatives, any of which a relationship may be. With a [range], it is a path of such relationships
 * of that many steps.
 */
data class RelationshipPattern(
    val variable: String?,
    val types: List<String>,
    val direction: Direction,
    val range: Range?,
    val properties: Expression?,
) {
    /** Which way a relationship points, read from the node before it in the pattern. */
    enum class Direction { OUTGOING, INCOMING, EITHER }

    /** How many steps a variable-length relationship takes; a bound left out is open. */
    data class Range(val min: Int?, val max: Int?)
}
