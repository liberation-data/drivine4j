package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.mapper.TransformPostProcessor
import org.drivine.query.FragmentVectorSearchBuilder
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.QuerySpecification
import org.drivine.query.VectorIndexResolver
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderClauseResult
import org.drivine.session.SessionManager

/**
 * Context for DSL-based queries containing the resolved view model, WHERE clause, and bindings.
 */
private data class QueryContext(
    val viewModel: GraphViewModel?,
    val whereClause: String?,
    val bindings: Map<String, Any?>,
    val prologs: List<String> = emptyList(),
    val bridgeVariables: List<String> = emptyList(),
)

/**
 * Manager for working with graph objects (GraphViews and GraphFragments).
 * Provides methods to query and retrieve graph-mapped objects from the database.
 *
 * Maintains a session to track loaded objects and enable dirty checking for optimized saves.
 */
class GraphObjectManager(
    private val persistenceManager: PersistenceManager,
    private val sessionManager: SessionManager,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry
) {
    /**
     * The name of the database this manager is connected to.
     */
    val database: String
        get() = persistenceManager.database

    private val grammar = persistenceManager.grammar

    /**
     * Loads all instances of a graph object (GraphView or GraphFragment) from the database.
     * Loaded objects are automatically added to the session for dirty tracking.
     *
     * @param graphClass The graph object class to load
     * @return List of graph object instances
     */
    fun <T : Any> loadAll(graphClass: Class<T>): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val query = builder.buildQuery()

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .transform(graphClass)
        )

        // Snapshot loaded objects for dirty tracking
        snapshotResults(graphClass, results)

        return results
    }

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
    fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val query = builder.buildQuery(whereClause, null)

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .transform(graphClass)
        )

        // Snapshot loaded objects for dirty tracking
        snapshotResults(graphClass, results)

        return results
    }

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
    fun <T : Any> count(graphClass: Class<T>): Long {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(builder.buildCountQuery())
                .transform(Long::class.java)
        )
    }

    /**
     * Counts graph objects matching a simple WHERE clause filter (Java-friendly). Conditions use
     * the same aliases as [loadAll] — `n` for fragments, the root field name for views.
     *
     * @param graphClass The graph object class to count
     * @param whereClause Cypher WHERE clause conditions (without the WHERE keyword)
     * @return The number of matching graph objects
     */
    fun <T : Any> count(graphClass: Class<T>, whereClause: String): Long {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(builder.buildCountQuery(whereClause))
                .transform(Long::class.java)
        )
    }

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
    ): Long {
        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val ctx = buildQueryContext(graphClass, querySpec)
        val query = builder.buildCountQuery(ctx.whereClause, ctx.prologs, ctx.bridgeVariables)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(ctx.bindings)
                .transform(Long::class.java)
        )
    }

    /**
     * Auto-registers subtypes for a class hierarchy based on Neo4j labels.
     * For sealed classes and classes with @JsonSubTypes, automatically registers all subclasses
     * using their simple name as the discriminator.
     * The TransformPostProcessor will use Neo4j labels to determine the concrete type.
     *
     * For label-based polymorphism, registers using:
     * 1. Composite key (all labels sorted and comma-joined) - most specific match
     * 2. Individual distinct labels - fallback matching
     * 3. Simple class name - final fallback
     *
     * For GraphViews, also registers subtypes for relationship target types.
     */
    private fun autoRegisterSubtypesIfNeeded(graphClass: Class<*>) {
        // Track registered classes to avoid infinite recursion
        val registeredClasses = mutableSetOf<Class<*>>()
        autoRegisterSubtypesRecursive(graphClass, registeredClasses)
    }

    private fun autoRegisterSubtypesRecursive(graphClass: Class<*>, registeredClasses: MutableSet<Class<*>>) {
        if (!registeredClasses.add(graphClass)) {
            return // Already processed this class
        }

        val kotlinClass = graphClass.kotlin

        // First, check for Jackson's @JsonSubTypes annotation (works for both Java and Kotlin)
        val jsonSubTypes = graphClass.getAnnotation(JsonSubTypes::class.java)
        if (jsonSubTypes != null) {
            jsonSubTypes.value.forEach { subType ->
                subtypeRegistry.register(graphClass, subType.name, subType.value.java)
            }
            // Don't return - still need to check relationships for GraphViews
        } else if (kotlinClass.isSealed) {
            // Register sealed class subtypes
            kotlinClass.sealedSubclasses.forEach { subclass ->
                val subclassJava = subclass.java

                // Extract labels from @NodeFragment annotation to use as discriminators
                val nodeFragment = subclassJava.getAnnotation(NodeFragment::class.java)
                if (nodeFragment != null) {
                    val subLabels = nodeFragment.labels.toList()

                    // Register using composite key (all labels sorted and joined)
                    // This enables matching nodes with multiple labels like ["WebUser", "Anonymous"]
                    if (subLabels.isNotEmpty()) {
                        subtypeRegistry.registerWithLabels(graphClass, subLabels, subclassJava)
                    }

                    // Also register using each distinct label (labels not in base class)
                    val baseFragment = graphClass.getAnnotation(NodeFragment::class.java)
                    val baseLabels = baseFragment?.labels?.toSet() ?: emptySet()
                    val distinctLabels = subLabels.toSet() - baseLabels

                    distinctLabels.forEach { label ->
                        subtypeRegistry.register(graphClass, label, subclassJava)
                    }
                }

                // Also register using simple class name as fallback
                subtypeRegistry.register(graphClass, subclass.simpleName ?: subclassJava.simpleName, subclassJava)
            }
        }

        // For GraphViews, also register subtypes for relationship target types
        if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(graphClass)
            viewModel.relationships.forEach { rel ->
                autoRegisterSubtypesRecursive(rel.elementType, registeredClasses)
            }
        }
    }

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
     *         this(query.issue.id.desc())
     *     }
     * }
     * ```
     *
     * **Future with Code Generation:**
     * When code generation is implemented, query DSLs will be auto-registered via QueryDslRegistry,
     * and you'll be able to use an even cleaner extension function syntax without passing the query object.
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
    ): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val ctx = buildQueryContext(graphClass, querySpec)

        // Process ORDER BY clause - separate root orders from collection sorts
        val relationshipNames = ctx.viewModel?.relationships?.map { it.fieldName }?.toSet() ?: emptySet()
        val orderResult = if (querySpec.orders.isNotEmpty()) {
            CypherGenerator.processOrders(querySpec.orders, relationshipNames)
        } else {
            OrderClauseResult(null, emptyList())
        }

        val baseQuery = if (querySpec.depthOverrides.isNotEmpty() && builder is GraphViewQueryBuilder) {
            builder.buildQuery(ctx.whereClause, orderResult.orderByClause, orderResult.collectionSorts, querySpec.depthOverrides, ctx.prologs, ctx.bridgeVariables)
        } else {
            builder.buildQuery(ctx.whereClause, orderResult.orderByClause, orderResult.collectionSorts, ctx.prologs, ctx.bridgeVariables)
        }

        // SKIP/LIMIT are the final clauses, after RETURN … ORDER BY …. For a @GraphView each root is
        // one row (relationships are pattern comprehensions in the projection), so LIMIT bounds root
        // entities and keeps their collections intact.
        val (query, bindings) = applyPagination(baseQuery, ctx.bindings, querySpec.skip, querySpec.limit)

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .bind(bindings)
                .transform(graphClass)
        )

        // Snapshot loaded objects for dirty tracking
        snapshotResults(graphClass, results)

        return results
    }

    /**
     * Loads a single graph object instance by its ID.
     * The loaded object is automatically added to the session for dirty tracking.
     *
     * @param id The ID value to search for
     * @param graphClass The graph object class to load
     * @return The graph object instance, or null if not found
     */
    fun <T : Any> load(id: String, graphClass: Class<T>): T? {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val whereClause = builder.buildIdWhereClause("id")
        val query = builder.buildQuery(whereClause)

        val result = persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(graphClass)
        )

        // Snapshot the loaded object for dirty tracking
        if (result != null) {
            snapshotResults(graphClass, listOf(result))
        }

        return result
    }

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
    @JvmOverloads
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
    ): List<Scored<T>> = loadNearest(graphClass, null, vector, topK, threshold)

    /**
     * Vector search variant that names the embedding [property] explicitly — use when the searched
     * fragment carries more than one `@VectorIndex` property. See the [loadNearest] overload above
     * for the full semantics.
     *
     * @param property the `@VectorIndex` embedding property to search
     */
    @JvmOverloads
    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
    ): List<Scored<T>> {
        if (!grammar.supportsVectorSearch) {
            throw UnsupportedOperationException(
                "Vector search is not supported on this backend (${grammar::class.simpleName} has no native vector index)."
            )
        }

        autoRegisterSubtypesIfNeeded(graphClass)

        val thresholdParam = if (threshold != null) VECTOR_THRESHOLD_PARAM else null
        val query = when {
            graphClass.isAnnotationPresent(GraphView::class.java) -> {
                // A view searches its root fragment's embedding and returns the projected view.
                val rootFragmentType = GraphViewModel.from(graphClass).rootFragment.fragmentType
                val spec = VectorIndexResolver.resolve(rootFragmentType, property, VECTOR_TOP_K_PARAM, VECTOR_QUERY_PARAM)
                GraphViewQueryBuilder.forView(graphClass, grammar).buildVectorQuery(spec, thresholdParam)
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                // A fragment searches and returns itself — no relationships, no required-rel filter.
                val spec = VectorIndexResolver.resolve(graphClass, property, VECTOR_TOP_K_PARAM, VECTOR_QUERY_PARAM)
                FragmentVectorSearchBuilder(FragmentModel.from(graphClass), grammar).build(spec, thresholdParam)
            }

            else -> throw IllegalArgumentException(
                "loadNearest requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }

        val bindings = buildMap<String, Any?> {
            put(VECTOR_TOP_K_PARAM, topK)
            put(VECTOR_QUERY_PARAM, vector)
            if (threshold != null) put(VECTOR_THRESHOLD_PARAM, threshold)
        }

        return executeVectorSearch(graphClass, query, bindings)
    }

    /**
     * Vector search over a `@GraphView` with an additional caller `where { }` predicate `AND`-ed into
     * the post-projection filter. Use for "find the nearest propositions in this context with this
     * status" — vector similarity plus arbitrary property predicates in one statement.
     *
     * ```kotlin
     * graphObjectManager.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, queryVector, topK = 20) {
     *     where { query.proposition.contextId eq ctx; query.proposition.status eq status }
     * }
     * ```
     *
     * Predicates filter the *projected* values: **property predicates** on the root map
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
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>> {
        if (!grammar.supportsVectorSearch) {
            throw UnsupportedOperationException(
                "Vector search is not supported on this backend (${grammar::class.simpleName} has no native vector index)."
            )
        }
        require(graphClass.isAnnotationPresent(GraphView::class.java)) {
            "loadNearest { where { } } currently supports @GraphView types; ${graphClass.simpleName} is not a @GraphView"
        }

        autoRegisterSubtypesIfNeeded(graphClass)

        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val viewModel = GraphViewModel.from(graphClass)

        // Render the caller predicate in projected-collection mode: property predicates read the
        // projected root map, and relationship quantifiers (`mentions.any { … }`) become list
        // predicates over the projected relationship collection — both run in the post-projection
        // WHERE without traversing the vector-sourced node. Referencing a relationship the view does
        // not project throws (from buildWhereClause's relationship lookup).
        val whereResult = if (querySpec.conditions.isNotEmpty()) {
            CypherGenerator.buildWhereClause(querySpec.conditions, viewModel, grammar, projectedCollectionMode = true)
        } else null
        val callerBindings = CypherGenerator.extractBindings(querySpec.conditions, viewModel)

        val rootFragmentType = viewModel.rootFragment.fragmentType
        val vectorSpec = VectorIndexResolver.resolve(rootFragmentType, null, VECTOR_TOP_K_PARAM, VECTOR_QUERY_PARAM)
        val thresholdParam = if (threshold != null) VECTOR_THRESHOLD_PARAM else null
        val query = GraphViewQueryBuilder.forView(graphClass, grammar)
            .buildVectorQuery(vectorSpec, thresholdParam, whereResult?.whereClause)

        val bindings = buildMap<String, Any?> {
            put(VECTOR_TOP_K_PARAM, topK)
            put(VECTOR_QUERY_PARAM, vector)
            if (threshold != null) put(VECTOR_THRESHOLD_PARAM, threshold)
            putAll(callerBindings)
        }

        return executeVectorSearch(graphClass, query, bindings)
    }

    /**
     * Runs a vector-search query and packages each `{ value, score }` row into a [Scored] instance,
     * transforming the inner `value` with the same machinery [loadAll] uses, then snapshots for
     * dirty tracking.
     */
    private fun <T : Any> executeVectorSearch(
        graphClass: Class<T>,
        query: String,
        bindings: Map<String, Any?>,
    ): List<Scored<T>> {
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(query).bind(bindings).transform(Map::class.java)
        )

        val transform = TransformPostProcessor<Any, T>(graphClass, subtypeRegistry)
        val scored = rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val map = row as Map<String, Any?>
            val valueData = map["value"] as Any
            val value = transform.apply(listOf(valueData)).first()
            val score = (map["score"] as Number).toDouble()
            Scored(value, score)
        }

        // Snapshot for dirty tracking, consistent with loadAll.
        snapshotResults(graphClass, scored.map { it.value })

        return scored
    }


    /**
     * Builds query context from a GraphQuerySpec, extracting the view model, WHERE clause, and bindings.
     */
    private fun <Q : Any> buildQueryContext(
        graphClass: Class<*>,
        querySpec: GraphQuerySpec<Q>
    ): QueryContext {
        val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            GraphViewModel.from(graphClass)
        } else {
            null
        }

        val whereResult = if (querySpec.conditions.isNotEmpty()) {
            CypherGenerator.buildWhereClause(querySpec.conditions, viewModel, grammar)
        } else null

        val bindings = CypherGenerator.extractBindings(querySpec.conditions, viewModel)

        return QueryContext(
            viewModel, whereResult?.whereClause, bindings,
            whereResult?.prologs ?: emptyList(),
            whereResult?.bridgeVariables ?: emptyList()
        )
    }

    /**
     * Extracts fragment model and root field name for snapshotting.
     */
    private fun extractSnapshotMetadata(clazz: Class<*>): Pair<FragmentModel, String?> {
        return if (clazz.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(clazz)
            val fragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
            Pair(fragmentModel, viewModel.rootFragment.fieldName)
        } else {
            Pair(FragmentModel.from(clazz), null)
        }
    }

    /**
     * Takes snapshots of loaded objects for dirty tracking.
     * Delegates to SessionManager to handle the snapshotting details.
     *
     * For polymorphic results (when graphClass is abstract/sealed), snapshot each object
     * using its actual runtime class rather than the query target class.
     */
    private fun <T : Any> snapshotResults(graphClass: Class<T>, results: List<T>) {
        if (results.isEmpty()) return

        // Check if the graphClass is abstract or sealed (indicates polymorphic query)
        val isPolymorphic = graphClass.kotlin.isAbstract || graphClass.kotlin.isSealed

        if (isPolymorphic) {
            // Snapshot each object using its actual runtime class
            results.forEach { obj ->
                val (fragmentModel, rootFragmentFieldName) = extractSnapshotMetadata(obj.javaClass)
                sessionManager.snapshot(obj, fragmentModel, rootFragmentFieldName)
            }
        } else {
            // Non-polymorphic: use the query target class for all results
            val (fragmentModel, rootFragmentFieldName) = extractSnapshotMetadata(graphClass)
            sessionManager.snapshotAll(results, fragmentModel, rootFragmentFieldName)
        }
    }

    /**
     * Deletes a graph object (GraphView or GraphFragment) from the database by its ID.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @return The number of nodes deleted (0 or 1)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>): Int {
        return delete(id, graphClass, null, CascadeType.NONE)
    }

    /**
     * Deletes a graph object by its ID, applying a cascade policy scoped by the view.
     *
     * Mirrors [save]'s cascade parameter on the delete path. The cascade boundary is the shape
     * of the view passed in — see [delete] (the four-arg overload) for the full semantics.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param cascade The cascade policy (default NONE = root-only DETACH DELETE)
     * @return The number of nodes deleted (root plus any cascaded fragments)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, cascade: CascadeType): Int {
        return delete(id, graphClass, null, cascade)
    }

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
    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?): Int {
        return delete(id, graphClass, whereClause, CascadeType.NONE)
    }

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
     *   has no relationships left once the root is removed. Requires a grammar that supports it
     *   (see [validateCascadeSupport]).
     *
     * Ids are always bound as parameters; nothing is interpolated into the Cypher.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param whereClause Additional WHERE clause conditions (without WHERE keyword)
     * @param cascade The cascade policy
     * @return The number of nodes deleted (root plus any cascaded fragments)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?, cascade: CascadeType): Int {
        validateCascadeSupport(cascade)

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val idCondition = builder.buildIdWhereClause("id")

        val fullWhereClause = if (whereClause != null) {
            "$idCondition AND $whereClause"
        } else {
            idCondition
        }

        val query = if (cascade == CascadeType.NONE) {
            // Preserve the legacy root-only delete byte-for-byte.
            builder.buildDeleteQuery(fullWhereClause)
        } else {
            builder.buildCascadeDeleteQuery(fullWhereClause, cascade)
        }

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(Int::class.java)
        )
    }

    /**
     * Deletes all graph objects (GraphViews or GraphFragments) of a given type from the database.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * WARNING: This will delete ALL nodes matching the labels. Use with caution.
     *
     * @param graphClass The graph object class
     * @return The number of nodes deleted
     */
    fun <T : Any> deleteAll(graphClass: Class<T>): Int {
        return deleteAll(graphClass, null as String?)
    }

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
    fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val query = builder.buildDeleteQuery(whereClause)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .transform(Int::class.java)
        )
    }

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
    ): Int {
        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val ctx = buildQueryContext(graphClass, querySpec)
        val query = builder.buildDeleteQuery(ctx.whereClause, ctx.prologs, ctx.bridgeVariables)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(ctx.bindings)
                .transform(Int::class.java)
        )
    }

    /**
     * Saves a graph object (GraphView or GraphFragment) to the database.
     *
     * For objects loaded in this session, only dirty fields are written (optimized save).
     * For objects not in the session, all fields are written (full save).
     *
     * Uses MERGE pattern: creates if not exists, updates if exists.
     *
     * For GraphViews:
     * - Saves root fragment
     * - Detects relationship changes (added/removed)
     * - Deletes removed relationships according to cascade policy
     * - Merges added relationships and their fragments
     *
     * @param obj The object to save
     * @param cascade The cascade policy for deleted relationships (default: NONE - only delete relationship)
     * @return The saved object
     */
    fun <T : Any> save(obj: T, cascade: CascadeType = CascadeType.NONE): T {
        validateCascadeSupport(cascade)
        val graphClass = obj.javaClass

        val mergeBuilder = GraphObjectMergeBuilder.forClass(
            graphClass,
            objectMapper,
            sessionManager
        )
        val statements = mergeBuilder.buildMergeStatements(obj, cascade)

        // Execute all statements in order
        statements.forEach { statement ->
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(statement.statement)
                    .bind(statement.bindings)
            )
        }

        // Update snapshot after save
        snapshotResults(graphClass, listOf(obj))

        return obj
    }

    /**
     * Appends `SKIP $_skip` / `LIMIT $_limit` (bound, not inlined) after the query's `RETURN … ORDER
     * BY …` tail. A no-op when neither is set. SKIP precedes LIMIT, per Cypher.
     */
    private fun applyPagination(
        query: String,
        bindings: Map<String, Any?>,
        skip: Int?,
        limit: Int?,
    ): Pair<String, Map<String, Any?>> {
        if (skip == null && limit == null) return query to bindings
        val merged = bindings.toMutableMap()
        val sb = StringBuilder(query)
        if (skip != null) {
            sb.append("\nSKIP \$$SKIP_PARAM")
            merged[SKIP_PARAM] = skip
        }
        if (limit != null) {
            sb.append("\nLIMIT \$$LIMIT_PARAM")
            merged[LIMIT_PARAM] = limit
        }
        return sb.toString() to merged
    }

    private companion object {
        // Bound-parameter names for the vector search. Underscored to avoid clashing with any
        // user-supplied bindings on future filtered vector queries.
        const val VECTOR_TOP_K_PARAM = "_vectorTopK"
        const val VECTOR_QUERY_PARAM = "_vectorQuery"
        const val VECTOR_THRESHOLD_PARAM = "_vectorThreshold"

        // Bound-parameter names for DSL pagination.
        const val SKIP_PARAM = "_skip"
        const val LIMIT_PARAM = "_limit"
    }

    private fun validateCascadeSupport(cascade: CascadeType) {
        if (cascade == CascadeType.DELETE_ORPHAN && !grammar.supportsOrphanDelete) {
            throw UnsupportedOperationException(
                "CASCADE DELETE_ORPHAN is not supported on this database. " +
                "FalkorDB does not correctly handle DELETE followed by a pattern " +
                "predicate in the same query (see FalkorDB/FalkorDB#1890). " +
                "Use CASCADE DELETE_ALL or CASCADE NONE instead."
            )
        }
    }
}
