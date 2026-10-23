package org.drivine.cypher.ast

/**
 * A path of a `MATCH`, a `CREATE`, a `MERGE`, an `EXISTS`, a pattern comprehension or a pattern predicate:
 * nodes joined by relationships, one more node than relationships. With a [variable], the path itself is bound to it.
 * With [shortest], only the paths of the least length are found: one of them, or all.
 */
data class Pattern(
    val variable: String?,
    val nodes: List<NodePattern>,
    val relationships: List<RelationshipPattern>,
    val shortest: Shortest? = null,
) {
    init {
        require(nodes.size == relationships.size + 1) { "A path has one node more than it has relationships." }
    }

    /** `shortestPath(…)`, one of the shortest paths, or `allShortestPaths(…)`, every one. */
    enum class Shortest { ONE, ALL }
}
