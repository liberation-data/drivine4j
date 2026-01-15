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
                    // Only increment if this operator uses a parameter
                    if (condition.operator != ComparisonOperator.IS_NULL &&
                        condition.operator != ComparisonOperator.IS_NOT_NULL) {
                        paramIndex++
                    }
                    result
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(condition, viewModel, paramIndex)
                    paramIndex += condition.targetConditions.size
                    result
                }
                is WhereCondition.LabelCondition -> {
                    // Label conditions don't use parameters
                    buildLabelCondition(condition)
                }
                is WhereCondition.OrCondition -> {
                    val result = buildOrCondition(condition, viewModel, paramIndex)
                    paramIndex += countParameters(condition.conditions)
                    result
                }
            }
        }
    }

    /**
     * Builds a Cypher condition for a label check.
     * Example: "webUser:WebUser:Anonymous"
     */
    private fun buildLabelCondition(condition: WhereCondition.LabelCondition): String {
        val labelClause = condition.labels.joinToString(":") { it }
        return "${condition.alias}:$labelClause"
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
                        // This is a direct relationship target property
                        relationshipConditions.getOrPut(alias) { mutableListOf() }.add(condition)
                    } else if (alias.contains("_")) {
                        // Possibly a nested relationship (e.g., "raisedBy_worksFor.name")
                        val parentAlias = alias.substringBefore("_")
                        if (parentAlias in relationshipNames) {
                            // This is a nested relationship property
                            // Group it under the parent relationship for now
                            relationshipConditions.getOrPut(parentAlias) { mutableListOf() }.add(condition)
                        } else {
                            // Not a nested relationship, treat as root property
                            grouped.add(condition)
                        }
                    } else {
                        // This is a root fragment property
                        grouped.add(condition)
                    }
                }
                is WhereCondition.RelationshipCondition -> {
                    grouped.add(condition)
                }
                is WhereCondition.LabelCondition -> {
                    // Check if the label condition is for a relationship target
                    if (condition.alias in relationshipNames) {
                        // Convert to a RelationshipCondition with label filter
                        grouped.add(
                            WhereCondition.RelationshipCondition(
                                relationshipName = condition.alias,
                                targetConditions = listOf(condition)
                            )
                        )
                    } else {
                        // Root fragment label condition - add directly
                        grouped.add(condition)
                    }
                }
                is WhereCondition.OrCondition -> {
                    // DON'T group conditions within OR - they need to stay separate
                    // so buildOrCondition can generate separate EXISTS clauses
                    // Each condition will be processed independently by buildOrCondition
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
        val result = processOrders(orders)
        return result.orderByClause ?: ""
    }

    /**
     * Processes order specifications and separates root-level ORDER BY from collection sorts.
     *
     * Root-level orders (e.g., "issue.id") go to the ORDER BY clause.
     * Collection orders (e.g., "assignedTo.name" or "raisedBy_worksFor.name") are converted
     * to CollectionSortSpec for wrapping with apoc.coll.sortMaps().
     *
     * @param orders List of order specifications
     * @param relationshipNames Set of relationship field names (to distinguish collection sorts)
     * @return OrderClauseResult with separate ORDER BY clause and collection sorts
     */
    fun processOrders(orders: List<OrderSpec>, relationshipNames: Set<String> = emptySet()): OrderClauseResult {
        val rootOrders = mutableListOf<OrderSpec>()
        val collectionSorts = mutableListOf<CollectionSortSpec>()

        orders.forEach { order ->
            val alias = order.propertyPath.substringBefore(".")
            val propertyName = order.propertyPath.substringAfter(".")

            // Check if this is a collection sort (relationship alias or nested relationship)
            val isCollectionSort = alias in relationshipNames || alias.contains("_")

            if (isCollectionSort) {
                collectionSorts.add(
                    CollectionSortSpec(
                        relationshipPath = alias,
                        propertyName = propertyName,
                        ascending = order.direction == OrderDirection.ASC
                    )
                )
            } else {
                rootOrders.add(order)
            }
        }

        val orderByClause = if (rootOrders.isNotEmpty()) {
            rootOrders.joinToString(", ") { "${it.propertyPath} ${it.direction.name}" }
        } else {
            null
        }

        return OrderClauseResult(orderByClause, collectionSorts)
    }

    /**
     * Extracts parameter bindings from conditions.
     * Converts condition values into parameter map for binding.
     *
     * @param conditions List of filter conditions
     * @param viewModel Optional GraphViewModel for relationship metadata (needed to match buildWhereClause ordering)
     * @return Map of parameter names to values
     */
    fun extractBindings(conditions: List<WhereCondition>, viewModel: GraphViewModel? = null): Map<String, Any?> {
        // IMPORTANT: Must group conditions the same way as buildWhereClause to maintain parameter ordering
        val grouped = groupConditionsByAlias(conditions, viewModel)

        val bindings = mutableMapOf<String, Any?>()
        var paramIndex = 0

        fun extractRecursive(conds: List<WhereCondition>) {
            conds.forEach { condition ->
                when (condition) {
                    is WhereCondition.PropertyCondition -> {
                        // Skip IS NULL and IS NOT NULL operators as they don't have parameters
                        if (condition.operator != ComparisonOperator.IS_NULL &&
                            condition.operator != ComparisonOperator.IS_NOT_NULL) {
                            val paramName = generateParamName(condition.propertyPath, paramIndex)
                            bindings[paramName] = convertToNeo4jValue(condition.value)
                            paramIndex++
                        } else {
                            // Still increment index to keep ordering consistent with buildWhereClause
                            // Actually no - these don't have parameters, so don't increment
                        }
                    }
                    is WhereCondition.RelationshipCondition -> {
                        // Recursively extract bindings from nested conditions
                        extractRecursive(condition.targetConditions)
                    }
                    is WhereCondition.LabelCondition -> {
                        // Label conditions don't have parameters - nothing to extract
                    }
                    is WhereCondition.OrCondition -> {
                        // Recursively extract bindings from OR conditions
                        extractRecursive(condition.conditions)
                    }
                }
            }
        }

        extractRecursive(grouped)
        return bindings
    }

    /**
     * Builds a Cypher condition for a property filter.
     * Example: "issue.state = $param_issue_state_0"
     */
    private fun buildPropertyCondition(condition: WhereCondition.PropertyCondition, index: Int): String {
        return when (condition.operator) {
            ComparisonOperator.IS_NULL,
            ComparisonOperator.IS_NOT_NULL -> {
                // Null checks don't need parameters
                "${condition.propertyPath} ${condition.operator.cypherOperator}"
            }
            ComparisonOperator.IN -> {
                // IN operator requires list syntax
                val paramName = generateParamName(condition.propertyPath, index)
                "${condition.propertyPath} ${condition.operator.cypherOperator} \$$paramName"
            }
            ComparisonOperator.CONTAINS,
            ComparisonOperator.STARTS_WITH,
            ComparisonOperator.ENDS_WITH -> {
                // String operations
                val paramName = generateParamName(condition.propertyPath, index)
                "${condition.propertyPath} ${condition.operator.cypherOperator} \$$paramName"
            }
            else -> {
                // Standard comparison operators
                val paramName = generateParamName(condition.propertyPath, index)
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

        // Separate direct properties from nested relationship properties
        val (directConditions, nestedConditions) = separateNestedConditions(condition.targetConditions, targetAlias)

        // Build WHERE clauses for direct target conditions
        val directWhere = if (directConditions.isNotEmpty()) {
            var paramIndex = startIndex
            val whereClauses = directConditions.joinToString(" AND ") { targetCondition ->
                when (targetCondition) {
                    is WhereCondition.PropertyCondition -> {
                        val result = buildPropertyCondition(targetCondition, paramIndex)
                        paramIndex++
                        result
                    }
                    is WhereCondition.RelationshipCondition -> {
                        throw UnsupportedOperationException("Should not reach here - nested conditions separated")
                    }
                    is WhereCondition.LabelCondition -> {
                        // Label conditions don't use parameters
                        buildLabelCondition(targetCondition)
                    }
                    is WhereCondition.OrCondition -> {
                        val result = buildOrCondition(targetCondition, viewModel, paramIndex)
                        paramIndex += countParameters(targetCondition.conditions)
                        result
                    }
                }
            }
            " WHERE $whereClauses"
        } else {
            ""
        }

        // Build nested EXISTS patterns for nested relationships
        val nestedPatterns = if (nestedConditions.isNotEmpty()) {
            // Check if the target is a @GraphView
            val targetViewModel = try {
                GraphViewModel.from(relationship.elementType)
            } catch (e: Exception) {
                null  // Not a GraphView, can't have nested relationships
            }

            if (targetViewModel != null) {
                var paramIndex = startIndex + directConditions.size
                nestedConditions.entries.joinToString(" AND ") { (nestedRelName, nestedConds) ->
                    buildNestedRelationshipCondition(
                        parentAlias = targetAlias,
                        nestedRelationshipName = nestedRelName,
                        conditions = nestedConds,
                        targetViewModel = targetViewModel,
                        startIndex = paramIndex
                    ).also {
                        paramIndex += nestedConds.size
                    }
                }
            } else {
                throw IllegalArgumentException(
                    "Cannot filter on nested relationship '${targetAlias}_*' because " +
                    "${relationship.elementType.simpleName} is not a @GraphView"
                )
            }
        } else {
            ""
        }

        // Combine direct and nested WHERE clauses
        val combinedWhere = when {
            directWhere.isNotEmpty() && nestedPatterns.isNotEmpty() -> "$directWhere AND $nestedPatterns"
            directWhere.isNotEmpty() -> directWhere
            nestedPatterns.isNotEmpty() -> " WHERE $nestedPatterns"
            else -> ""
        }

        // Neo4j 5+ uses EXISTS { pattern WHERE conditions }
        return "EXISTS { $relationshipPattern$combinedWhere }"
    }

    /**
     * Separates conditions into direct properties and nested relationship properties.
     *
     * Example: For targetAlias="raisedBy":
     * - "raisedBy.name" -> direct
     * - "raisedBy_worksFor.name" -> nested (relationship="worksFor")
     */
    private fun separateNestedConditions(
        conditions: List<WhereCondition>,
        targetAlias: String
    ): Pair<List<WhereCondition>, Map<String, List<WhereCondition.PropertyCondition>>> {
        val direct = mutableListOf<WhereCondition>()
        val nested = mutableMapOf<String, MutableList<WhereCondition.PropertyCondition>>()

        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val alias = condition.propertyPath.substringBefore(".")
                    if (alias.startsWith("${targetAlias}_")) {
                        // Nested relationship property (e.g., "raisedBy_worksFor.name")
                        val nestedRelName = alias.substringAfter("${targetAlias}_")
                        nested.getOrPut(nestedRelName) { mutableListOf() }.add(condition)
                    } else {
                        // Direct property (e.g., "raisedBy.name")
                        direct.add(condition)
                    }
                }
                else -> {
                    // OR conditions and other types treated as direct
                    direct.add(condition)
                }
            }
        }

        return Pair(direct, nested)
    }

    /**
     * Builds a nested relationship condition within an EXISTS pattern.
     * This handles filtering on relationships within nested GraphViews.
     *
     * Example: When filtering RaisedAndAssignedIssue by raisedBy.worksFor.name:
     * - parentAlias = "raisedBy"
     * - nestedRelationshipName = "worksFor"
     * - Generates: EXISTS { (raisedBy)-[:WORKS_FOR]->(worksFor) WHERE worksFor.name = $param }
     */
    private fun buildNestedRelationshipCondition(
        parentAlias: String,
        nestedRelationshipName: String,
        conditions: List<WhereCondition.PropertyCondition>,
        targetViewModel: GraphViewModel,
        startIndex: Int
    ): String {
        // Find the nested relationship in the target view model
        val nestedRel = targetViewModel.relationships.find { it.fieldName == nestedRelationshipName }
            ?: throw IllegalArgumentException(
                "Nested relationship '$nestedRelationshipName' not found in ${targetViewModel.className}. " +
                "Available relationships: ${targetViewModel.relationships.map { it.fieldName }}"
            )

        // Build the relationship pattern
        val nestedAlias = "${parentAlias}_${nestedRelationshipName}"
        val relationshipPattern = when (nestedRel.direction) {
            org.drivine.annotation.Direction.OUTGOING -> "($parentAlias)-[:${nestedRel.type}]->($nestedAlias)"
            org.drivine.annotation.Direction.INCOMING -> "($parentAlias)<-[:${nestedRel.type}]-($nestedAlias)"
            org.drivine.annotation.Direction.UNDIRECTED -> "($parentAlias)-[:${nestedRel.type}]-($nestedAlias)"
        }

        // Build WHERE clause for the nested conditions
        var paramIndex = startIndex
        val whereClauses = conditions.joinToString(" AND ") { condition ->
            buildPropertyCondition(condition, paramIndex).also {
                paramIndex++
            }
        }

        return "EXISTS { $relationshipPattern WHERE $whereClauses }"
    }

    /**
     * Builds an OR condition by recursively generating sub-conditions.
     * Example: (issue.state = $param OR issue.state = $param2)
     *
     * For relationship properties in OR, each becomes a separate EXISTS clause.
     */
    private fun buildOrCondition(
        condition: WhereCondition.OrCondition,
        viewModel: GraphViewModel?,
        startIndex: Int
    ): String {
        // First, group PropertyConditions that refer to relationships
        // But keep them separate - each OR branch gets its own EXISTS
        val relationshipNames = viewModel?.relationships?.map { it.fieldName }?.toSet() ?: emptySet()

        var paramIndex = startIndex
        val orClauses = condition.conditions.joinToString(" OR ") { subCondition ->
            when (subCondition) {
                is WhereCondition.PropertyCondition -> {
                    // Check if this is a relationship property
                    val alias = subCondition.propertyPath.substringBefore(".")
                    val baseAlias = if (alias.contains("_")) alias.substringBefore("_") else alias

                    if (baseAlias in relationshipNames) {
                        // Convert to RelationshipCondition on the fly
                        val relCondition = WhereCondition.RelationshipCondition(
                            relationshipName = baseAlias,
                            targetConditions = listOf(subCondition)
                        )
                        val result = buildRelationshipCondition(relCondition, viewModel, paramIndex)
                        paramIndex++
                        result
                    } else {
                        // Root property
                        val result = buildPropertyCondition(subCondition, paramIndex)
                        paramIndex++
                        result
                    }
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(subCondition, viewModel, paramIndex)
                    paramIndex += subCondition.targetConditions.size
                    result
                }
                is WhereCondition.LabelCondition -> {
                    // Label conditions don't use parameters
                    // But if it's for a relationship target, wrap in EXISTS
                    if (subCondition.alias in relationshipNames) {
                        val relCondition = WhereCondition.RelationshipCondition(
                            relationshipName = subCondition.alias,
                            targetConditions = listOf(subCondition)
                        )
                        buildRelationshipCondition(relCondition, viewModel, paramIndex)
                    } else {
                        buildLabelCondition(subCondition)
                    }
                }
                is WhereCondition.OrCondition -> {
                    // Nested OR: recursively build it
                    val result = buildOrCondition(subCondition, viewModel, paramIndex)
                    paramIndex += countParameters(subCondition.conditions)
                    result
                }
            }
        }
        return "($orClauses)"
    }

    /**
     * Counts the total number of parameters needed for a list of conditions.
     * Used to properly index parameters in nested OR conditions.
     */
    private fun countParameters(conditions: List<WhereCondition>): Int {
        return conditions.sumOf { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> 1
                is WhereCondition.RelationshipCondition -> condition.targetConditions.size
                is WhereCondition.LabelCondition -> 0  // Label conditions don't have parameters
                is WhereCondition.OrCondition -> countParameters(condition.conditions)
            }
        }
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

    /**
     * Converts a value to a Neo4j-compatible type.
     * Neo4j driver doesn't support java.util.UUID natively, so we convert it to String.
     */
    private fun convertToNeo4jValue(value: Any?): Any? {
        return when (value) {
            is java.util.UUID -> value.toString()
            is Collection<*> -> value.map { convertToNeo4jValue(it) }
            else -> value
        }
    }
}