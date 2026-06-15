package org.drivine.query.dsl

/**
 * DSL for building type-safe queries for GraphViews and GraphFragments.
 *
 * Example usage with cleaner context parameter syntax:
 * ```kotlin
 * graphObjectManager.loadAll<RaisedAndAssignedIssue> {
 *     where {
 *         issue.state eq "open"      // Direct property access!
 *         issue.id gt 1000
 *     }
 *     orderBy {
 *         issue.id.asc()             // Direct property access!
 *     }
 * }
 * ```
 *
 * The generated context property extensions enable direct property access
 * (e.g., `issue.state` instead of `query.issue.state`), making the DSL
 * more natural and concise.
 *
 * With context parameters (Kotlin 2.2+), conditions are automatically registered,
 * eliminating the need for this() or other wrapper syntax.
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
    internal val depthOverrides = mutableMapOf<String, Int>()
    internal var limit: Int? = null
    internal var skip: Int? = null

    /**
     * Limits the result to at most [n] entities — for a `@GraphView`, [n] **roots** (relationships
     * stay fully populated), bound as `$_limit` and applied after `ORDER BY`. Pair with [orderBy] for
     * a deterministic top-N; without it the subset is an arbitrary `<= n` (standard Cypher).
     *
     * ```kotlin
     * loadAll<PropositionView> { orderBy { proposition.created.desc() }; limit(20) }
     * ```
     */
    fun limit(n: Int) {
        require(n >= 0) { "limit must be >= 0, was $n" }
        limit = n
    }

    /**
     * Skips the first [n] entities (for pagination), bound as `$_skip` and applied before [limit].
     * Combine with [orderBy] + [limit] for stable pages.
     *
     * ```kotlin
     * loadAll<PropositionView> { orderBy { proposition.created.desc() }; skip(40); limit(20) }
     * ```
     */
    fun skip(n: Int) {
        require(n >= 0) { "skip must be >= 0, was $n" }
        skip = n
    }

    /**
     * Overrides the expansion depth for a recursive relationship at query time.
     * This takes precedence over the maxDepth declared on the @GraphRelationship annotation.
     *
     * Example:
     * ```kotlin
     * graphObjectManager.loadAll<LocationHierarchy> {
     *     depth("subLocations", 5)  // Override annotation's maxDepth
     * }
     * ```
     *
     * @param relationshipName The field name of the recursive relationship
     * @param maxDepth The maximum expansion depth (0 = don't expand)
     */
    fun depth(relationshipName: String, maxDepth: Int) {
        depthOverrides[relationshipName] = maxDepth
    }

    /**
     * Adds filter conditions using the DSL.
     * With context parameters (Kotlin 2.2+), conditions automatically register themselves.
     *
     * Example:
     * ```kotlin
     * where {
     *     query.issue.state eq "open"  // Automatically registered!
     *     query.issue.id gt 1000
     * }
     * ```
     */
    fun where(block: context(WhereBuilder<T>) () -> Unit) {
        val builder = WhereBuilder(queryObject)
        block(builder)
        conditions.addAll(builder.conditions)
    }

    /**
     * Adds ordering specifications using the DSL.
     * With context parameters (Kotlin 2.2+), order specifications can be added naturally.
     *
     * Example:
     * ```kotlin
     * orderBy {
     *     query.issue.id.asc()      // Automatically registered!
     *     query.issue.state.desc()
     * }
     * ```
     */
    fun orderBy(block: context(OrderBuilder<T>) () -> Unit) {
        val builder = OrderBuilder(queryObject)
        block(builder)
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
    val queryObject: T
) {
    /**
     * The list of conditions collected by this builder.
     * Public to allow access from inline extension functions.
     */
    val conditions = mutableListOf<WhereCondition>()

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
     *         query.issue.state eq "open"
     *         query.issue.state eq "reopened"
     *     }
     * }
     * ```
     *
     * Generates: (issue.state = 'open' OR issue.state = 'reopened')
     *
     * Can be combined with AND conditions:
     * ```kotlin
     * where {
     *     query.issue.locked eq false  // AND
     *     anyOf {
     *         query.issue.state eq "open"
     *         query.issue.state eq "reopened"
     *     }
     * }
     * ```
     * Generates: issue.locked = false AND (issue.state = 'open' OR issue.state = 'reopened')
     */
    fun anyOf(block: context(WhereBuilder<T>) () -> Unit) {
        val orBuilder = WhereBuilder(queryObject)
        block(orBuilder)
        if (orBuilder.conditions.isNotEmpty()) {
            conditions.add(WhereCondition.OrCondition(orBuilder.conditions))
        }
    }
}

/**
 * Context-aware property to access the query object within a where block.
 * This is the key to the context parameters DSL - `query` becomes magically available!
 */
context(builder: WhereBuilder<T>)
val <T : Any> query: T
    get() = builder.queryObject

/**
 * Context-aware function to create OR conditions within a where block.
 */
context(builder: WhereBuilder<T>)
fun <T : Any> anyOf(block: context(WhereBuilder<T>) () -> Unit) {
    builder.anyOf(block)
}

/**
 * Quantified predicate over a relationship: **at least one** related node satisfies the block.
 *
 * The receiver is the relationship's generated `Properties` object (so the target fragment's
 * properties — `resolvedId`, `role`, … — are directly in scope inside the block), and the block's
 * conditions are correlated to a single related node.
 *
 * ```kotlin
 * where { mentions.any { resolvedId eq entityId } }
 * ```
 * → `EXISTS { (root)-[:HAS_MENTION]->(mentions) WHERE mentions.resolvedId = $id }`
 *
 * This is the explicit form of the flat `mentions.resolvedId eq id` shorthand; prefer it when a
 * single related node must satisfy several conditions together, or for symmetry with [none].
 */
context(outer: WhereBuilder<*>)
fun <P : NodeReference> P.any(block: context(WhereBuilder<P>) P.() -> Unit) {
    outer.conditions.add(collectRelationshipCondition(this, block, negate = false))
}

/**
 * Quantified predicate over a relationship: **no** related node satisfies the block.
 *
 * ```kotlin
 * where { mentions.none { resolvedId eq entityId } }
 * ```
 * → `NOT EXISTS { (root)-[:HAS_MENTION]->(mentions) WHERE mentions.resolvedId = $id }`
 */
context(outer: WhereBuilder<*>)
fun <P : NodeReference> P.none(block: context(WhereBuilder<P>) P.() -> Unit) {
    outer.conditions.add(collectRelationshipCondition(this, block, negate = true))
}

/**
 * Runs a quantifier block against a fresh sub-builder scoped to the relationship's [target]
 * `Properties`, then packages the collected conditions into a [WhereCondition.RelationshipCondition]
 * keyed by the target's [NodeReference.nodeAlias] (which the codegen sets to the relationship field
 * name). The relationship's type/direction/target-label are resolved from the view model at render
 * time, exactly as the flat relationship-predicate form already does.
 */
private fun <P : NodeReference> collectRelationshipCondition(
    target: P,
    block: context(WhereBuilder<P>) P.() -> Unit,
    negate: Boolean,
): WhereCondition.RelationshipCondition {
    val sub = WhereBuilder(target)
    block(sub, target)
    return WhereCondition.RelationshipCondition(
        relationshipName = target.nodeAlias,
        targetConditions = sub.conditions,
        negate = negate,
    )
}

/**
 * Builder for ORDER BY specifications.
 *
 * With context parameters, the query object is available and order specs auto-register:
 * ```kotlin
 * orderBy {
 *     query.issue.id.asc()       // Automatically registered!
 *     query.issue.state.desc()
 * }
 * ```
 *
 * @param T The query object type that provides property references
 */
class OrderBuilder<T : Any>(
    /**
     * The query object providing property references.
     */
    val queryObject: T
) {
    internal val orders = mutableListOf<OrderSpec>()

    /**
     * Adds an order specification.
     * Usage: this(query.issue.id.asc())
     * Note: With context parameters, this is typically not needed as order specs auto-register.
     */
    operator fun invoke(order: OrderSpec) {
        orders.add(order)
    }
}

/**
 * Context-aware property to access the query object within an orderBy block.
 */
context(builder: OrderBuilder<T>)
val <T : Any> query: T
    get() = builder.queryObject

/**
 * Represents a WHERE condition in the query.
 * Can be a simple property condition, a relationship filter, a label check, or an OR condition.
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
     * Filter on relationship target properties — a quantified existence check over the relationship.
     * Example: assignedTo { it.name eq "Kent Beck" }
     *
     * Renders as an existence subquery: at least one related node satisfies [targetConditions]
     * (`EXISTS { … }` / `size([…]) > 0`). When [negate] is true it becomes "no related node
     * satisfies them" (`NOT EXISTS { … }`) — the `none { }` quantifier.
     */
    data class RelationshipCondition(
        val relationshipName: String,  // e.g., "assignedTo"
        val targetConditions: List<WhereCondition>,
        val negate: Boolean = false,
    ) : WhereCondition()

    /**
     * Filter by node labels - checks if a node has specific labels.
     * Example: webUser.instanceOf<AnonymousWebUser>()
     * Generates: webUser:Anonymous (or webUser:WebUser:Anonymous for multiple labels)
     */
    data class LabelCondition(
        val alias: String,  // e.g., "webUser"
        val labels: List<String>  // e.g., ["WebUser", "Anonymous"]
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
    ENDS_WITH("ENDS WITH"),
    IS_NULL("IS NULL"),
    IS_NOT_NULL("IS NOT NULL")
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

/**
 * Represents a sort specification for a collection (relationship).
 * Used to wrap list comprehensions with apoc.coll.sortMaps().
 *
 * @param relationshipPath The path to the relationship, e.g., "assignedTo" or "raisedBy_worksFor"
 * @param propertyName The property to sort by, e.g., "name"
 * @param ascending True for ascending, false for descending
 */
data class CollectionSortSpec(
    val relationshipPath: String,
    val propertyName: String,
    val ascending: Boolean
) {
    /**
     * Returns true if this is a nested collection sort (e.g., "raisedBy_worksFor").
     */
    fun isNested(): Boolean = relationshipPath.contains("_")

    /**
     * For nested sorts, returns the parent relationship name (e.g., "raisedBy" from "raisedBy_worksFor").
     */
    fun parentRelationship(): String? = if (isNested()) relationshipPath.substringBefore("_") else null

    /**
     * For nested sorts, returns the nested relationship name (e.g., "worksFor" from "raisedBy_worksFor").
     */
    fun nestedRelationship(): String? = if (isNested()) relationshipPath.substringAfter("_") else null
}

/**
 * Result of processing order specifications.
 * Separates root-level ORDER BY from collection sorts that need apoc.coll.sortMaps().
 */
data class OrderClauseResult(
    val orderByClause: String?,
    val collectionSorts: List<CollectionSortSpec>
)