package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.FragmentQueryBuilder
import org.drivine.query.projectedKey
import org.drivine.query.projectedKeyOrNull
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.schema.SchemaGrammar
import java.util.IdentityHashMap
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
     *   post-projection. A property is then read by the key it has in the projected map, which for a
     *   `@GraphProperty` or `@NodeStamp` field is the field name and not the stored name. See
     *   [buildProjectedCollectionPredicate] and [renderProjectedPath].
     */
    fun buildWhereClause(
        conditions: List<WhereCondition>,
        viewModel: GraphViewModel? = null,
        grammar: CypherGrammar = Neo4j5Grammar(ApocSortMapsEmitter()),
        projectedCollectionMode: Boolean = false,
    ): WhereClauseResult = buildWhereClause(conditions, viewModel, grammar, projectedCollectionMode, startIndex = 0)

    /**
     * [buildWhereClause] for a statement that holds several predicates, each written by a call of its
     * own: the parameters are numbered from [startIndex], and [parameterCount] says how far the next
     * predicate's are numbered on. Numbered apart, two predicates on one property bind two parameters.
     */
    internal fun buildWhereClause(
        conditions: List<WhereCondition>,
        viewModel: GraphViewModel?,
        grammar: CypherGrammar,
        projectedCollectionMode: Boolean,
        startIndex: Int,
    ): WhereClauseResult {
        val grouped = groupConditionsByAlias(conditions, viewModel)
        val prologs = mutableListOf<String>()
        val bridgeVars = mutableListOf<String>()
        val ecCounter = AtomicInteger(0)

        val whereClause = renderConditionList(
            grouped, viewModel, startIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode,
        )

        return WhereClauseResult(
            whereClause = whereClause.ifEmpty { null },
            prologs = prologs,
            bridgeVariables = bridgeVars,
        )
    }

    /**
     * Renders a list of (already-grouped) conditions as Cypher joined by [separator], threading a running
     * parameter index from [startIndex]. This is the shared dispatch loop used by [buildWhereClause] at
     * the top level, by [buildNotCondition] for a negated sub-expression and by [buildOrCondition] for
     * the branches of an `OR` — so parameter numbering stays aligned with [extractBindings] however the
     * conditions are nested, and a path is read as a relationship's by one rule in all three.
     */
    private fun renderConditionList(
        conditions: List<WhereCondition>,
        viewModel: GraphViewModel?,
        startIndex: Int,
        grammar: CypherGrammar,
        ecCounter: AtomicInteger,
        prologs: MutableList<String>,
        bridgeVars: MutableList<String>,
        projectedCollectionMode: Boolean,
        separator: String = " AND ",
    ): String {
        val relationshipNames = viewModel?.relationships?.map { it.fieldName }?.toSet() ?: emptySet()
        var paramIndex = startIndex
        return conditions.joinToString(separator) { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    // At the top level a property of a relationship's target has been grouped into a
                    // relationship condition already. Inside a `not { }` or an `or` it has not, and it
                    // is one there as well: the target is no variable of the statement. In an `or`
                    // each branch is a predicate on the relationship by itself.
                    val relationship = relationshipOf(condition.propertyPath, relationshipNames)
                    val result = if (relationship != null) {
                        val relCondition = WhereCondition.RelationshipCondition(relationship, listOf(condition))
                        buildRelationshipCondition(relCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    } else {
                        val lhs = rootPath(condition.propertyPath, viewModel, projectedCollectionMode)
                        buildPropertyConditionWithLhs(condition, paramIndex, lhs)
                    }
                    if (condition.operator != ComparisonOperator.IS_NULL &&
                        condition.operator != ComparisonOperator.IS_NOT_NULL) {
                        paramIndex++
                    }
                    result
                }
                is WhereCondition.RelationshipCondition -> {
                    val result = buildRelationshipCondition(condition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += countParameters(condition.targetConditions)
                    result
                }
                is WhereCondition.LabelCondition -> {
                    if (condition.alias in relationshipNames) {
                        val relCondition = WhereCondition.RelationshipCondition(condition.alias, listOf(condition))
                        buildRelationshipCondition(relCondition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    } else {
                        buildLabelCondition(condition)
                    }
                }
                is WhereCondition.OrCondition -> {
                    val result = buildOrCondition(condition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += countParameters(condition.conditions)
                    result
                }
                is WhereCondition.ListMembershipCondition -> {
                    val rhs = rootPath(condition.propertyPath, viewModel, projectedCollectionMode)
                    val result = buildListMembershipCondition(condition, paramIndex, rhs)
                    paramIndex++
                    result
                }
                is WhereCondition.NotCondition -> {
                    val result = buildNotCondition(condition, viewModel, paramIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode)
                    paramIndex += countParameters(condition.conditions)
                    result
                }
                is WhereCondition.AnyLabelCondition -> {
                    val result = buildAnyLabelCondition(condition, paramIndex)
                    paramIndex++
                    result
                }
            }
        }
    }

    /**
     * The relationship of the view that [propertyPath] reads a target of — `assignedTo` for
     * `assignedTo.name`, and for `assignedTo_worksFor.name`, a relationship of the target — or null
     * when the path is the root's.
     */
    private fun relationshipOf(propertyPath: String, relationshipNames: Set<String>): String? =
        relationshipNamedFirst(propertyPath.substringBefore("."), relationshipNames)

    /**
     * The relationship of [relationshipNames] that [alias] names first: the longest that is the alias,
     * or that it begins with before an `_`, as a relationship's name may hold an underscore. Null when
     * none is.
     */
    internal fun relationshipNamedFirst(alias: String, relationshipNames: Collection<String>): String? =
        relationshipNames.filter { alias == it || alias.startsWith("${it}_") }.maxByOrNull { it.length }

    /**
     * `NOT ( … )` — renders the (raw, un-regrouped) inner conditions AND-joined, then negates. A
     * property of a relationship's target is rendered as a predicate on the relationship, each by
     * itself, so the parameters keep the order the conditions were written in.
     */
    private fun buildNotCondition(
        condition: WhereCondition.NotCondition,
        viewModel: GraphViewModel?,
        startIndex: Int,
        grammar: CypherGrammar,
        ecCounter: AtomicInteger,
        prologs: MutableList<String>,
        bridgeVars: MutableList<String>,
        projectedCollectionMode: Boolean,
    ): String {
        val inner = renderConditionList(
            condition.conditions, viewModel, startIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode,
        )
        return "NOT ($inner)"
    }

    /** `ANY(l IN labels(alias) WHERE l IN $p)` — label membership matching **any** of the labels. */
    private fun buildAnyLabelCondition(condition: WhereCondition.AnyLabelCondition, index: Int): String {
        val paramName = generateParamName("${condition.alias}.labels", index)
        return "ANY(_lbl IN labels(${condition.alias}) WHERE _lbl IN \$$paramName)"
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

                    // A relationship target's property ("assignedTo.name"), or a property of a view
                    // nested below it ("raisedBy_worksFor.name"), is grouped under that relationship.
                    val relationship = relationshipNamedFirst(alias, relationshipNames)
                    if (relationship != null) {
                        relationshipConditions.getOrPut(relationship) { mutableListOf() }.add(condition)
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
                is WhereCondition.NotCondition -> {
                    // Negation wraps its own sub-expression; keep it whole (buildNotCondition renders it).
                    grouped.add(condition)
                }
                is WhereCondition.AnyLabelCondition -> {
                    // Root any-label predicate; keep in place.
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
    fun processOrders(orders: List<OrderTerm>, relationshipNames: Set<String> = emptySet()): OrderClauseResult =
        processOrders(orders, relationshipNames, viewModel = null)

    /**
     * As [processOrders], for the load of a view, written for Neo4j 5 as that form is: a caller that
     * knows its engine passes the grammar to the form that takes one.
     */
    fun processOrders(orders: List<OrderTerm>, relationshipNames: Set<String>, viewModel: GraphViewModel?): OrderClauseResult =
        processOrders(orders, relationshipNames, viewModel, Neo4j5Grammar(ApocSortMapsEmitter()))

    /**
     * As [processOrders], for the load of a view. A view's `ORDER BY` follows its projection, where
     * the root alias is the projected map, so a root order is rendered by the key that map has for
     * the property (see [renderProjectedPath]). [OrderClauseResult.rootOrders] keeps the stored
     * names, which is what a keyset predicate on the node needs.
     *
     * A root order that places its nulls is two terms: the test for null, then the value. An order
     * of a relationship collection that places them is refused.
     *
     * An order by an expression orders the roots, and is written by [CypherOrderExpressions] with
     * its values bound, in [OrderClauseResult.bindings]. It may read only the root's properties: the
     * view's root field, or on a fragment its alias, [FragmentQueryBuilder.NODE_ALIAS].
     *
     * @param viewModel The view being loaded, or null when the load is of a fragment
     * @param grammar The engine's grammar, which says what of an expression it can write
     * @throws IllegalArgumentException where an expression reads a property of a node other than the root
     * @throws UnsupportedOrderFunctionException where an expression asks for what the engine cannot write
     */
    fun processOrders(
        orders: List<OrderTerm>,
        relationshipNames: Set<String>,
        viewModel: GraphViewModel?,
        grammar: CypherGrammar,
    ): OrderClauseResult {
        val (collectionSorts, rootOrders) = splitOrders(orders, relationshipNames)
        val rootAlias = viewModel?.rootFragment?.fieldName ?: FragmentQueryBuilder.NODE_ALIAS
        val expressions = CypherOrderExpressions(grammar, rootAlias) { rootPath(it, viewModel, projected = true) }
        val orderByClause = rootOrders.takeIf { it.isNotEmpty() }?.joinToString(", ") { order ->
            val term = when (order) {
                is OrderSpec -> rootPath(order.propertyPath, viewModel, projected = true)
                is ExpressionOrder -> expressions.write(order.expression)
            }
            // Cypher has no NULLS FIRST / LAST. Ordering by the test for null before the value
            // does the same: false sorts before true, so `IS NULL` leaves the null rows last.
            val nulls = when (order.nulls) {
                NullPlacement.FIRST -> "$term IS NOT NULL, "
                NullPlacement.LAST -> "$term IS NULL, "
                null -> ""
            }
            "$nulls$term ${order.direction.name}"
        }
        return OrderClauseResult(orderByClause, collectionSorts, rootOrders, expressions.bindings)
    }

    /**
     * [orders] parted into the sorts of relationship collections and the orders of the roots. A
     * property order whose alias is a relationship field, or holds an underscore (`field_nested`, a
     * nested collection), sorts a collection, and is refused where it places its nulls; every other
     * order, an order by an expression among them, orders the roots. Shared with the Oracle backend.
     */
    internal fun splitOrders(orders: List<OrderTerm>, relationshipNames: Set<String>): SplitOrders {
        val (sorts, roots) = orders.partition { order ->
            order is OrderSpec && order.propertyPath.substringBefore('.').let { it in relationshipNames || '_' in it }
        }
        val collectionSorts = sorts.filterIsInstance<OrderSpec>().map { order ->
            order.requireNoNullPlacement("in the order of a relationship collection")
            CollectionSortSpec(
                relationshipPath = order.propertyPath.substringBefore('.'),
                propertyName = order.propertyPath.substringAfter('.'),
                ascending = order.direction == OrderDirection.ASC,
            )
        }
        return SplitOrders(collectionSorts, roots)
    }

    /**
     * Extracts parameter bindings from conditions.
     * Converts condition values into parameter map for binding.
     *
     * @param conditions List of filter conditions
     * @param viewModel Optional GraphViewModel for relationship metadata (needed to match buildWhereClause ordering)
     * @return Map of parameter names to values
     */
    fun extractBindings(conditions: List<WhereCondition>, viewModel: GraphViewModel? = null): Map<String, Any?> =
        extractBindings(conditions, viewModel, startIndex = 0)

    /** The number of parameters [conditions] bind: how far the predicate after them is numbered on. */
    internal fun parameterCount(conditions: List<WhereCondition>, viewModel: GraphViewModel? = null): Int =
        countParameters(groupConditionsByAlias(conditions, viewModel))

    /** [extractBindings] for a predicate whose parameters are numbered from [startIndex], as [buildWhereClause] numbers them. */
    internal fun extractBindings(conditions: List<WhereCondition>, viewModel: GraphViewModel?, startIndex: Int): Map<String, Any?> {
        // IMPORTANT: Must group conditions the same way as buildWhereClause to maintain parameter ordering
        val grouped = groupConditionsByAlias(conditions, viewModel)

        val bindings = mutableMapOf<String, Any?>()
        var paramIndex = startIndex

        fun extractRecursive(conds: List<WhereCondition>) {
            conds.forEach { condition ->
                when (condition) {
                    is WhereCondition.PropertyCondition -> {
                        // Skip IS NULL and IS NOT NULL operators as they don't have parameters
                        if (condition.operator != ComparisonOperator.IS_NULL &&
                            condition.operator != ComparisonOperator.IS_NOT_NULL) {
                            val paramName = generateParamName(condition.propertyPath, paramIndex)
                            // The case-insensitive operators lower-case both sides: the LHS via
                            // toLower(...) in the Cypher, the RHS by lower-casing the bound value here.
                            bindings[paramName] = if (
                                condition.operator == ComparisonOperator.CONTAINS_IGNORE_CASE ||
                                condition.operator == ComparisonOperator.EQUALS_IGNORE_CASE
                            ) {
                                condition.value?.toString()?.lowercase()
                            } else {
                                convertToNeo4jValue(condition.value)
                            }
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
                    is WhereCondition.NotCondition -> {
                        // Raw (un-regrouped) walk — mirrors buildNotCondition's rendering order.
                        extractRecursive(condition.conditions)
                    }
                    is WhereCondition.AnyLabelCondition -> {
                        val paramName = generateParamName("${condition.alias}.labels", paramIndex)
                        bindings[paramName] = condition.labels
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
            ComparisonOperator.NOT_IN -> {
                // NOT membership — a leading NOT over the IN form.
                val paramName = generateParamName(condition.propertyPath, index)
                "NOT $lhs IN \$$paramName"
            }
            ComparisonOperator.HAS_ELEMENT -> {
                // Reversed membership — the bound value on the left, the list-valued property on the
                // right (the dynamic twin of hasItem / ListMembershipCondition).
                val paramName = generateParamName(condition.propertyPath, index)
                "\$$paramName IN $lhs"
            }
            ComparisonOperator.CONTAINS_IGNORE_CASE -> {
                // Case-insensitive contains — the bound value is lower-cased in extractBindings.
                val paramName = generateParamName(condition.propertyPath, index)
                "toLower($lhs) CONTAINS \$$paramName"
            }
            ComparisonOperator.EQUALS_IGNORE_CASE -> {
                // Case-insensitive equals — the bound value is lower-cased in extractBindings.
                val paramName = generateParamName(condition.propertyPath, index)
                "toLower($lhs) = \$$paramName"
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
    private fun buildListMembershipCondition(
        condition: WhereCondition.ListMembershipCondition,
        index: Int,
        rhs: String = renderPropertyPath(condition.propertyPath),
    ): String {
        val paramName = generateParamName(condition.propertyPath, index)
        return "\$$paramName IN $rhs"
    }

    /**
     * Renders an `alias.property` path wherever it enters a statement — a condition, an `ORDER BY`, a
     * keyset seek — backtick-quoting the property segment when it is not a plain identifier: a
     * `@PropertyBag` key like `proposition.metadata.source` becomes `` proposition.`metadata.source` ``,
     * and a `@NodeStamp` field `` note.`__drivine.stamp` ``. Unquoted, `note.__drivine.stamp` reads the
     * `stamp` of a `__drivine` that no node has, which is null for every row. Plain fields and
     * relationship aliases are untouched. The parameter name is still derived from the raw path, so
     * bindings stay aligned.
     */
    internal fun renderPropertyPath(propertyPath: String): String {
        val dot = propertyPath.indexOf('.')
        if (dot < 0) return propertyPath
        return "${propertyPath.substring(0, dot)}.${quoteProperty(propertyPath.substring(dot + 1))}"
    }

    /**
     * A root property as a top-level predicate reads it: from the projected map when the predicate
     * runs after the projection ([projected]), and from the node otherwise.
     */
    private fun rootPath(propertyPath: String, viewModel: GraphViewModel?, projected: Boolean): String =
        if (projected && viewModel != null) renderProjectedPath(propertyPath, viewModel) else renderPropertyPath(propertyPath)

    /**
     * Renders an `alias.property` path of [viewModel]'s root where it is read **after** the view's
     * projection — an `ORDER BY`, or the filter of a scored search. By then the root alias is the
     * projected map, keyed by field name, so a `@GraphProperty` or `@NodeStamp` field is read by its
     * field name: `claim.stamp`, where [renderPropertyPath] writes `` claim.`__drivine.stamp` `` for a
     * predicate on the node. A root projected with `.*` keeps its stored names, and so does a path
     * that is not the root's.
     *
     * @throws IllegalArgumentException when the root is projected field by field and no field is
     *   stored as the property: the map does not hold it, and the statement would read null
     */
    internal fun renderProjectedPath(propertyPath: String, viewModel: GraphViewModel): String {
        val dot = propertyPath.indexOf('.')
        val root = viewModel.rootFragment
        if (dot < 0 || propertyPath.substring(0, dot) != root.fieldName) return renderPropertyPath(propertyPath)
        val property = propertyPath.substring(dot + 1)
        val key = projectedKeyOrNull(root.fragmentType, property) ?: throw IllegalArgumentException(
            "${viewModel.className} cannot be ordered, paged or filtered in a scored search by '$propertyPath': " +
                "${root.fragmentType.simpleName} declares no field stored as '$property', so the view does not project " +
                "it, and what is read after the projection would be null for every row. Declare the property as a " +
                "field of ${root.fragmentType.simpleName}; a where { } of a plain load reads the node and needs no field."
        )
        return "${root.fieldName}.${quoteProperty(key)}"
    }

    /**
     * A property name as it is written after an alias. A name that is not a plain identifier (a
     * `@PropertyBag` key, the node stamp, or a dynamic `property(path)` holding a dot, a space, a
     * hyphen or anything else) is backtick-quoted; any backtick in it is doubled, as Cypher escapes
     * them, and so is the Unicode escape of one, which Neo4j reads as a backtick: a runtime-supplied
     * key can't break out of the quotes. The rule is the one schema DDL spells names by, so a property
     * is written the same way where it is indexed and where it is read.
     *
     * @throws IllegalArgumentException for an empty name, which no engine reads as a property
     */
    internal fun quoteProperty(property: String): String {
        require(property.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return SchemaGrammar.identifier(property)
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
     * A `@GraphProperty` or `@NodeStamp` field of a target projected field by field is keyed by its
     * field name, so that is the key the predicate reads (see [projectedKey]).
     *
     * The element is read as the view projected it:
     * - a fragment's properties are the element's own keys: `_e0.resolvedId`;
     * - a nested view holds its root one map down, under the root's field name: `_e0.entry.title`;
     * - a relationship of a nested view is a key beside that root, read as a list of its own —
     *   `any(_e1 IN _e0.markers WHERE _e1.name = $p)`, the conditions on one relationship holding of
     *   one of its targets — or, when it holds one node, as that node's map: `_e0.owner.name`.
     *
     * A relationship that holds one node is projected as that node's map, or null, and no list, so
     * it is read as a list of the one, or of none: `any(_e0 IN CASE WHEN assignee IS NULL THEN [] ELSE
     * [assignee] END WHERE …)`.
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
        val nestedView = relationship.elementType.takeIf { it.isAnnotationPresent(org.drivine.annotation.GraphView::class.java) }
            ?.let { GraphViewModel.from(it) }

        // Where a property of an element of [type] is read from [element]: one map down, in the
        // root, when the element is a view.
        fun keyOf(element: String, type: Class<*>, property: String): String {
            val view = type.takeIf { it.isAnnotationPresent(org.drivine.annotation.GraphView::class.java) }?.let { GraphViewModel.from(it) }
            return if (view == null) {
                "$element.${quoteProperty(projectedKey(type, property))}"
            } else {
                "$element.${quoteProperty(view.rootFragment.fieldName)}.${quoteProperty(projectedKey(view.rootFragment.fragmentType, property))}"
            }
        }

        // The conditions on a collection inside the element, by that collection: they hold of one
        // of its targets, and so are rendered together, where the first of them was written.
        val nestedParts = linkedMapOf<String, MutableList<String>>()
        val parts = mutableListOf<Any>() // a rendered condition, or the NestedSlot of a collection's conditions
        var paramIndex = startIndex
        condition.targetConditions.forEach { targetCondition ->
            if (targetCondition !is WhereCondition.PropertyCondition) {
                throw UnsupportedOperationException(
                    "Only property predicates are supported inside any{}/none{} on the vector-search path; " +
                    "got ${targetCondition::class.simpleName} for relationship '${condition.relationshipName}'."
                )
            }
            val alias = targetCondition.propertyPath.substringBefore(".")
            val property = targetCondition.propertyPath.substringAfter(".")
            val index = paramIndex
            if (targetCondition.operator != ComparisonOperator.IS_NULL &&
                targetCondition.operator != ComparisonOperator.IS_NOT_NULL) {
                paramIndex++
            }
            if (alias == collectionAlias || !alias.startsWith("${collectionAlias}_")) {
                // The element is the target as the view projected it, so it is read by projected key.
                parts.add(buildPropertyConditionWithLhs(targetCondition, index, keyOf(elemVar, relationship.elementType, property)))
                return@forEach
            }
            val nestedName = alias.substringAfter("${collectionAlias}_")
            val nestedRel = nestedView?.relationships?.find { it.fieldName == nestedName } ?: throw IllegalArgumentException(
                "Cannot filter on '${targetCondition.propertyPath}': " + if (nestedView == null) {
                    "${relationship.elementType.simpleName} is not a @GraphView, so '${condition.relationshipName}' has no relationship '$nestedName'."
                } else {
                    "nested relationship '$nestedName' not found in ${nestedView.className}. " +
                        "Available relationships: ${nestedView.relationships.map { it.fieldName }}"
                }
            )
            val nestedSource = "$elemVar.${quoteProperty(nestedRel.fieldName)}"
            if (nestedRel.isCollection) {
                val slot = "$nestedSource/_n${nestedRel.fieldName}"
                val nestedVar = nestedParts.keys.indexOf(slot).takeIf { it >= 0 } ?: nestedParts.size
                val nestedElem = "${elemVar}_$nestedVar"
                if (slot !in nestedParts) {
                    nestedParts[slot] = mutableListOf()
                    parts.add(NestedSlot(slot, nestedSource, nestedElem))
                }
                nestedParts.getValue(slot).add(
                    buildPropertyConditionWithLhs(targetCondition, index, keyOf(nestedElem, nestedRel.elementType, property))
                )
            } else {
                parts.add(buildPropertyConditionWithLhs(targetCondition, index, keyOf(nestedSource, nestedRel.elementType, property)))
            }
        }

        val inner = parts.joinToString(" AND ") { part ->
            when (part) {
                is NestedSlot -> "any(${part.element} IN ${part.source} WHERE ${nestedParts.getValue(part.slot).joinToString(" AND ")})"
                else -> part.toString()
            }
        }

        // The one node as a list of one, and of none when it is absent: `any` over a null element
        // is null, which `none { }` would negate to null and so drop the root.
        val source = if (relationship.isCollection) {
            collectionAlias
        } else {
            "CASE WHEN $collectionAlias IS NULL THEN [] ELSE [$collectionAlias] END"
        }
        val quantifier = "any($elemVar IN $source WHERE $inner)"
        return if (condition.negate) "NOT $quantifier" else quantifier
    }

    /** Where the conditions on one collection inside a projected element are rendered: see [buildProjectedCollectionPredicate]. */
    private class NestedSlot(val slot: String, val source: String, val element: String)

    /**
     * Like [buildPropertyCondition], but renders against an explicit left-hand side (e.g. a list
     * element `_e0.resolvedId`) while keeping the parameter name derived from the original property
     * path, so bindings stay aligned with [extractBindings].
     */
    private fun buildPropertyConditionWithLhs(condition: WhereCondition.PropertyCondition, index: Int, lhs: String): String {
        return when (condition.operator) {
            ComparisonOperator.IS_NULL,
            ComparisonOperator.IS_NOT_NULL -> "$lhs ${condition.operator.cypherOperator}"
            ComparisonOperator.NOT_IN -> "NOT $lhs IN \$${generateParamName(condition.propertyPath, index)}"
            ComparisonOperator.HAS_ELEMENT -> "\$${generateParamName(condition.propertyPath, index)} IN $lhs"
            ComparisonOperator.CONTAINS_IGNORE_CASE -> "toLower($lhs) CONTAINS \$${generateParamName(condition.propertyPath, index)}"
            ComparisonOperator.EQUALS_IGNORE_CASE -> "toLower($lhs) = \$${generateParamName(condition.propertyPath, index)}"
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
        val targetViewModel = runCatching { GraphViewModel.from(relationship.elementType) }.getOrNull()
        val (directConditions, nestedConditions) =
            separateNestedConditions(condition.targetConditions, targetAlias, targetViewModel)

        // Each target condition takes the index of its place among the block's parameters, as
        // [extractBindings] walks them. The direct conditions are rendered before the nested ones
        // wherever each was written, so the index is looked up and not counted while rendering.
        val indexOf = IdentityHashMap<WhereCondition, Int>()
        var nextIndex = startIndex
        condition.targetConditions.forEach { targetCondition ->
            indexOf[targetCondition] = nextIndex
            nextIndex += countParameters(listOf(targetCondition))
        }

        // Build WHERE clauses for direct target conditions
        val directWhere = if (directConditions.isNotEmpty()) {
            val whereClauses = directConditions.joinToString(" AND ") { targetCondition ->
                when (targetCondition) {
                    is WhereCondition.PropertyCondition -> {
                        buildPropertyCondition(targetCondition, indexOf.getValue(targetCondition))
                    }
                    is WhereCondition.RelationshipCondition -> {
                        throw UnsupportedOperationException("Should not reach here - nested conditions separated")
                    }
                    is WhereCondition.LabelCondition -> {
                        // Label conditions don't use parameters
                        buildLabelCondition(targetCondition)
                    }
                    is WhereCondition.OrCondition -> {
                        buildOrCondition(targetCondition, viewModel, indexOf.getValue(targetCondition), grammar, ecCounter, prologs, bridgeVars)
                    }
                    is WhereCondition.ListMembershipCondition -> throw UnsupportedOperationException(
                        "hasItem (list-membership) is not supported inside a relationship any{}/none{} block; " +
                        "use it as a top-level root predicate."
                    )
                    is WhereCondition.NotCondition -> throw UnsupportedOperationException(
                        "not { } is not supported inside a relationship any{}/none{} block; " +
                        "use it as a top-level root predicate (or none{} to negate a relationship)."
                    )
                    is WhereCondition.AnyLabelCondition -> throw UnsupportedOperationException(
                        "hasAnyLabel() is not supported inside a relationship any{}/none{} block; " +
                        "use it as a top-level root predicate."
                    )
                }
            }
            " WHERE $whereClauses"
        } else {
            ""
        }

        // Build nested relationship conditions
        // An engine whose checks cannot nest (FalkorDB, Neptune): flatten into one statement of patterns
        // Neo4j and Memgraph: nest a check of each nested relationship inside the view's
        val nestedWhere = if (nestedConditions.isNotEmpty()) {
            if (targetViewModel != null) {
                if (!grammar.nestsFilteredExistenceChecks) {
                    // Flatten: extend the relationship pattern with nested hops, a sibling's as a path
                    // of its own from the node it shares, and add all conditions to a single WHERE
                    val compoundParts = mutableListOf<String>()
                    val branches = mutableListOf<MutableList<String>>()
                    val nestedWhereClauses = mutableListOf<String>()

                    flattenNestedConditions(targetAlias, nestedConditions, targetViewModel, indexOf, compoundParts, nestedWhereClauses, branches)

                    // Build compound pattern: (rootAlias)-[:REL]->(target)-[:A]->(target_a), (target)-[:B]->(target_b)
                    val compoundPattern = relationshipPattern + compoundParts.joinToString("") +
                        branches.joinToString("") { ", " + it.joinToString("") }
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
                    // Neo4j and Memgraph: a check of each nested relationship within the view's
                    nestedConditions.entries.joinToString(" AND ") { (nestedRelName, nestedConds) ->
                        buildNestedRelationshipCondition(
                            parentAlias = targetAlias,
                            nestedRelationshipName = nestedRelName,
                            conditions = nestedConds,
                            targetViewModel = targetViewModel,
                            indexOf = indexOf,
                            grammar = grammar,
                            ecCounter = ecCounter,
                            prologs = prologs,
                            bridgeVars = bridgeVars,
                        )
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

        // Combine direct and nested WHERE clauses (nested checks only — a flattened pattern returns early above)
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
     * - "raisedBy_worksFor_parent.name" -> nested (relationship="worksFor"), for its own level to separate
     *
     * A nested condition is keyed by the relationship of [targetViewModel] its alias names next: the
     * longest of its relationship names the rest of the alias begins with, as a relationship's name may
     * hold an underscore. With no view model, or none that matches, the rest of the alias is the name.
     */
    private fun separateNestedConditions(
        conditions: List<WhereCondition>,
        targetAlias: String,
        targetViewModel: GraphViewModel? = null,
    ): Pair<List<WhereCondition>, Map<String, List<WhereCondition.PropertyCondition>>> {
        val direct = mutableListOf<WhereCondition>()
        val nested = mutableMapOf<String, MutableList<WhereCondition.PropertyCondition>>()

        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val alias = condition.propertyPath.substringBefore(".")
                    if (alias.startsWith("${targetAlias}_")) {
                        // Nested relationship property (e.g., "raisedBy_worksFor.name")
                        val rest = alias.substringAfter("${targetAlias}_")
                        val nestedRelName = targetViewModel?.relationships
                            ?.let { relationships -> relationshipNamedFirst(rest, relationships.map { it.fieldName }) }
                            ?: rest
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
        indexOf: Map<WhereCondition, Int>,
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

        // Its own conditions, and those of the levels below it, each a check of its own within this one.
        val nestedViewModel = runCatching { GraphViewModel.from(nestedRel.elementType) }.getOrNull()
        val (direct, deeper) = separateNestedConditions(conditions, nestedAlias, nestedViewModel)
        val clauses = direct.map { buildPropertyCondition(it as WhereCondition.PropertyCondition, indexOf.getValue(it)) } +
            deeper.map { (deeperName, deeperConds) ->
                buildNestedRelationshipCondition(
                    parentAlias = nestedAlias,
                    nestedRelationshipName = deeperName,
                    conditions = deeperConds,
                    targetViewModel = nestedViewModel ?: throw notAView(nestedAlias, nestedRel),
                    indexOf = indexOf,
                    grammar = grammar,
                    ecCounter = ecCounter,
                    prologs = prologs,
                    bridgeVars = bridgeVars,
                )
            }
        val whereClauses = clauses.joinToString(" AND ")

        val result = grammar.filteredExistenceCheck(relationshipPattern, whereClauses, ecCounter.getAndIncrement())
        result.prolog?.let { prologs.add(it) }
        bridgeVars.addAll(result.bridgeVariables)
        return result.inlineCondition
    }

    /**
     * Extends a flattened relationship pattern with the nested relationships [nested] names, a hop for
     * each, and each level below them in turn, adding their conditions to [whereClauses]: the
     * flattened form of [buildNestedRelationshipCondition]'s nested checks. The first relationship
     * extends [patternParts], the path that reached [parentAlias]; each sibling after it starts a path
     * of its own from [parentAlias], added to [branches], so each leads from the node they share.
     */
    private fun flattenNestedConditions(
        parentAlias: String,
        nested: Map<String, List<WhereCondition.PropertyCondition>>,
        parentViewModel: GraphViewModel,
        indexOf: Map<WhereCondition, Int>,
        patternParts: MutableList<String>,
        whereClauses: MutableList<String>,
        branches: MutableList<MutableList<String>>,
    ) {
        nested.entries.forEachIndexed { i, (nestedRelName, nestedConds) ->
            val path = if (i == 0) patternParts else mutableListOf("($parentAlias)").also { branches.add(it) }
            val nestedRel = parentViewModel.relationships.find { it.fieldName == nestedRelName }
                ?: throw IllegalArgumentException(
                    "Nested relationship '$nestedRelName' not found in ${parentViewModel.className}. " +
                    "Available relationships: ${parentViewModel.relationships.map { it.fieldName }}"
                )
            val nestedAlias = "${parentAlias}_${nestedRelName}"
            val nestedDirection = when (nestedRel.direction) {
                org.drivine.annotation.Direction.OUTGOING -> "-[:${nestedRel.type}]->"
                org.drivine.annotation.Direction.INCOMING -> "<-[:${nestedRel.type}]-"
                org.drivine.annotation.Direction.UNDIRECTED -> "-[:${nestedRel.type}]-"
            }
            path.add("${nestedDirection}($nestedAlias)")
            val nestedViewModel = runCatching { GraphViewModel.from(nestedRel.elementType) }.getOrNull()
            val (direct, deeper) = separateNestedConditions(nestedConds, nestedAlias, nestedViewModel)
            direct.forEach { cond -> whereClauses.add(buildPropertyCondition(cond as WhereCondition.PropertyCondition, indexOf.getValue(cond))) }
            if (deeper.isNotEmpty()) {
                flattenNestedConditions(
                    nestedAlias, deeper, nestedViewModel ?: throw notAView(nestedAlias, nestedRel), indexOf, path, whereClauses, branches,
                )
            }
        }
    }

    private fun notAView(alias: String, relationship: RelationshipModel) = IllegalArgumentException(
        "Cannot filter on nested relationship '${alias}_*' because ${relationship.elementType.simpleName} is not a @GraphView"
    )

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
        grammar: CypherGrammar,
        ecCounter: AtomicInteger,
        prologs: MutableList<String>,
        bridgeVars: MutableList<String>,
        projectedCollectionMode: Boolean = false,
    ): String {
        val clauses = renderConditionList(
            condition.conditions, viewModel, startIndex, grammar, ecCounter, prologs, bridgeVars, projectedCollectionMode, separator = " OR ",
        )
        return "($clauses)"
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
                is WhereCondition.RelationshipCondition -> countParameters(condition.targetConditions)
                is WhereCondition.LabelCondition -> 0  // Label conditions don't have parameters
                is WhereCondition.OrCondition -> countParameters(condition.conditions)
                is WhereCondition.ListMembershipCondition -> 1
                is WhereCondition.NotCondition -> countParameters(condition.conditions)
                is WhereCondition.AnyLabelCondition -> 1  // binds the labels list
            }
        }
    }

    /**
     * Generates a unique parameter name for a property path.
     * Example: "issue.state" with index 0 -> "param_issue_state_0"
     *
     * Every character that cannot stand in a parameter name becomes an underscore, so a runtime key
     * holding a hyphen, a space or a backtick still names a parameter. Two paths may then share a
     * stem, as `a.b` and `a_b` always could; what tells their parameters apart is [index], the
     * parameter's position among the conditions.
     */
    private fun generateParamName(propertyPath: String, index: Int): String {
        val sanitized = propertyPath.replace(NOT_IN_A_PARAMETER_NAME, "_")
        return "param_${sanitized}_$index"
    }

    private val NOT_IN_A_PARAMETER_NAME = Regex("[^A-Za-z0-9_]")

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
    internal fun convertToNeo4jValue(value: Any?): Any? {
        return when (value) {
            is java.util.UUID -> value.toString()
            is Collection<*> -> value.map { convertToNeo4jValue(it) }
            else -> value
        }
    }
}
