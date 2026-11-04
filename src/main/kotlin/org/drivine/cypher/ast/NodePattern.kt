package org.drivine.cypher.ast

/** A node of a pattern, `(variable:Label {key: value})`: each part may be left out. */
data class NodePattern(val variable: String?, val labels: List<String>, val properties: Expression?) {
    /**
     * Whether the node is written with a map of properties: an empty one, `{}`, is read as no [properties] (#583), but a
     * `CREATE` or `MERGE` still makes no node bound already with it, as Neo4j refuses it. Left out of equality, as it matches alike.
     */
    var mapped: Boolean = properties != null
        internal set
}
