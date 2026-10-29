package org.drivine.cypher.ast

/** A node of a pattern, `(variable:Label {key: value})`: each part may be left out. */
data class NodePattern(val variable: String?, val labels: List<String>, val properties: Expression?)
