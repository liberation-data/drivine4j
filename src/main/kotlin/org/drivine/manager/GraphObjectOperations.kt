package org.drivine.manager

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.store.StoreIdentity

/**
 * Loading, querying and deleting graph objects: what [GraphObjectManager] and
 * [StatelessGraphObjectManager] have in common. They differ in how they save.
 *
 * The reified Kotlin forms and the generated query DSL are extensions of this interface, so they
 * work on either manager.
 */
interface GraphObjectOperations {

    /** The name of the database this manager is connected to. */
    val database: String

    /** Stable identity of the store this manager's objects live in. See [StoreIdentity]. */
    val storeIdentity: StoreIdentity

    /** Relationships whose type is known only at runtime, between stored nodes. See [EdgeOperations]. */
    val edges: EdgeOperations

    /** What to do when an ordered or paginated query has no range index to seek into. */
    var indexAdvice: IndexAdvicePolicy

    /**
     * Loads all instances of a graph object (GraphView or GraphFragment) from the database.
     * [GraphObjectManager] adds each loaded object to its session for dirty tracking;
     * [StatelessGraphObjectManager] remembers nothing.
     *
     * @param graphClass The graph object class to load
     * @return List of graph object instances
     */
    fun <T : Any> loadAll(graphClass: Class<T>): List<T>

    /**
     * Loads instances of a graph object with a simple WHERE clause filter.
     * This is a Java-friendly alternative to the DSL-based loadAll method.
     *
     * Example:
     * ```java
     * // Filter by root fragment property
     * graphObjectManager.loadAll(PersonContext.class, "person.name = 'Alice'");
     *
     * // Filter by relationship property
     * graphObjectManager.loadAll(PersonContext.class, "worksFor.name = 'Acme Corp'");
     *
     * // Multiple conditions with AND
     * graphObjectManager.loadAll(PersonContext.class, "person.name = 'Alice' AND person.bio IS NOT NULL");
     * ```
     *
     * @param graphClass The graph object class to load
     * @param whereClause Cypher WHERE clause conditions (without the WHERE keyword)
     * @return List of graph object instances matching the criteria
     */
    fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T>

    /**
     * Counts all instances of a graph object (GraphView or GraphFragment).
     *
     * For a `@GraphView` this counts only roots that satisfy the view's required relationships —
     * the same roots [loadAll] would return — not a naive node count. For a plain `@NodeFragment`
     * it is a straight node count of the fragment's labels.
     *
     * @param graphClass The graph object class to count
     * @return The number of matching graph objects
     */
    fun <T : Any> count(graphClass: Class<T>): Long

    /**
     * Counts graph objects matching a simple WHERE clause filter (Java-friendly). Conditions use
     * the same aliases as [loadAll] — `n` for fragments, the root field name for views.
     *
     * @param graphClass The graph object class to count
     * @param whereClause Cypher WHERE clause conditions (without the WHERE keyword)
     * @return The number of matching graph objects
     */
    fun <T : Any> count(graphClass: Class<T>, whereClause: String): Long

    /**
     * Counts graph objects using the type-safe query DSL (mirrors the DSL [loadAll]/[deleteAll]).
     *
     * @param graphClass The graph object class to count
     * @param queryObject The query object providing property references
     * @param spec DSL block for building the filter
     * @return The number of matching graph objects
     */
    fun <T : Any, Q : Any> count(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): Long

