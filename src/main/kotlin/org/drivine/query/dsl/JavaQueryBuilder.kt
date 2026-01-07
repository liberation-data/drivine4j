package org.drivine.query.dsl

/**
 * Intermediate builder returned by `graphObjectManager.query(Class)`.
 * Use `filterWith()` to specify the QueryDsl and get a fully-typed JavaQueryBuilder.
 *
 * Example (Java):
 * ```java
 * List<RaisedAndAssignedIssue> results = graphObjectManager
 *     .query(RaisedAndAssignedIssue.class)
 *     .filterWith(RaisedAndAssignedIssueQueryDsl.class)
 *     .where(dsl -> dsl.getIssue().getState().eq("open"))
 *     .loadAll();
 * ```
 *
 * @param T The graph view type
 */
class QueryStarter<T : Any>(
    private val graphClass: Class<T>,
    private val graphObjectManager: org.drivine.manager.GraphObjectManager
) {
    /**
     * Specifies the QueryDsl class to use for type-safe filtering.
     * Returns a fully-typed JavaQueryBuilder with access to DSL properties.
     *
     * @param Q The QueryDsl type (generated, e.g., RaisedAndAssignedIssueQueryDsl)
     * @param queryDslClass The QueryDsl class
     * @return A JavaQueryBuilder with full type safety for the DSL
     */
    fun <Q : Any> filterWith(queryDslClass: Class<Q>): JavaQueryBuilder<T, Q> {
        val queryDsl = getQueryDslInstance(queryDslClass)
        return JavaQueryBuilder(graphClass, queryDsl, graphObjectManager)
    }

    /**
     * Gets the singleton INSTANCE from a QueryDsl companion object.
     */
    private fun <Q : Any> getQueryDslInstance(queryDslClass: Class<Q>): Q {
        return try {
            // Kotlin object: get Companion.INSTANCE
            val companionField = queryDslClass.getDeclaredField("Companion")
            val companion = companionField.get(null)
            val getInstanceMethod = companion.javaClass.getMethod("getINSTANCE")
            @Suppress("UNCHECKED_CAST")
            getInstanceMethod.invoke(companion) as Q
        } catch (e: NoSuchFieldException) {
            // Java static INSTANCE field fallback
            try {
                val instanceField = queryDslClass.getDeclaredField("INSTANCE")
                @Suppress("UNCHECKED_CAST")
                instanceField.get(null) as Q
            } catch (e2: NoSuchFieldException) {
                throw IllegalArgumentException(
                    "QueryDsl class ${queryDslClass.name} must have either a Companion.INSTANCE " +
                    "or a static INSTANCE field", e2
                )
            }
        }
    }
}

/**
 * Starts a query for the given GraphView class.
 * Use `filterWith()` to specify the QueryDsl for type-safe filtering.
 *
 * Example (Java):
 * ```java
 * List<RaisedAndAssignedIssue> results = graphObjectManager
 *     .query(RaisedAndAssignedIssue.class)
 *     .filterWith(RaisedAndAssignedIssueQueryDsl.class)
 *     .where(dsl -> dsl.getIssue().getState().eq("open"))
 *     .loadAll();
 * ```
 *
 * @param T The graph view type
 * @param graphClass The GraphView class to query
 * @return A QueryStarter for fluent configuration
 */
fun <T : Any> org.drivine.manager.GraphObjectManager.query(
    graphClass: Class<T>
): QueryStarter<T> {
    return QueryStarter(graphClass, this)
}

/**
 * Java-friendly query builder for constructing type-safe queries.
 *
 * This class provides a fluent API that works well with Java lambdas:
 * ```java
 * List<PersonCareer> results = graphObjectManager
 *     .query(PersonCareer.class)
 *     .filterWith(PersonCareerQueryDsl.class)
 *     .where(q -> q.person().name().eq("Alice"))
 *     .where(q -> q.employmentHistory().role().contains("Engineer"))
 *     .orderBy(q -> q.person().name().asc())
 *     .loadAll();
 * ```
 *
 * @param T The graph view type
 * @param Q The query DSL type (generated, e.g., PersonCareerQueryDsl)
 */
