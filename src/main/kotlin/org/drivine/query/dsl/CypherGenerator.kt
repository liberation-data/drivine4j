package org.drivine.query.dsl

import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates Cypher query fragments from DSL specifications.
 * Handles WHERE clauses, ORDER BY clauses, and parameter binding.
 */
object CypherGenerator {

    /**
     * Builds a WHERE clause from a list of conditions.
     * All conditions are AND'd together.
     *
     * @param conditions List of filter conditions
     * @return Cypher WHERE clause (without the WHERE keyword)
     */
    fun buildWhereClause(conditions: List<WhereCondition>): String {
        return conditions.mapIndexed { index, condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> buildPropertyCondition(condition, index)
                is WhereCondition.RelationshipCondition -> buildRelationshipCondition(condition)
            }
        }.joinToString(" AND ")
    }

    /**
     * Builds an ORDER BY clause from a list of order specifications.
     *
     * @param orders List of order specifications
     * @return Cypher ORDER BY clause (without the ORDER BY keywords)
     */
    fun buildOrderByClause(orders: List<OrderSpec>): String {
        return orders.joinToString(", ") { order ->
            "${order.propertyPath} ${order.direction.name}"
        }
    }

    /**
     * Extracts parameter bindings from conditions.
     * Converts condition values into parameter map for binding.
     *
     * @param conditions List of filter conditions
     * @return Map of parameter names to values
     */
    fun extractBindings(conditions: List<WhereCondition>): Map<String, Any?> {
        val bindings = mutableMapOf<String, Any?>()

        conditions.forEachIndexed { index, condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val paramName = generateParamName(condition.propertyPath, index)
                    bindings[paramName] = condition.value
                }
                is WhereCondition.RelationshipCondition -> {
                    // Recursively extract bindings from nested conditions
                    bindings.putAll(extractBindings(condition.targetConditions))
                }
            }
        }

        return bindings
    }

    /**
     * Builds a Cypher condition for a property filter.
     * Example: "issue.state = $param_issue_state_0"
     */
    private fun buildPropertyCondition(condition: WhereCondition.PropertyCondition, index: Int): String {
        val paramName = generateParamName(condition.propertyPath, index)

        return when (condition.operator) {
            ComparisonOperator.IN -> {
                // IN operator requires list syntax
                "${condition.propertyPath} ${condition.operator.cypherOperator} \$$paramName"
            }
            ComparisonOperator.CONTAINS,
            ComparisonOperator.STARTS_WITH,
            ComparisonOperator.ENDS_WITH -> {
                // String operations
                "${condition.propertyPath} ${condition.operator.cypherOperator} \$$paramName"
            }
            else -> {
                // Standard comparison operators
                "${condition.propertyPath} ${condition.operator.cypherOperator} \$$paramName"
            }
        }
    }

    /**
     * Builds a Cypher condition for relationship target filtering.
     * Uses EXISTS with pattern matching.
     *
     * Example: EXISTS((issue)-[:ASSIGNED_TO]->(assignee) WHERE assignee.name = $param)
     */
    private fun buildRelationshipCondition(condition: WhereCondition.RelationshipCondition): String {
        // TODO: Implement relationship filtering
        // This requires knowing the relationship pattern from the GraphView model
        // For now, throw an exception
        throw UnsupportedOperationException(
            "Relationship filtering not yet implemented. " +
            "Currently only root fragment properties can be filtered."
        )
    }

    /**
     * Generates a unique parameter name for a property path.
     * Example: "issue.state" with index 0 -> "param_issue_state_0"
     */
    private fun generateParamName(propertyPath: String, index: Int): String {
        val sanitized = propertyPath.replace(".", "_")
        return "param_${sanitized}_$index"
    }

    /**
     * Resets the parameter counter (no longer needed, kept for backwards compatibility).
     */
    fun resetParamCounter() {
        // No-op: we now use index-based naming
    }
}