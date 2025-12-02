package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
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
     * @param viewModel Optional GraphViewModel for relationship metadata (needed for relationship filtering)
     * @return Cypher WHERE clause (without the WHERE keyword)
     */
    fun buildWhereClause(conditions: List<WhereCondition>, viewModel: GraphViewModel? = null): String {
        // Group property conditions by alias to detect relationship filters
        val grouped = groupConditionsByAlias(conditions, viewModel)

        var paramIndex = 0
        return grouped.joinToString(" AND ") { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val result = buildPropertyCondition(condition, paramIndex)
                    paramIndex++
                    result
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(condition, viewModel, paramIndex)
                    paramIndex += condition.targetConditions.size
                    result
                }
            }
        }
    }

    /**
     * Groups property conditions by alias, converting relationship target conditions
     * into RelationshipCondition objects.
     */
    private fun groupConditionsByAlias(
        conditions: List<WhereCondition>,
        viewModel: GraphViewModel?
    ): List<WhereCondition> {
        if (viewModel == null) return conditions

        val relationshipNames = viewModel.relationships.map { it.fieldName }.toSet()
        val rootAlias = viewModel.rootFragment.fieldName

        // Group PropertyConditions by their alias
        val grouped = mutableListOf<WhereCondition>()
        val relationshipConditions = mutableMapOf<String, MutableList<WhereCondition.PropertyCondition>>()

        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    // Extract alias from property path (e.g., "assignedTo.name" -> "assignedTo")
                    val alias = condition.propertyPath.substringBefore(".")

                    if (alias in relationshipNames) {
                        // This is a relationship target property
                        relationshipConditions.getOrPut(alias) { mutableListOf() }.add(condition)
                    } else {
                        // This is a root fragment property
                        grouped.add(condition)
                    }
                }
                is WhereCondition.RelationshipCondition -> {
                    grouped.add(condition)
                }
            }
        }

        // Convert grouped relationship conditions into RelationshipCondition objects
        relationshipConditions.forEach { (relationshipName, propConditions) ->
            grouped.add(
                WhereCondition.RelationshipCondition(
                    relationshipName = relationshipName,
                    targetConditions = propConditions
                )
            )
        }

        return grouped
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
        var paramIndex = 0

        fun extractRecursive(conds: List<WhereCondition>) {
            conds.forEach { condition ->
                when (condition) {
                    is WhereCondition.PropertyCondition -> {
                        val paramName = generateParamName(condition.propertyPath, paramIndex)
                        bindings[paramName] = condition.value
                        paramIndex++
                    }
                    is WhereCondition.RelationshipCondition -> {
                        // Recursively extract bindings from nested conditions
                        extractRecursive(condition.targetConditions)
                    }
                }
            }
        }

        extractRecursive(conditions)
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
     * Example: EXISTS { (issue)-[:ASSIGNED_TO]->(assignee) WHERE assignee.name = $param }
     */
    private fun buildRelationshipCondition(
        condition: WhereCondition.RelationshipCondition,
        viewModel: GraphViewModel?,
        startIndex: Int
    ): String {
        requireNotNull(viewModel) {
            "GraphViewModel is required for relationship filtering. " +
            "This is likely a bug - relationship conditions should only be generated for GraphViews."
        }

        // Find the relationship metadata
        val relationship = viewModel.relationships.find { it.fieldName == condition.relationshipName }
            ?: throw IllegalArgumentException(
                "Relationship '${condition.relationshipName}' not found in ${viewModel.className}. " +
                "Available relationships: ${viewModel.relationships.map { it.fieldName }}"
            )

        // Get the root fragment alias (usually the field name)
        val rootAlias = viewModel.rootFragment.fieldName

        // Get the relationship target alias (usually the relationship field name)
        val targetAlias = relationship.fieldName

        // Build the relationship pattern based on direction
        val relationshipPattern = when (relationship.direction) {
            org.drivine.annotation.Direction.OUTGOING -> "($rootAlias)-[:${relationship.type}]->($targetAlias)"
            org.drivine.annotation.Direction.INCOMING -> "($rootAlias)<-[:${relationship.type}]-($targetAlias)"
            org.drivine.annotation.Direction.UNDIRECTED -> "($rootAlias)-[:${relationship.type}]-($targetAlias)"
        }

        // Build WHERE clauses for target conditions
        val targetWhere = if (condition.targetConditions.isNotEmpty()) {
            var paramIndex = startIndex
            val whereClauses = condition.targetConditions.joinToString(" AND ") { targetCondition ->
                when (targetCondition) {
                    is WhereCondition.PropertyCondition -> {
                        val result = buildPropertyCondition(targetCondition, paramIndex)
                        paramIndex++
                        result
                    }
                    is WhereCondition.RelationshipCondition -> {
                        throw UnsupportedOperationException("Nested relationship conditions not yet supported")
                    }
                }
            }
            " WHERE $whereClauses"
        } else {
            ""
        }

        // Neo4j 5+ uses EXISTS { pattern WHERE conditions }
        return "EXISTS { $relationshipPattern$targetWhere }"
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