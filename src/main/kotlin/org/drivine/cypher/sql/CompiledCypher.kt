package org.drivine.cypher.sql

/** A Cypher statement as SQL: the statement, and the values its parameters are bound to. */
data class CompiledCypher(val sql: String, val parameters: Map<String, Any?>)
