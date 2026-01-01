package org.drivine.manager

import java.util.UUID

/**
 * Reified type extensions for GraphObjectManager providing cleaner Kotlin syntax.
 *
 * These extensions eliminate the need for `::class.java` by using reified type parameters.
 *
 * Example usage:
 * ```kotlin
 * // Before: graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java)
 * // After:  graphObjectManager.loadAll<RaisedAndAssignedIssue>()
 *
 * // Before: graphObjectManager.load("uuid", RaisedAndAssignedIssue::class.java)
 * // After:  graphObjectManager.load<RaisedAndAssignedIssue>("uuid")
 * ```
 */

/**
 * Loads all instances of a graph object using reified type parameter.
 *
 * @param T The graph object type to load
 * @return List of graph object instances
 */
inline fun <reified T : Any> GraphObjectManager.loadAll(): List<T> {
    return loadAll(T::class.java)
}

/**
 * Loads all instances of a graph object with filtering and ordering using reified type parameter.
 *
 * Example:
 * ```kotlin
 * graphObjectManager.loadAll<RaisedAndAssignedIssue> {
 *     where {
 *         this(query.issue.state eq "open")
 *     }
 * }
 * ```
 *
 * @param T The graph object type to load
 * @param Q The query DSL type
 * @param queryObject The query object providing property references
 * @param spec DSL block for building the query
 * @return List of graph object instances matching the criteria
 */
inline fun <reified T : Any, Q : Any> GraphObjectManager.loadAll(
    queryObject: Q,
    noinline spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
): List<T> {
    return loadAll(T::class.java, queryObject, spec)
}

/**
 * Loads a single graph object by ID using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val issue = graphObjectManager.load<RaisedAndAssignedIssue>("some-uuid")
 * ```
 *
 * @param T The graph object type to load
 * @param id The object ID
 * @return The graph object instance, or null if not found
 */
inline fun <reified T : Any> GraphObjectManager.load(id: String): T? {
    return load(id, T::class.java)
}

/**
 * Deletes a graph object by ID using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<Issue>("some-uuid")
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object ID
 * @return The number of nodes deleted (0 or 1)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: String): Int {
    return delete(id, T::class.java)
}

/**
 * Deletes a graph object by ID with additional WHERE clause using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<Issue>("some-uuid", "n.state = 'closed'")
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object ID
 * @param whereClause Additional WHERE clause conditions
 * @return The number of nodes deleted (0 or 1)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: String, whereClause: String?): Int {
    return delete(id, T::class.java, whereClause)
}

/**
 * Deletes all graph objects of a type using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.deleteAll<Issue>()
 * ```
 *
 * @param T The graph object type to delete
 * @return The number of nodes deleted
 */
inline fun <reified T : Any> GraphObjectManager.deleteAll(): Int {
    return deleteAll(T::class.java)
}

/**
 * Deletes graph objects matching a WHERE clause using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.deleteAll<Issue>("n.state = 'closed'")
 * ```
 *
 * @param T The graph object type to delete
 * @param whereClause WHERE clause conditions
 * @return The number of nodes deleted
 */
inline fun <reified T : Any> GraphObjectManager.deleteAll(whereClause: String?): Int {
    return deleteAll(T::class.java, whereClause)
}

/**
 * Deletes graph objects using a type-safe query DSL with reified type parameter.
 *
 * Example:
 * ```kotlin
 * graphObjectManager.deleteAll<RaisedAndAssignedIssue>(RaisedAndAssignedIssueQuery) {
 *     where {
 *         query.issue.state eq "closed"
 *     }
 * }
 * ```
 *
 * @param T The graph object type to delete
 * @param Q The query DSL type
 * @param queryObject The query object providing property references
 * @param spec DSL block for building the query
 * @return The number of nodes deleted
 */
inline fun <reified T : Any, Q : Any> GraphObjectManager.deleteAll(
    queryObject: Q,
    noinline spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
): Int {
    return deleteAll(T::class.java, queryObject, spec)
}

// ==================== UUID Overloads ====================

/**
 * Loads a single graph object by UUID using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val issue = graphObjectManager.load<RaisedAndAssignedIssue>(uuid)
 * ```
 *
 * @param T The graph object type to load
 * @param id The object UUID
 * @return The graph object instance, or null if not found
 */
inline fun <reified T : Any> GraphObjectManager.load(id: UUID): T? {
    return load(id.toString(), T::class.java)
}

/**
 * Deletes a graph object by UUID using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<Issue>(uuid)
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object UUID
 * @return The number of nodes deleted (0 or 1)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: UUID): Int {
    return delete(id.toString(), T::class.java)
}

/**
 * Deletes a graph object by UUID with additional WHERE clause using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<Issue>(uuid, "n.state = 'closed'")
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object UUID
 * @param whereClause Additional WHERE clause conditions
 * @return The number of nodes deleted (0 or 1)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: UUID, whereClause: String?): Int {
    return delete(id.toString(), T::class.java, whereClause)
}