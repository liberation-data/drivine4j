package org.drivine.query

/**
 * Represents a query whereby the statement and parameters have been formatted for the target database platform.
 */
data class CompiledQuery(
    val statement: String,
    val parameters: Map<String, Any>
)
