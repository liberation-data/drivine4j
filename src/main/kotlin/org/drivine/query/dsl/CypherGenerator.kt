package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.grammar.OpenCypherGrammar
import org.drivine.query.sort.ApocSortMapsEmitter
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
    /**
     * @param projectedCollectionMode when true, relationship predicates render as a list predicate
     *   over the *already-projected* relationship collection (`any(x IN mentions WHERE …)`) instead
     *   of an `EXISTS { (root)-[…] }` subquery. Used by the vector-search path, whose filter runs
     *   after projection (the root is a map, not a node) — the same reason property predicates work
     *   post-projection. See [buildProjectedCollectionPredicate].
     */
    fun buildWhereClause(
        conditions: List<WhereCondition>,
        viewModel: GraphViewModel? = null,
        grammar: CypherGrammar = Neo4j5Grammar(ApocSortMapsEmitter()),
        projectedCollectionMode: Boolean = false,
    ): WhereClauseResult {
        val grouped = groupConditionsByAlias(conditions, viewModel)
        val prologs = mutableListOf<String>()
        val bridgeVars = mutableListOf<String>()
        val ecCounter = AtomicInteger(0)

        var paramIndex = 0
        val whereClause = grouped.joinToString(" AND ") { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val result = buildPropertyCondition(condition, paramIndex)
                    if (condition.operator != ComparisonOperator.IS_NULL &&
                        condition.operator != ComparisonOperator.IS_NOT_NULL) {
                        paramIndex++
                    }
                    result
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(condition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += condition.targetConditions.size
                    result
                }
                is WhereCondition.LabelCondition -> {
                    buildLabelCondition(condition)
                }
                is WhereCondition.OrCondition -> {
                    val result = buildOrCondition(condition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += countParameters(condition.conditions)
                    result
                }
                is WhereCondition.ListMembershipCondition -> {
                    val result = buildListMembershipCondition(condition, paramIndex)
                    paramIndex++
                    result
                }
            }
        }

        return WhereClauseResult(
            whereClause = whereClause.ifEmpty { null },
            prologs = prologs,
            bridgeVariables = bridgeVars,
        )
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
                is WhereCondition.ListMembershipCondition -> {
                    // A root list-property predicate (`$value IN root.listProp`); keep it in place.
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
                    is WhereCondition.ListMembershipCondition -> {
                        val paramName = generateParamName(condition.propertyPath, paramIndex)
                        bindings[paramName] = convertToNeo4jValue(condition.value)
                        paramIndex++
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
        val lhs = renderPropertyPath(condition.propertyPath)
        return when (condition.operator) {
            ComparisonOperator.IS_NULL,
            ComparisonOperator.IS_NOT_NULL -> {
                // Null checks don't need parameters
                "$lhs ${condition.operator.cypherOperator}"
            }
            ComparisonOperator.IN -> {
                // IN operator requires list syntax
                val paramName = generateParamName(condition.propertyPath, index)
                "$lhs ${condition.operator.cypherOperator} \$$paramName"
            }
            ComparisonOperator.CONTAINS,
            ComparisonOperator.STARTS_WITH,
            ComparisonOperator.ENDS_WITH -> {
                // String operations
                val paramName = generateParamName(condition.propertyPath, index)
                "$lhs ${condition.operator.cypherOperator} \$$paramName"
            }
            else -> {
                // Standard comparison operators
                val paramName = generateParamName(condition.propertyPath, index)
                "$lhs ${condition.operator.cypherOperator} \$$paramName"
            }
        }
    }

    /**
     * Builds a list-membership condition: `$param IN <alias>.<property>`. The mirror of the `IN`
     * [PropertyCondition] (whose property is on the left); here the bound caller value is on the left
     * and the list-valued property on the right. Uses the same path/backtick and parameter-name logic
     * as the other property predicates, so it composes and binds identically — and in the vector path's
     * projected-collection mode the root is a map, so `root.listProp` is plain map access (no pattern,
     * no FalkorDB vecf32 quirk).
     */
    private fun buildListMembershipCondition(condition: WhereCondition.ListMembershipCondition, index: Int): String {
        val rhs = renderPropertyPath(condition.propertyPath)
        val paramName = generateParamName(condition.propertyPath, index)
        return "\$$paramName IN $rhs"
    }

    /**
     * Renders a `alias.property` path for the left-hand side of a condition, backtick-quoting the
     * property segment when it contains a dot — a `@PropertyBag` key like `proposition.metadata.source`
     * becomes `` proposition.`metadata.source` ``. Plain fields and relationship aliases are untouched.
     * The parameter name is still derived from the raw path, so bindings stay aligned.
     */
    private fun renderPropertyPath(propertyPath: String): String {
        val dot = propertyPath.indexOf('.')
        if (dot < 0) return propertyPath
        val alias = propertyPath.substring(0, dot)
        val property = propertyPath.substring(dot + 1)
        return if (property.contains('.')) "$alias.`$property`" else propertyPath
    }

    /**
     * Renders a relationship predicate as a list predicate over the **already-projected** relationship
     * collection (the vector-search path), e.g.
     *
     *     any(_e0 IN mentions WHERE _e0.resolvedId = $param_mentions_resolvedId_0)
     *
     * `none{}` (negate) → `NOT any(...)`. The inner predicate filters on the projected map keys
     * (`_e0.resolvedId`), not node properties, which is why it dodges the FalkorDB `vecf32`-Pointer
     * quirk — exactly like post-projection property predicates. Portable openCypher across engines.
     *
     * An empty projected collection (optional relationship with no matches) makes `any(...)` false —
     * so `any{}` excludes such roots and `none{}` includes them, both correct.
     *
     * Parameter names are derived from the original `relationship.property` path (e.g.
     * `mentions.resolvedId`), keeping them aligned with [extractBindings].
     */
    private fun buildProjectedCollectionPredicate(
        condition: WhereCondition.RelationshipCondition,
        relationship: org.drivine.model.RelationshipModel,
        startIndex: Int,
        ecCounter: AtomicInteger,
    ): String {
        val collectionAlias = relationship.fieldName // the projected list, e.g. "mentions"
        val elemVar = "_e${ecCounter.getAndIncrement()}"

        var paramIndex = startIndex
        val inner = condition.targetConditions.joinToString(" AND ") { targetCondition ->
            when (targetCondition) {
                is WhereCondition.PropertyCondition -> {
                    val lhs = "$elemVar.${targetCondition.propertyPath.substringAfter(".")}"
                    val rendered = buildPropertyConditionWithLhs(targetCondition, paramIndex, lhs)
                    if (targetCondition.operator != ComparisonOperator.IS_NULL &&
                        targetCondition.operator != ComparisonOperator.IS_NOT_NULL) {
                        paramIndex++
                    }
                    rendered
                }
                else -> throw UnsupportedOperationException(
                    "Only property predicates are supported inside any{}/none{} on the vector-search path; " +
                    "got ${targetCondition::class.simpleName} for relationship '${condition.relationshipName}'."
                )
            }
        }

        val quantifier = "any($elemVar IN $collectionAlias WHERE $inner)"
        return if (condition.negate) "NOT $quantifier" else quantifier
    }

    /**
     * Like [buildPropertyCondition], but renders against an explicit left-hand side (e.g. a list
     * element `_e0.resolvedId`) while keeping the parameter name derived from the original property
     * path, so bindings stay aligned with [extractBindings].
     */
    private fun buildPropertyConditionWithLhs(condition: WhereCondition.PropertyCondition, index: Int, lhs: String): String {
        return when (condition.operator) {
            ComparisonOperator.IS_NULL,
            ComparisonOperator.IS_NOT_NULL -> "$lhs ${condition.operator.cypherOperator}"
            else -> {
                val paramName = generateParamName(condition.propertyPath, index)
                "$lhs ${condition.operator.cypherOperator} \$$paramName"
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
        startIndex: Int,
        grammar: CypherGrammar,
        ecCounter: AtomicInteger = AtomicInteger(0),
        prologs: MutableList<String> = mutableListOf(),
        bridgeVars: MutableList<String> = mutableListOf(),
        projectedCollectionMode: Boolean = false,
    ): String {
        requireNotNull(viewModel) {
            "GraphViewModel is required for relationship filtering. " +
            "This is likely a bug - relationship conditions should only be generated for GraphViews."
        }

        // Find the relationship metadata. A relationship not declared by the view is not projected,
        // so it can be neither traversed (load path) nor list-filtered (vector path) — a clear error.
        val relationship = viewModel.relationships.find { it.fieldName == condition.relationshipName }
            ?: throw IllegalArgumentException(
                "Relationship '${condition.relationshipName}' not found in ${viewModel.className}. " +
                "Available relationships: ${viewModel.relationships.map { it.fieldName }}"
            )

        // Vector path: filter the already-projected relationship collection in place, rather than an
        // EXISTS subquery over the root node (which the vector path has projected to a map).
        if (projectedCollectionMode) {
            return buildProjectedCollectionPredicate(condition, relationship, startIndex, ecCounter)
        }

        // Get the root fragment alias (usually the field name)
        val rootAlias = viewModel.rootFragment.fieldName

        // Get the relationship target alias (usually the relationship field name)
        val targetAlias = relationship.fieldName

        // `none { }` negates the existence check. NOT-wrapping the positive inline condition is
        // portable: NOT EXISTS { } on Neo4j, NOT (_ec > 0) / NOT (size([...]) > 0) elsewhere.
        fun finalize(inlineCondition: String): String =
            if (condition.negate) "NOT ($inlineCondition)" else inlineCondition

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
                        val result = buildOrCondition(targetCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars)
                        paramIndex += countParameters(targetCondition.conditions)
                        result
                    }
                    is WhereCondition.ListMembershipCondition -> throw UnsupportedOperationException(
                        "hasItem (list-membership) is not supported inside a relationship any{}/none{} block; " +
                        "use it as a top-level root predicate."
                    )
                }
            }
            " WHERE $whereClauses"
        } else {
            ""
        }

        // Build nested relationship conditions
        // For openCypher: flatten into compound MATCH pattern (no nested EXISTS)
        // For Neo4j: nest EXISTS { } inside EXISTS { }
        val nestedWhere = if (nestedConditions.isNotEmpty()) {
            val targetViewModel = try {
                GraphViewModel.from(relationship.elementType)
            } catch (e: Exception) {
                null
            }

            if (targetViewModel != null) {
                var paramIndex = startIndex + directConditions.size

                if (grammar is OpenCypherGrammar) {
                    // Flatten: extend the relationship pattern with nested hops
                    // and add all conditions to a single WHERE
                    val compoundParts = mutableListOf<String>()
                    val nestedWhereClauses = mutableListOf<String>()

                    nestedConditions.entries.forEach { (nestedRelName, nestedConds) ->
                        val nestedRel = targetViewModel.relationships.find { it.fieldName == nestedRelName }
                            ?: throw IllegalArgumentException("Nested relationship '$nestedRelName' not found")

                        val nestedAlias = "${targetAlias}_${nestedRelName}"
                        val nestedDirection = when (nestedRel.direction) {
                            org.drivine.annotation.Direction.OUTGOING -> "-[:${nestedRel.type}]->"
                            org.drivine.annotation.Direction.INCOMING -> "<-[:${nestedRel.type}]-"
                            org.drivine.annotation.Direction.UNDIRECTED -> "-[:${nestedRel.type}]-"
                        }
                        compoundParts.add("${nestedDirection}($nestedAlias)")

                        nestedConds.forEach { cond ->
                            nestedWhereClauses.add(buildPropertyCondition(cond, paramIndex))
                            paramIndex++
                        }
                    }

                    // Build compound pattern: (rootAlias)-[:REL]->(target)-[:NESTED_REL]->(nestedTarget)
                    val compoundPattern = relationshipPattern + compoundParts.joinToString("")
                    val allConditions = mutableListOf<String>()
                    if (directWhere.isNotEmpty()) allConditions.add(directWhere.removePrefix(" WHERE "))
                    allConditions.addAll(nestedWhereClauses)

                    val result = grammar.filteredExistenceCheck(
                        compoundPattern,
                        allConditions.joinToString(" AND "),
                        ecCounter.getAndIncrement()
                    )
                    result.prolog?.let { prologs.add(it) }
                    bridgeVars.addAll(result.bridgeVariables)
                    return finalize(result.inlineCondition)
                } else {
                    // Neo4j: use nested EXISTS { } (original behavior)
                    nestedConditions.entries.joinToString(" AND ") { (nestedRelName, nestedConds) ->
                        buildNestedRelationshipCondition(
                            parentAlias = targetAlias,
                            nestedRelationshipName = nestedRelName,
                            conditions = nestedConds,
                            targetViewModel = targetViewModel,
                            startIndex = paramIndex,
                            grammar = grammar,
                            ecCounter = ecCounter,
                            prologs = prologs,
                            bridgeVars = bridgeVars,
                        ).also {
                            paramIndex += nestedConds.size
                        }
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

        // Combine direct and nested WHERE clauses (Neo4j path only — openCypher returns early above)
        val combinedWhere = when {
            directWhere.isNotEmpty() && nestedWhere.isNotEmpty() -> "$directWhere AND $nestedWhere"
            directWhere.isNotEmpty() -> directWhere
            nestedWhere.isNotEmpty() -> " WHERE $nestedWhere"
            else -> ""
        }

        if (combinedWhere.isNotEmpty()) {
            val result = grammar.filteredExistenceCheck(
                relationshipPattern,
                combinedWhere.removePrefix(" WHERE "),
                ecCounter.getAndIncrement()
            )
            result.prolog?.let { prologs.add(it) }
            bridgeVars.addAll(result.bridgeVariables)
            return finalize(result.inlineCondition)
        } else {
            val direction = when (relationship.direction) {
                org.drivine.annotation.Direction.OUTGOING -> "-[:${relationship.type}]->"
                org.drivine.annotation.Direction.INCOMING -> "<-[:${relationship.type}]-"
                org.drivine.annotation.Direction.UNDIRECTED -> "-[:${relationship.type}]-"
            }
            return finalize(grammar.existenceCheck(rootAlias, direction, ""))
        }
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
        startIndex: Int,
        grammar: CypherGrammar = Neo4j5Grammar(ApocSortMapsEmitter()),
        ecCounter: AtomicInteger = AtomicInteger(0),
        prologs: MutableList<String> = mutableListOf(),
        bridgeVars: MutableList<String> = mutableListOf(),
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

        val result = grammar.filteredExistenceCheck(relationshipPattern, whereClauses, ecCounter.getAndIncrement())
        result.prolog?.let { prologs.add(it) }
        bridgeVars.addAll(result.bridgeVariables)
        return result.inlineCondition
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
        startIndex: Int,
        grammar: CypherGrammar = Neo4j5Grammar(ApocSortMapsEmitter()),
        ecCounter: AtomicInteger = AtomicInteger(0),
        prologs: MutableList<String> = mutableListOf(),
        bridgeVars: MutableList<String> = mutableListOf(),
        projectedCollectionMode: Boolean = false,
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

                    // IS NULL / IS NOT NULL render no $param, so they must not consume a parameter
                    // index here — the same rule buildWhereClause and extractBindings already apply.
                    // Incrementing for them desyncs the rendered Cypher from the extracted bindings.
                    val consumesParam = subCondition.operator != ComparisonOperator.IS_NULL &&
                        subCondition.operator != ComparisonOperator.IS_NOT_NULL

                    if (baseAlias in relationshipNames) {
                        // Convert to RelationshipCondition on the fly
                        val relCondition = WhereCondition.RelationshipCondition(
                            relationshipName = baseAlias,
                            targetConditions = listOf(subCondition)
                        )
                        val result = buildRelationshipCondition(relCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                        if (consumesParam) paramIndex++
                        result
                    } else {
                        val result = buildPropertyCondition(subCondition, paramIndex)
                        if (consumesParam) paramIndex++
                        result
                    }
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(subCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += subCondition.targetConditions.size
                    result
                }
                is WhereCondition.LabelCondition -> {
                    if (subCondition.alias in relationshipNames) {
                        val relCondition = WhereCondition.RelationshipCondition(
                            relationshipName = subCondition.alias,
                            targetConditions = listOf(subCondition)
                        )
                        buildRelationshipCondition(relCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    } else {
                        buildLabelCondition(subCondition)
                    }
                }
                is WhereCondition.OrCondition -> {
                    val result = buildOrCondition(subCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += countParameters(subCondition.conditions)
                    result
                }
                is WhereCondition.ListMembershipCondition -> {
                    val result = buildListMembershipCondition(subCondition, paramIndex)
                    paramIndex++
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
                is WhereCondition.PropertyCondition ->
                    // IS NULL / IS NOT NULL bind no value, so they add no parameter to the OR's span.
                    if (condition.operator == ComparisonOperator.IS_NULL ||
                        condition.operator == ComparisonOperator.IS_NOT_NULL
                    ) 0 else 1
                is WhereCondition.RelationshipCondition -> condition.targetConditions.size
                is WhereCondition.LabelCondition -> 0  // Label conditions don't have parameters
                is WhereCondition.OrCondition -> countParameters(condition.conditions)
                is WhereCondition.ListMembershipCondition -> 1
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