package org.drivine.cypher.ast

/**
 * A relationship of a pattern, `-[variable:TYPE|OTHER *min..max {key: value}]->`. Its [types] are
 * alternatives, any of which a relationship may be. With a [range], it is a path of such relationships
 * of that many steps. With a [group], it is a quantified path pattern, `((a)-[:R]->(b)){1,3}`, that many
 * repetitions of the group's pattern between the nodes beside it, and has no variable, types or properties of its own.
 */
data class RelationshipPattern(
    val variable: String?,
    val types: List<String>,
    val direction: Direction,
    val range: Range?,
    val properties: Expression?,
    val group: Group? = null,
) {
    /** Which way a relationship points, read from the node before it in the pattern. */
    enum class Direction { OUTGOING, INCOMING, EITHER }

    /** How many steps a variable-length relationship takes; a bound left out is open. */
    data class Range(val min: Int?, val max: Int?)

    /**
     * The pattern a quantified path pattern repeats, each repetition's first node the last of the one before,
     * and the [where] each repetition meets. To the rest of the statement, a variable of the pattern is the
     * list of what it matched in each repetition.
     */
    data class Group(val pattern: Pattern, val where: Expression?)
}
