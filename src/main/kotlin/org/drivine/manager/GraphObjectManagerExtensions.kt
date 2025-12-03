package org.drivine.manager

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