class JavaQueryBuilder<T : Any, Q : Any>(
    private val graphClass: Class<T>,
    private val queryDsl: Q,
    private val graphObjectManager: org.drivine.manager.GraphObjectManager
) {
    private val conditions = mutableListOf<WhereCondition>()
    private val orders = mutableListOf<OrderSpec>()

    /**
     * Adds a WHERE condition using the query DSL.
     *
     * Example (Java):
     * ```java
     * builder.where(q -> q.person().name().eq("Alice"))
     * ```
     *
     * @param condition Function that returns a PropertyConditionBuilder from the query DSL
     * @return This builder for chaining
     */
    fun where(condition: java.util.function.Function<Q, PropertyConditionBuilder>): JavaQueryBuilder<T, Q> {
        val conditionBuilder = condition.apply(queryDsl)
        conditions.add(conditionBuilder.condition)
        return this
    }

    /**
     * Adds multiple WHERE conditions that are AND'd together.
     *
     * Example (Java):
     * ```java
     * builder.whereAll(q -> Arrays.asList(
     *     q.person().name().eq("Alice"),
     *     q.person().bio().isNotNull()
     * ))
     * ```
     *
     * @param conditions Function that returns a list of PropertyConditionBuilders
     * @return This builder for chaining
     */
    fun whereAll(conditions: java.util.function.Function<Q, List<PropertyConditionBuilder>>): JavaQueryBuilder<T, Q> {
        val conditionBuilders = conditions.apply(queryDsl)
        this.conditions.addAll(conditionBuilders.map { it.condition })
        return this
    }

    /**
     * Adds an OR condition (any of the conditions must match).
     *
     * Example (Java):
     * ```java
     * builder.whereAny(q -> Arrays.asList(
     *     q.issue().state().eq("open"),
     *     q.issue().state().eq("reopened")
     * ))
     * ```
     *
     * @param conditions Function that returns a list of PropertyConditionBuilders for OR
     * @return This builder for chaining
     */
    fun whereAny(conditions: java.util.function.Function<Q, List<PropertyConditionBuilder>>): JavaQueryBuilder<T, Q> {
        val conditionBuilders = conditions.apply(queryDsl)
        this.conditions.add(WhereCondition.OrCondition(conditionBuilders.map { it.condition }))
        return this
    }

    /**
     * Adds an ORDER BY clause.
     *
     * Example (Java):
     * ```java
     * builder.orderBy(q -> q.person().name().asc())
     * ```
     *
     * @param order Function that returns an OrderSpec from the query DSL
     * @return This builder for chaining
     */
    fun orderBy(order: java.util.function.Function<Q, OrderSpec>): JavaQueryBuilder<T, Q> {
        orders.add(order.apply(queryDsl))
        return this
    }

    /**
     * Executes the query and returns all matching results.
     *
     * @return List of matching graph objects
     */
    fun loadAll(): List<T> {
        return graphObjectManager.loadAll(graphClass, queryDsl) {
            // Transfer collected conditions and orders to the GraphQuerySpec
            this.conditions.addAll(this@JavaQueryBuilder.conditions)
            this.orders.addAll(this@JavaQueryBuilder.orders)
        }
    }

    /**
     * Executes the query and returns the first matching result, or null.
     *
     * @return First matching graph object, or null if none found
     */
    fun loadFirst(): T? {
        return loadAll().firstOrNull()
    }

    /**
     * Executes a delete operation for all matching objects.
     *
     * @return Number of deleted objects
     */
    fun deleteAll(): Int {
        return graphObjectManager.deleteAll(graphClass, queryDsl) {
            this.conditions.addAll(this@JavaQueryBuilder.conditions)
            this.orders.addAll(this@JavaQueryBuilder.orders)
        }
    }
}