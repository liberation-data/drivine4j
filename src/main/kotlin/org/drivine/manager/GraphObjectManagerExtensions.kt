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
 * Loads a single graph object by ID, throwing if not found.
 *
 * Example:
 * ```kotlin
 * val issue = graphObjectManager.loadOrThrow<RaisedAndAssignedIssue>("some-uuid")
 * ```
 *
 * @param T The graph object type to load
 * @param id The object ID
 * @return The graph object instance
 * @throws NoSuchElementException if not found
 */
inline fun <reified T : Any> GraphObjectManager.loadOrThrow(id: String): T {
    return load(id, T::class.java)
        ?: throw NoSuchElementException("${T::class.simpleName} not found: $id")
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
 * Deletes a graph object by ID with a cascade policy using reified type parameter.
 *
 * The cascade scope is the shape of the view T — see [GraphObjectManager.delete].
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<DeletableSession>(sessionId, CascadeType.DELETE_ALL)
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object ID
 * @param cascade The cascade policy
 * @return The number of nodes deleted (root plus any cascaded fragments)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: String, cascade: CascadeType): Int {
    return delete(id, T::class.java, cascade)
}

/**
 * Deletes a graph object by ID with a WHERE clause and cascade policy using reified type parameter.
 *
 * @param T The graph object type to delete
 * @param id The object ID
 * @param whereClause Additional WHERE clause conditions
 * @param cascade The cascade policy
 * @return The number of nodes deleted (root plus any cascaded fragments)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: String, whereClause: String?, cascade: CascadeType): Int {
    return delete(id, T::class.java, whereClause, cascade)
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
 * Loads a single graph object by UUID, throwing if not found.
 *
 * @param T The graph object type to load
 * @param id The object UUID
 * @return The graph object instance
 * @throws NoSuchElementException if not found
 */
inline fun <reified T : Any> GraphObjectManager.loadOrThrow(id: UUID): T {
    return load(id.toString(), T::class.java)
        ?: throw NoSuchElementException("${T::class.simpleName} not found: $id")
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

/**
 * Deletes a graph object by UUID with a cascade policy using reified type parameter.
 *
 * Example:
 * ```kotlin
 * val count = graphObjectManager.delete<DeletableSession>(sessionUuid, CascadeType.DELETE_ALL)
 * ```
 *
 * @param T The graph object type to delete
 * @param id The object UUID
 * @param cascade The cascade policy
 * @return The number of nodes deleted (root plus any cascaded fragments)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: UUID, cascade: CascadeType): Int {
    return delete(id.toString(), T::class.java, cascade)
}

/**
 * Deletes a graph object by UUID with a WHERE clause and cascade policy using reified type parameter.
 *
 * @param T The graph object type to delete
 * @param id The object UUID
 * @param whereClause Additional WHERE clause conditions
 * @param cascade The cascade policy
 * @return The number of nodes deleted (root plus any cascaded fragments)
 */
inline fun <reified T : Any> GraphObjectManager.delete(id: UUID, whereClause: String?, cascade: CascadeType): Int {
    return delete(id.toString(), T::class.java, whereClause, cascade)
}

// ---------------------------------------------------------------------------------------------------
// count
// ---------------------------------------------------------------------------------------------------

/**
 * Counts all instances of a graph object using a reified type parameter.
 *
 * `graphObjectManager.count<RaisedAndAssignedIssue>()` instead of `count(RaisedAndAssignedIssue::class.java)`.
 */
inline fun <reified T : Any> GraphObjectManager.count(): Long {
    return count(T::class.java)
}

/** Counts graph objects matching a simple WHERE clause, using a reified type parameter. */
inline fun <reified T : Any> GraphObjectManager.count(whereClause: String): Long {
    return count(T::class.java, whereClause)
}

/**
 * Counts graph objects using the type-safe query DSL, with a reified type parameter.
 *
 * ```kotlin
 * graphObjectManager.count<RaisedAndAssignedIssue>(RaisedAndAssignedIssueQueryDsl.INSTANCE) {
 *     where { query.issue.state eq "open" }
 * }
 * ```
 */
inline fun <reified T : Any, Q : Any> GraphObjectManager.count(
    queryObject: Q,
    noinline spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
): Long {
    return count(T::class.java, queryObject, spec)
}

// ---------------------------------------------------------------------------------------------------
// loadNearest
// ---------------------------------------------------------------------------------------------------

/**
 * Vector search with a reified type parameter.
 *
 * `graphObjectManager.loadNearest<PropositionView>(queryVector, topK = 20)` instead of
 * `loadNearest(PropositionView::class.java, queryVector, topK = 20)`.
 */
inline fun <reified T : Any> GraphObjectManager.loadNearest(
    vector: List<Float>,
    topK: Int,
    threshold: Double? = null,
): List<Scored<T>> {
    return loadNearest(T::class.java, vector, topK, threshold)
}

/**
 * Vector search naming the embedding [property] explicitly (when the fragment has several), with a
 * reified type parameter.
 */
inline fun <reified T : Any> GraphObjectManager.loadNearest(
    property: String?,
    vector: List<Float>,
    topK: Int,
    threshold: Double? = null,
): List<Scored<T>> {
    return loadNearest(T::class.java, property, vector, topK, threshold)
}

/**
 * Filtered vector search (a `where { }` predicate AND-ed into the post-projection filter), with a
 * reified type parameter.
 *
 * ```kotlin
 * graphObjectManager.loadNearest<PropositionView>(PropositionViewQueryDsl.INSTANCE, queryVector, topK = 20) {
 *     where { query.proposition.contextId eq ctx }
 * }
 * ```
 */
inline fun <reified T : Any, Q : Any> GraphObjectManager.loadNearest(
    queryObject: Q,
    vector: List<Float>,
    topK: Int,
    threshold: Double? = null,
    noinline spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
): List<Scored<T>> {
    return loadNearest(T::class.java, queryObject, vector, topK, threshold, spec)
}