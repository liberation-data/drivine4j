package org.drivine.query.dsl

/**
 * DSL for building type-safe queries for GraphViews and GraphFragments.
 *
 * Example usage:
 * ```kotlin
 * graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java) {
 *     where {
 *         this(issue.state eq "open")
 *         this(issue.id gt 1000)
 *         // TODO: Relationship filtering
 *         // assignedTo { this(it.name eq "Kent Beck") }
 *     }
 *     orderBy {
 *         this(issue.id.asc())
 *     }
 * }
 * ```
 */

/**
 * Root DSL builder for graph queries.
 * Collects filter conditions and ordering specifications.
 *
 * @param T The query object type that provides property references
 */
class GraphQuerySpec<T : Any>(private val queryObject: T) {
    internal val conditions = mutableListOf<WhereCondition>()
    internal val orders = mutableListOf<OrderSpec>()

    /**
     * Adds filter conditions using the DSL.
     * The query object is provided as the receiver, enabling syntax like:
     * where { query -> this(query.issue.state eq "open") }
     * or with receiver: where { this(issue.state eq "open") }
     */
    fun where(block: WhereBuilder<T>.() -> Unit) {
        val builder = WhereBuilder(queryObject)
        builder.block()
        conditions.addAll(builder.conditions)
    }

    /**
     * Adds ordering specifications using the DSL.
     */
    fun orderBy(block: OrderBuilder<T>.() -> Unit) {
        val builder = OrderBuilder(queryObject)
        builder.block()
        orders.addAll(builder.orders)
    }
}

/**
 * Builder for WHERE conditions.
 * Collects individual filter conditions that will be AND'd together.
 *
 * The query object is available as a property, enabling both syntaxes:
 * ```kotlin
 * where { query ->
 *     this(query.issue.state eq "open")  // Explicit parameter
 * }
 *
 * where {
 *     this(query.issue.state eq "open")  // Access via property
 * }
 * ```
 *
 * @param T The query object type that provides property references
 */
open class WhereBuilder<T : Any>(
    /**
     * The query object providing property references.
     * Can be accessed as `query.issue.state` etc.
     */
    val query: T
) {
    internal val conditions = mutableListOf<WhereCondition>()

    /**
     * Adds a single condition.
     * Usage: this(query.issue.state eq "open")
     */
    operator fun invoke(builder: PropertyConditionBuilder) {
        conditions.add(builder.condition)
    }

    /**
     * Adds multiple conditions chained with `and`.
     * Usage: this(query.issue.state eq "open" and query.issue.id gt 1000)
     */
    operator fun invoke(chain: PropertyConditionChain) {
        conditions.addAll(chain.conditions)
    }

    /**
     * Creates an OR condition - at least one of the nested conditions must be true.
     *
     * Usage:
     * ```kotlin
     * where {
     *     anyOf {
     *         this(query.issue.state eq "open")
     *         this(query.issue.state eq "reopened")
     *     }
     * }
     * ```
     *
     * Generates: (issue.state = 'open' OR issue.state = 'reopened')
     *
     * Can be combined with AND conditions:
     * ```kotlin
     * where {
     *     this(query.issue.locked eq false)  // AND
     *     anyOf {
     *         this(query.issue.state eq "open")
     *         this(query.issue.state eq "reopened")
     *     }
     * }
     * ```
     * Generates: issue.locked = false AND (issue.state = 'open' OR issue.state = 'reopened')
     */
    fun anyOf(block: WhereBuilder<T>.() -> Unit) {
        val orBuilder = WhereBuilder(query)
        orBuilder.block()
        if (orBuilder.conditions.isNotEmpty()) {
            conditions.add(WhereCondition.OrCondition(orBuilder.conditions))
        }
    }
}

/**
 * Builder for ORDER BY specifications.
 *
 * The query object is available as a property:
 * ```kotlin
 * orderBy {
 *     this(query.issue.id.asc())
 *     this(query.issue.state.desc())
 * }
 * ```
 *
 * @param T The query object type that provides property references
 */
class OrderBuilder<T : Any>(
    /**
     * The query object providing property references.
     */
    val query: T
) {
    internal val orders = mutableListOf<OrderSpec>()

    /**
     * Adds an order specification.
     * Usage: this(query.issue.id.asc())
     */
    operator fun invoke(order: OrderSpec) {
        orders.add(order)
    }
}

/**
 * Represents a WHERE condition in the query.
 * Can be a simple property condition, a relationship filter, or an OR condition.
 */
sealed class WhereCondition {
    /**
     * Filter on a root fragment property.
     * Example: issue.state eq "open"
     */
    data class PropertyCondition(
        val propertyPath: String,  // e.g., "issue.state"
        val operator: ComparisonOperator,
        val value: Any?
    ) : WhereCondition()

    /**
     * Filter on relationship target properties.
     * Example: assignedTo { it.name eq "Kent Beck" }
     */
    data class RelationshipCondition(
        val relationshipName: String,  // e.g., "assignedTo"
        val targetConditions: List<WhereCondition>
    ) : WhereCondition()

    /**
     * OR condition - at least one of the nested conditions must be true.
     * Example: anyOf { this(query.state eq "open"); this(query.state eq "reopened") }
     * Generates: (issue.state = 'open' OR issue.state = 'reopened')
     */
    data class OrCondition(
        val conditions: List<WhereCondition>
    ) : WhereCondition()
}

/**
 * Comparison operators for property conditions.
 */
enum class ComparisonOperator(val cypherOperator: String) {
    EQUALS("="),
    NOT_EQUALS("<>"),
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUAL("<="),
    IN("IN"),
    CONTAINS("CONTAINS"),
    STARTS_WITH("STARTS WITH"),
    ENDS_WITH("ENDS WITH")
}

/**
 * Represents an ORDER BY specification.
 */
data class OrderSpec(
    val propertyPath: String,  // e.g., "issue.id"
    val direction: OrderDirection
)

enum class OrderDirection {
    ASC,
    DESC
}