    /**
     * Loads instances of a graph object using a type-safe query DSL.
     * Supports filtering and ordering.
     *
     * Pass the query DSL object explicitly to enable type-safe property access.
     *
     * Example:
     * ```kotlin
     * graphObjectManager.loadAll(
     *     RaisedAndAssignedIssue::class.java,
     *     RaisedAndAssignedIssueQueryDsl.INSTANCE
     * ) {
     *     where {
     *         this(query.issue.state eq "open")
     *         this(query.issue.id gt 1000)
     *     }
     *     orderBy {
     *         this(query.issue.id.descending())
     *     }
     * }
     * ```
     *
     * With `drivine4j-codegen` there is no need to pass the query object: for each `@GraphView` and
     * `@NodeFragment` it generates the query DSL and a `loadAll<T> { }` extension on
     * [GraphObjectOperations] that supplies it.
     *
     * @param graphClass The graph object class to load
     * @param queryObject The query object providing property references
     * @param spec DSL block for building the query
     * @return List of graph object instances matching the criteria
     */
    fun <T : Any, Q : Any> loadAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): List<T>

    /**
     * Loads a single graph object instance by its ID.
     * [GraphObjectManager] adds the loaded object to its session for dirty tracking;
     * [StatelessGraphObjectManager] remembers nothing.
     *
     * @param id The ID value to search for
     * @param graphClass The graph object class to load
     * @return The graph object instance, or null if not found
     */
    fun <T : Any> load(id: String, graphClass: Class<T>): T?

    /**
     * Loads the [topK] graph objects whose embedding is most similar to [vector], ordered most
     * similar first, each paired with its normalized similarity [score][Scored.score].
     *
     * Works on both a `@GraphView` (searches the **root fragment**'s embedding, returns the
     * projected view) and a plain `@NodeFragment` (searches and returns the fragment itself). The
     * vector index is inferred from the `@VectorIndex` annotation on the searched fragment; when it
     * declares a single embedding no [property] is needed — pass [property] only to disambiguate
     * between several embeddings on the same node.
     *
     * **Result count semantics:** `topK` is the index's `k` — the number of candidates the
     * nearest-neighbour search returns. For a view, the required-relationship filters (and an
     * optional [threshold]) are applied *afterwards*, so **fewer than [topK] results may come back**;
     * raise [topK] if your view is selective. A fragment search has no relationship filters, so it
     * returns the full top-K (minus any [threshold] cut).
     *
     * Example:
     * ```kotlin
     * val views = graphObjectManager.loadNearest(PropositionView::class.java, queryEmbedding, topK = 20)
     * val nodes = graphObjectManager.loadNearest(PropositionNode::class.java, queryEmbedding, topK = 20)
     * ```
     *
     * @param graphClass the `@GraphView` or `@NodeFragment` class to load
     * @param vector the query embedding
     * @param topK the number of nearest candidates to retrieve from the index
     * @param threshold optional minimum similarity (higher = closer); candidates below it are dropped
     * @return scored instances, most similar first, of length `<= topK`
     * @throws UnsupportedOperationException if the backend has no native vector index
     */
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): List<Scored<T>>

    /**
     * Vector search variant that names the embedding [property] explicitly — use when the searched
     * fragment carries more than one `@VectorIndex` property. See the [loadNearest] overload above
     * for the full semantics.
     *
     * @param property the `@VectorIndex` embedding property to search
     */
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): List<Scored<T>>

    /**
     * Vector search with an additional caller `where { }` predicate `AND`-ed into the filter — vector
     * similarity plus arbitrary property predicates in one statement. Works on a `@GraphView`
     * (predicates filter the *projected* values) and on a bare `@NodeFragment` (predicates filter the
     * matched node directly, via the fragment's generated query DSL) — the vector mirror of the filtered
     * [loadMatching].
     *
     * ```kotlin
     * // view
     * graphObjectManager.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, queryVector, topK = 20) {
     *     where { query.proposition.contextId eq ctx; query.proposition.status eq status }
     * }
     * // fragment
     * graphObjectManager.loadNearest(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE, queryVector, topK = 20) {
     *     where { query.containerSectionId eq "sec-1" }
     * }
     * ```
     *
     * For a **view**, predicates filter the *projected* values: **property predicates** on the root map
     * (`proposition.contextId eq …`) and **relationship quantifiers** over the projected relationship
     * collection (`mentions.any { resolvedId eq … }` → `any(m IN mentions WHERE m.resolvedId = …)`,
     * `none{}` → `NOT any(...)`). Multiple quantifiers `AND` together (e.g. "mentions all of these
     * entities" = one `any{}` per id). Referencing a relationship the view does not project is an
     * error. The `topK` / post-filter semantics are unchanged: the result may contain fewer than
     * `topK` rows.
     *
     * @param queryObject the generated query DSL object providing property references
     * @param spec the `where { }` block
     */
    fun <T : Any, Q : Any> loadNearest(
        graphClass: Class<T>,
        queryObject: Q,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>>

    /**
     * Full-text search: finds the [topK] nodes most relevant to a text [query] and returns them
     * scored, the full-text mirror of [loadNearest]. Resolves the model's `@FullTextIndex`, runs the
     * engine-appropriate full-text `CALL`, and returns typed, **[0, 1]-normalized** [Scored] results
     * (polymorphic dispatch included) — no consumer Cypher, no per-engine score normalization.
     *
     * Works on a `@GraphView` (searches its root fragment's text index, returns the projected view)
     * and on a bare `@NodeFragment` (searches and returns itself). As with [loadNearest], a view's
     * required-relationship filters apply *after* the search, so **fewer than [topK] rows may return**.
     *
     * The [query] is passed through to the engine's full-text query language (Lucene syntax on Neo4j:
     * `AND`/`OR`/`"phrase"`/`field:term`). Raw user input is **not** escaped here — wrap or escape it
     * yourself if it may contain query-syntax metacharacters.
     *
     * @param graphClass the `@GraphView` or `@NodeFragment` class to load
     * @param query the full-text query string
     * @param topK the maximum number of results to return (applied as a `LIMIT`)
     * @param threshold minimum normalized relevance in `[0, 1]`; results below it are dropped (default
     *   `0.0` keeps everything)
     * @return scored instances, most relevant first, of length `<= topK`
     * @throws UnsupportedOperationException if the backend has no native full-text index
     */
    fun <T : Any> loadMatching(
        graphClass: Class<T>,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
    ): List<Scored<T>>

    /**
     * Full-text search variant that names the indexed [property] explicitly — use when the searched
     * fragment carries more than one `@FullTextIndex`. Pass any property the target index covers. See
     * the [loadMatching] overload above for the full semantics.
     *
     * @param property a property covered by the `@FullTextIndex` to search
     */
    fun <T : Any> loadMatching(
        graphClass: Class<T>,
        property: String?,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
    ): List<Scored<T>>

    /**
     * Full-text search with an additional caller `where { }` predicate `AND`-ed into the post-search
     * filter — full-text relevance plus arbitrary property predicates in one statement, the full-text
     * mirror of the filtered [loadNearest]. Works on a `@GraphView` (predicates filter the *projected*
     * values, exactly as the filtered vector search does) and on a bare `@NodeFragment` (predicates
     * filter the matched node directly, using the fragment's generated query DSL).
     *
     * ```kotlin
     * // view
     * graphObjectManager.loadMatching(ChunkView::class.java, ChunkViewQueryDsl.INSTANCE, "graph databases", topK = 20) {
     *     where { query.containerSectionId eq "sec-1" }
     * }
     * // fragment
     * graphObjectManager.loadMatching(ChunkNode::class.java, ChunkNodeQueryDsl.INSTANCE, "graph databases", topK = 20) {
     *     where { query.containerSectionId eq "sec-1" }
     * }
     * ```
     *
     * @param queryObject the generated query DSL object providing property references
     * @param query the full-text query string
     * @param spec the `where { }` block
     */
    fun <T : Any, Q : Any> loadMatching(
        graphClass: Class<T>,
        queryObject: Q,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>>

    /**
     * Deletes a graph object (GraphView or GraphFragment) from the database by its ID.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @return The number of nodes deleted (0 or 1)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>): Int

    /**
     * Deletes a graph object by its ID, applying a cascade policy scoped by the view.
     *
     * The cascade boundary is the shape of the view passed in — see [delete] (the four-arg
     * overload) for the full semantics.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param cascade The cascade policy (default NONE = root-only DETACH DELETE)
     * @return The number of nodes deleted (root plus any cascaded fragments)
     * @throws UnsupportedOperationException for [CascadeType.DELETE_ORPHAN] on Memgraph
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, cascade: CascadeType): Int

    /**
     * Deletes a graph object (GraphView or GraphFragment) from the database by its ID,
     * with an additional WHERE clause filter.
     *
     * Example:
     * ```kotlin
     * // Delete only if state is 'closed'
     * graphObjectManager.delete(issueUuid, IssueCore::class.java, "issue.state = 'closed'")
     * ```
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param whereClause Additional WHERE clause conditions (without WHERE keyword)
     * @return The number of nodes deleted (0 or 1)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?): Int

    /**
     * Deletes a graph object by its ID with both a WHERE clause filter and a cascade policy.
     *
     * The cascade scope is the shape of the view: traversal follows only the relationships the
     * view declares, so callers express "what to destroy" by passing a narrow, delete-only view.
     *
     * - [CascadeType.NONE] (default) → root-only DETACH DELETE, identical to the legacy behavior.
     *   Related fragments are left as orphans.
     * - [CascadeType.DELETE_ALL] → also deletes every fragment reachable through the view's
     *   declared relationships (honoring direction and maxDepth). Nodes the view does not include
     *   survive; DETACH merely drops the edges to them.
     * - [CascadeType.DELETE_ORPHAN] → also deletes each included related fragment, but only if it
     *   has no relationships left once the root is removed. Throws `UnsupportedOperationException`
     *   on Memgraph, which cannot run the check.
     *
     * Ids are always bound as parameters; nothing is interpolated into the Cypher.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param whereClause Additional WHERE clause conditions (without WHERE keyword)
     * @param cascade The cascade policy
     * @return The number of nodes deleted (root plus any cascaded fragments)
     * @throws UnsupportedOperationException for [CascadeType.DELETE_ORPHAN] on Memgraph
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?, cascade: CascadeType): Int

    /**
     * Deletes all graph objects (GraphViews or GraphFragments) of a given type from the database.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * WARNING: This will delete ALL nodes matching the labels. Use with caution.
     *
     * @param graphClass The graph object class
     * @return The number of nodes deleted
     */
    fun <T : Any> deleteAll(graphClass: Class<T>): Int

    /**
     * Deletes graph objects (GraphViews or GraphFragments) matching a WHERE clause filter.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * Example:
     * ```kotlin
     * // Delete all closed issues
     * graphObjectManager.deleteAll(IssueCore::class.java, "n.state = 'closed'")
     *
     * // For GraphViews, use the root fragment field name as alias
     * graphObjectManager.deleteAll(RaisedAndAssignedIssue::class.java, "issue.state = 'closed'")
     * ```
     *
     * @param graphClass The graph object class
     * @param whereClause WHERE clause conditions (without WHERE keyword)
     * @return The number of nodes deleted
     */
    fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int

    /**
     * Deletes graph objects using a type-safe query DSL.
     * Supports filtering conditions.
     *
     * Example:
     * ```kotlin
     * graphObjectManager.deleteAll(
     *     IssueCore::class.java,
     *     IssueCoreQueryDsl.INSTANCE
     * ) {
     *     where {
     *         this(query.state eq "closed")
     *         this(query.locked eq true)
     *     }
     * }
     * ```
     *
     * @param graphClass The graph object class to delete
     * @param queryObject The query object providing property references
     * @param spec DSL block for building the query
     * @return The number of nodes deleted
     */
    fun <T : Any, Q : Any> deleteAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): Int

    /** [loadNearest] with every optional argument left out, for Java. */
    fun <T : Any> loadNearest(graphClass: Class<T>, vector: List<Float>, topK: Int): List<Scored<T>> =
        loadNearest(graphClass, vector, topK, null, null, null)

    /** [loadNearest] with a [threshold] and nothing after it, for Java. */
    fun <T : Any> loadNearest(graphClass: Class<T>, vector: List<Float>, topK: Int, threshold: Double?): List<Scored<T>> =
        loadNearest(graphClass, vector, topK, threshold, null, null)

    /** [loadNearest] with a [threshold] and a [searchK] and no partition label, for Java. */
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        searchK: Int?,
    ): List<Scored<T>> = loadNearest(graphClass, vector, topK, threshold, searchK, null)

    /** [loadNearest] on a named [property] with every optional argument left out, for Java. */
    fun <T : Any> loadNearest(graphClass: Class<T>, property: String?, vector: List<Float>, topK: Int): List<Scored<T>> =
        loadNearest(graphClass, property, vector, topK, null, null, null)

    /** [loadNearest] on a named [property] with a [threshold] and nothing after it, for Java. */
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
    ): List<Scored<T>> = loadNearest(graphClass, property, vector, topK, threshold, null, null)

    /** [loadNearest] on a named [property] with a [threshold] and a [searchK] and no partition label, for Java. */
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        searchK: Int?,
    ): List<Scored<T>> = loadNearest(graphClass, property, vector, topK, threshold, searchK, null)

    /** [loadMatching] with every optional argument left out, for Java. */
    fun <T : Any> loadMatching(graphClass: Class<T>, query: String, topK: Int): List<Scored<T>> =
        loadMatching(graphClass, query, topK, 0.0)

    /** [loadMatching] on a named [property] with every optional argument left out, for Java. */
    fun <T : Any> loadMatching(graphClass: Class<T>, property: String?, query: String, topK: Int): List<Scored<T>> =
        loadMatching(graphClass, property, query, topK, 0.0)
}
