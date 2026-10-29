package org.drivine.cypher.sql

/**
 * A Cypher statement as SQL: the statement, and the values its parameters are bound to. [nonFinite] says it is given NaN or an
 * infinity, which a store whose JSON holds no such float as a number writes as a value of its own, and gives back in the rows
 * that way: see [org.drivine.mapper.JdbcResultMapper].
 */
data class CompiledCypher(val sql: String, val parameters: Map<String, Any?>, val nonFinite: Boolean = false)
