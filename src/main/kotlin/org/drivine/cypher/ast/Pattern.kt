package org.drivine.cypher.ast

/**
 * A path of a `MATCH`, a pattern comprehension or a pattern predicate: nodes joined by relationships,
 * one more node than relationships. With a [variable], the path itself is bound to it.
 */
data class Pattern(val variable: String?, val nodes: List<NodePattern>, val relationships: List<RelationshipPattern>) {
    init {
        require(nodes.size == relationships.size + 1) { "A path has one node more than it has relationships." }
    }
}
