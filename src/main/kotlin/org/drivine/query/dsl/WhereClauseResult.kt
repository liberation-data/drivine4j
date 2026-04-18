package org.drivine.query.dsl

/**
 * Result of building a WHERE clause. For Neo4j, prologs will be empty and the
 * full condition goes in whereClause. For openCypher dialects that lack EXISTS { },
 * relationship filters are lifted into CALL subquery prologs.
 */
data class WhereClauseResult(
    val whereClause: String?,
    val prologs: List<String> = emptyList(),
    val bridgeVariables: List<String> = emptyList(),
)
