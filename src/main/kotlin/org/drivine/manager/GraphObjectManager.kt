package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.mapper.TransformPostProcessor
import org.drivine.query.FullTextSearchPlanner
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.QuerySpecification
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.Stamping
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorSearchPlanner
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderClauseResult
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.QueryIndexAdvisor
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.session.SessionManager
import org.drivine.store.StoreIdentity
import org.slf4j.LoggerFactory

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
 *
 * Deprecated: a save writes what differs from the session's snapshot, which is what this manager
 * last saw and not what the store holds. A node that anything else changed or deleted gets a partial
 * save. [StatelessGraphObjectManager] keeps no snapshot.
 */
@Deprecated(
    "Use StatelessGraphObjectManager (GraphObjectManagerFactory.stateless()). A save by this manager depends on its " +
        "session's snapshot, so a node changed elsewhere gets a partial save. See the README: Migrating from GraphObjectManager.",
)
class GraphObjectManager internal constructor(
    private val persistenceManager: PersistenceManager,
    internal val sessionManager: SessionManager,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry,
    /** A save writes a new stamp on each node it changes. This manager does not check one. */
    private val stamping: Stamping,
) : GraphObjectOperations {

    constructor(
        persistenceManager: PersistenceManager,
        sessionManager: SessionManager,
        objectMapper: ObjectMapper,
        subtypeRegistry: SubtypeRegistry,
    ) : this(persistenceManager, sessionManager, objectMapper, subtypeRegistry, Stamping(checked = false))

    @Suppress("DEPRECATION")
    private val logger = LoggerFactory.getLogger(GraphObjectManager::class.java)

    /**
     * The name of the database this manager is connected to.
     */
    override val database: String
        get() = persistenceManager.database

    /**
     * Stable identity of the store this manager's objects live in — see [StoreIdentity].
     *
     * Exposed here because [database] is not enough to answer the question callers actually have.
     * A datasource name says which entry in the configuration was used; it says nothing about which
     * store answered, so a process wired to the wrong one reports the same name as a process wired
     * to the right one. Ask this when something must decide whether it is talking to the store its
     * data came from.
     *
     * Repository-style code typically holds only a [GraphObjectManager]. Without this it would have
     * to be handed a second, redundant reference to the same [PersistenceManager] purely to reach an
     * identity this object already has — a seam that invites the two to drift apart.
     *
     * Resolution and caching belong to the underlying manager, which assigns an identity on first
     * sight of an unstamped store and then holds it for the life of the manager.
     */
    override val storeIdentity: StoreIdentity
        get() = persistenceManager.storeIdentity

    internal val grammar = persistenceManager.grammar

    /**
     * Reads a node's stored property keys, so a save of an untracked object under [NullPolicy.CLEAR]
     * can still remove the `@PropertyBag` keys it dropped, and the labels an open `@NodeLabels` field
     * recorded as its own. Only consulted on that path.
     */
    internal val storedKeys = object : StoredPropertyKeys {
        override fun of(labels: String, idProperty: String, id: Any): Set<String> =
            persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement("MATCH (n:$labels {$idProperty: \$id}) RETURN keys(n)")
                    .bind(mapOf("id" to id))
                    .transform(List::class.java)
            )?.map { it.toString() }?.toSet().orEmpty()

        override fun ownedLabels(labels: String, idProperty: String, id: Any, property: String): List<String> =
            persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement("MATCH (n:$labels {$idProperty: \$id}) RETURN coalesce(n.`$property`, [])")
                    .bind(mapOf("id" to id))
                    .transform(List::class.java)
            )?.map { it.toString() }.orEmpty()
    }

    /**
     * Relationships whose type is known only at runtime, between stored nodes — see [EdgeOperations].
     * Kept apart from the rest of this class, which is about declared shapes.
     */
    override val edges: EdgeOperations = EdgeOperations(this, persistenceManager)

    private val batchSave = BatchSaveOperations(objectMapper, sessionManager, UNWIND_CHUNK_SIZE, grammar, storedKeys, stamping)

    /** The statements that save [items] in batches, as [saveAll] runs them. */
    internal fun batchSpecs(items: List<Any>, nullPolicy: NullPolicy): List<QuerySpecification<*>> =
        if (items.isEmpty()) emptyList() else batchSave.buildBatchSpecs(items, CascadeType.NONE, nullPolicy)

    /**
     * Forgets every tracked object: each one's next save writes all fields, until it is loaded again.
     *
     * The session outlives transactions and is bounded (see [SessionManager.maxEntries]), so this is
     * never needed for memory. Call it to scope dirty tracking to a unit of work — a request or a job —
     * so a save never diffs against a snapshot taken by earlier, unrelated work.
     */
    fun clearSession() = sessionManager.clear()

    private val indexAdvisor = QueryIndexAdvisor(persistenceManager.indexes, persistenceManager.constraints)

    /**
     * What to do when an ordered or keyset-paginated query has no range index to seek into.
     *
     * Defaults to [IndexAdvicePolicy.WARN], which logs once per distinct label/property combination.
     * Set [IndexAdvicePolicy.FAIL] in development or CI to turn an unindexed page into an error, or
     * [IndexAdvicePolicy.OFF] to say nothing — ordering without an index is correct, just unindexed,
     * and on a small collection that is a perfectly reasonable thing to do.
     */
    override var indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN

    override fun <T : Any> loadAll(graphClass: Class<T>): List<T> {
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

    override fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T> {
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

    override fun <T : Any> count(graphClass: Class<T>): Long {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(builder.buildCountQuery())
                .transform(Long::class.java)
        )
    }

    override fun <T : Any> count(graphClass: Class<T>, whereClause: String): Long {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(builder.buildCountQuery(whereClause))
                .transform(Long::class.java)
        )
    }

    override fun <T : Any, Q : Any> count(
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
    internal fun autoRegisterSubtypesIfNeeded(graphClass: Class<*>) {
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

        // For GraphViews, also register subtypes for the root fragment and every relationship target.
        // The root fragment can itself be polymorphic — e.g. a recursive view whose node is a sealed
        // fragment — and needs the same registry population a directly-loaded sealed fragment gets.
        if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(graphClass)
            autoRegisterSubtypesRecursive(viewModel.rootFragment.fragmentType, registeredClasses)
            viewModel.relationships.forEach { rel ->
                autoRegisterSubtypesRecursive(rel.elementType, registeredClasses)
            }
        }
    }

    override fun <T : Any, Q : Any> loadAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val ctx = buildQueryContext(graphClass, querySpec, supportsSeek = true)

        // Process ORDER BY clause - separate root orders from collection sorts
        val relationshipNames = ctx.viewModel?.relationships?.map { it.fieldName }?.toSet() ?: emptySet()
        val orderResult = if (querySpec.orders.isNotEmpty()) {
            CypherGenerator.processOrders(querySpec.orders, relationshipNames)
        } else {
            OrderClauseResult(null, emptyList())
        }

        require(querySpec.seekValues.isEmpty() || querySpec.skip == null) {
            "seek and skip cannot be used together"
        }
        val keysetPlan = if (querySpec.seekValues.isNotEmpty()) {
            KeysetPlanner.plan(
                orderResult.rootOrders,
                querySpec.seekValues,
                orderResult.collectionSorts.size,
                guardAgainstNulls = grammar.indexesExcludeNulls,
            )
        } else {
            null
        }
        val effectiveWhereClause = listOfNotNull(ctx.whereClause, keysetPlan?.predicate)
            .joinToString(" AND ") { "($it)" }
            .takeIf { it.isNotEmpty() }
        keysetPlan?.bindings?.keys?.forEach { reserved ->
            require(reserved !in ctx.bindings) {
                "Keyset cursor parameter \$$reserved collides with a where-clause binding"
            }
        }
        val effectiveBindings = ctx.bindings + (keysetPlan?.bindings ?: emptyMap())

        adviseOnIndexes(graphClass, ctx.viewModel, orderResult.rootOrders, querySpec, isKeyset = keysetPlan != null)

        val baseQuery = if (querySpec.depthOverrides.isNotEmpty() && builder is GraphViewQueryBuilder) {
            builder.buildQuery(effectiveWhereClause, orderResult.orderByClause, orderResult.collectionSorts, querySpec.depthOverrides, ctx.prologs, ctx.bridgeVariables)
        } else {
            builder.buildQuery(effectiveWhereClause, orderResult.orderByClause, orderResult.collectionSorts, ctx.prologs, ctx.bridgeVariables)
        }

        // SKIP/LIMIT are the final clauses, after RETURN … ORDER BY …. For a @GraphView each root is
        // one row (relationships are pattern comprehensions in the projection), so LIMIT bounds root
        // entities and keeps their collections intact.
        val (query, bindings) = applyPagination(baseQuery, effectiveBindings, querySpec.skip, querySpec.limit)

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

    override fun <T : Any> load(id: String, graphClass: Class<T>): T? {
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

    override fun <T : Any> loadNearest(
        graphClass: Class<T>,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        searchK: Int?,
        partitionLabel: String?,
    ): List<Scored<T>> = loadNearest(graphClass, null, vector, topK, threshold, searchK, partitionLabel)

    override fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        searchK: Int?,
        partitionLabel: String?,
    ): List<Scored<T>> {
        requireValidSearchK(topK, searchK)
        return executeScoredSearch(
            graphClass,
            VectorSearchPlanner.plan(graphClass, property, vector, topK, threshold, grammar, searchK, partitionLabel),
        )
    }

    override fun <T : Any, Q : Any> loadNearest(
        graphClass: Class<T>,
        queryObject: Q,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        searchK: Int?,
        partitionLabel: String?,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>> {
        requireValidSearchK(topK, searchK)
        val querySpec = GraphQuerySpec(queryObject).apply(spec)
        return executeScoredSearch(
            graphClass,
            VectorSearchPlanner.planFiltered(
                graphClass, querySpec, vector, topK, threshold, grammar, searchK, partitionLabel,
            ),
        )
    }

    /**
     * Guards the over-fetch knob. A [searchK] below [topK] would ask the index for fewer rows than the
     * caller wants back, which can only lose results — always a mistake rather than a tuning choice.
     */
    private fun requireValidSearchK(topK: Int, searchK: Int?) {
        if (searchK != null && searchK < topK) {
            throw IllegalArgumentException(
                "searchK ($searchK) must be >= topK ($topK): searchK widens the index search and topK " +
                    "trims the surviving rows, so a smaller searchK can only discard results."
            )
        }
    }

    override fun <T : Any> loadMatching(
        graphClass: Class<T>,
        query: String,
        topK: Int,
        threshold: Double,
    ): List<Scored<T>> = loadMatching(graphClass, null, query, topK, threshold)

    override fun <T : Any> loadMatching(
        graphClass: Class<T>,
        property: String?,
        query: String,
        topK: Int,
        threshold: Double,
    ): List<Scored<T>> =
        executeScoredSearch(graphClass, FullTextSearchPlanner.plan(graphClass, property, query, topK, threshold, grammar))

    override fun <T : Any, Q : Any> loadMatching(
        graphClass: Class<T>,
        queryObject: Q,
        query: String,
        topK: Int,
        threshold: Double,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>> {
        val querySpec = GraphQuerySpec(queryObject).apply(spec)
        return executeScoredSearch(
            graphClass,
            FullTextSearchPlanner.planFiltered(graphClass, querySpec, query, topK, threshold, grammar),
        )
    }

    /**
     * Reports a vector search that returned fewer rows than asked for.
     *
     * Filters run *after* the index yields, so a scoped search returns roughly `k × selectivity` rows —
     * ask for 40 against a 22%-selective filter and about 9 come back, drawn from the globally-nearest
     * rather than the nearest within scope. That is silent otherwise: the caller sees a short list and
     * has no way to tell dilution from a genuinely small result.
     *
     * Logged at DEBUG, not WARN, and deliberately. A view with required relationships routinely returns
     * fewer than `topK` — documented, intended behaviour — so warning would be noise that teaches people
     * to ignore the warning. Suppressing it behind a "shortfall greater than X" heuristic would mean
     * picking X from a selectivity estimate Drivine does not have. Turn this logger up when recall looks
     * wrong; the numbers needed to act are all in the line.
     */
    private fun reportShortYield(graphClass: Class<*>, plan: ScoredSearchPlan, returned: Int) {
        val requested = plan.requestedRows ?: return
        if (returned >= requested) return
        if (!logger.isDebugEnabled) return
        logger.debug(
            "Vector search on {} returned {} of {} requested rows; the index was asked for {}. " +
                "Filters apply after the index yields, so a scoped search thins the result — raise " +
                "searchK to widen the search, or search a partition's own index.",
            graphClass.simpleName, returned, requested, plan.indexK,
        )
    }

    /**
     * Runs a planned scored search (vector or full-text) and packages each `{ value, score }` row into
     * a [Scored] instance, transforming the inner `value` with the same machinery [loadAll] uses, then
     * snapshots for dirty tracking. Registers subtypes first so polymorphic dispatch works. Both search
     * kinds share this row shape, so both planners feed this one executor.
     */
    private fun <T : Any> executeScoredSearch(
        graphClass: Class<T>,
        plan: ScoredSearchPlan,
    ): List<Scored<T>> {
        autoRegisterSubtypesIfNeeded(graphClass)

        val rows = persistenceManager.query(
            QuerySpecification.withStatement(plan.cypher).bind(plan.bindings).transform(Map::class.java)
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

        reportShortYield(graphClass, plan, scored.size)

        // Snapshot for dirty tracking, consistent with loadAll.
        snapshotResults(graphClass, scored.map { it.value })

        return scored
    }


    /**
     * Reports when an ordered query has no range index to seek into. See [indexAdvice] for the
     * levels, and the README's pagination section for why the index must mirror the ordering
     * exactly rather than merely overlap it.
     *
     * Only root-level orders participate — a collection sort happens inside a pattern comprehension,
     * where no index applies.
     */
    private fun <Q : Any> adviseOnIndexes(
        graphClass: Class<*>,
        viewModel: GraphViewModel?,
        rootOrders: List<OrderSpec>,
        querySpec: GraphQuerySpec<Q>,
        isKeyset: Boolean,
    ) {
        if (indexAdvice == IndexAdvicePolicy.OFF || rootOrders.isEmpty()) return
        // Only Neo4j can turn an index into an ordered seek that stops at the page boundary. On the
        // other engines a blocking sort sits between scan and limit whatever is indexed, so there is
        // no index worth recommending.
        if (!grammar.supportsIndexBackedOrdering) return

        val fragmentType = viewModel?.rootFragment?.fragmentType ?: graphClass
        val fragmentModel = FragmentModel.from(fragmentType)
        val label = fragmentModel.labels.firstOrNull() ?: return
        val rootAlias = viewModel?.rootFragment?.fieldName

        indexAdvisor.check(
            policy = indexAdvice,
            label = label,
            properties = rootOrders.map { it.propertyPath.substringAfter(".") },
            operation = if (isKeyset) "seek" else "orderBy",
            pinnedBy = pinningEqualities(querySpec, rootAlias),
            uniqueByContract = setOfNotNull(fragmentModel.nodeIdProperty),
        )
    }

    /**
     * Root properties the query fixes to a single value at the top level of its `where`.
     *
     * Only [WhereCondition.PropertyCondition] entries in the spec's own condition list count: those
     * are AND-ed, so each genuinely narrows the result. Conditions nested inside an `anyOf` are not
     * in this list, which is what we want — an equality in one branch of an OR pins nothing.
     */
    private fun <Q : Any> pinningEqualities(querySpec: GraphQuerySpec<Q>, rootAlias: String?): Set<String> =
        querySpec.conditions
            .filterIsInstance<WhereCondition.PropertyCondition>()
            .filter { it.operator == ComparisonOperator.EQUALS }
            .filter { rootAlias == null || it.propertyPath.substringBefore(".") == rootAlias }
            .map { it.propertyPath.substringAfter(".") }
            .toSet()

    /**
     * Builds query context from a GraphQuerySpec, extracting the view model, WHERE clause, and bindings.
     *
     * @param supportsSeek whether the calling operation applies [GraphQuerySpec.seek]. Only
     *   `loadAll` does; every other path would silently ignore the cursor and return (or delete, or
     *   count) the whole unpaginated result, so they reject it here instead.
     */
    private fun <Q : Any> buildQueryContext(
        graphClass: Class<*>,
        querySpec: GraphQuerySpec<Q>,
        supportsSeek: Boolean = false,
    ): QueryContext {
        require(supportsSeek || querySpec.seekValues.isEmpty()) {
            "seek is only supported by loadAll; this operation would ignore the cursor"
        }

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
    internal fun <T : Any> snapshotResults(graphClass: Class<T>, results: List<T>) {
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

    override fun <T : Any> delete(id: String, graphClass: Class<T>): Int {
        return delete(id, graphClass, null, CascadeType.NONE)
    }

    override fun <T : Any> delete(id: String, graphClass: Class<T>, cascade: CascadeType): Int {
        return delete(id, graphClass, null, cascade)
    }

    override fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?): Int {
        return delete(id, graphClass, whereClause, CascadeType.NONE)
    }

    override fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?, cascade: CascadeType): Int {
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

    override fun <T : Any> deleteAll(graphClass: Class<T>): Int {
        return deleteAll(graphClass, null as String?)
    }

    override fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int {
        val builder = GraphObjectQueryBuilder.forClass(graphClass, grammar)
        val query = builder.buildDeleteQuery(whereClause)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .transform(Int::class.java)
        )
    }

    override fun <T : Any, Q : Any> deleteAll(
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
     * @param nullPolicy How null field values are treated. Default [NullPolicy.IGNORE] — a merge-patch
     *   that writes only non-null fields and never clears anything (so a partially-loaded object never
     *   destroys stored data, embeddings included). Pass [NullPolicy.CLEAR] for a full overwrite that
     *   clears null fields. Uniform for all fields — see [NullPolicy].
     * @return The saved object
     */
    @JvmOverloads
    fun <T : Any> save(obj: T, cascade: CascadeType = CascadeType.NONE, nullPolicy: NullPolicy = NullPolicy.IGNORE): T {
        validateCascadeSupport(cascade)
        val graphClass = obj.javaClass

        val mergeBuilder = GraphObjectMergeBuilder.forClass(
            graphClass,
            objectMapper,
            sessionManager,
            grammar,
            storedKeys,
            stamping,
        )
        val statements = mergeBuilder.buildMergeStatements(obj, cascade, nullPolicy)

        // Execute all statements in order
        SaveExecutor(persistenceManager).execute(statements)

        // Update snapshot after save
        snapshotResults(graphClass, listOf(obj))

        return obj
    }

    /**
     * Batch-saves a collection of graph objects, mirroring [save]'s per-item semantics (cascade,
     * change detection, MERGE identity) while cutting round trips. The whole call is **atomic** —
     * inside an ambient transaction it joins it, otherwise it runs in a single transaction of its own
     * (see [PersistenceManager.executeBatch]) — so a failure on any item rolls the whole call back and
     * nothing is persisted.
     *
     * Heterogeneous collections are supported: items are grouped by runtime class and each group is
     * saved with its own model. The returned list is [objs] in input order, unchanged.
     *
     * **Batching strategy — "UNWIND the roots, pipeline the rest".** For each homogeneous group the
     * root-fragment upserts collapse into chunked `UNWIND $rows AS row MERGE (n:…{id}) SET n += row.props`
     * statements (sub-linear round trips); relationship and cascade statements stay per-item, exactly as
     * [save] builds them. Consequences worth knowing:
     * - **Roots with a `@PropertyBag`/`@CompositeProperty` fall back** to the full per-item path — the
     *   clear-stale `REMOVE` needs per-object keys an UNWIND can't express. Correct, just not batched.
     * - The batched root upsert writes the root properties via `n += props`. Null handling follows
     *   [nullPolicy], uniformly with [save]: under [NullPolicy.IGNORE] (default) nulls are dropped from
     *   `props` and never clear anything (merge-patch); under [NullPolicy.CLEAR] a null is kept so
     *   `+= {x: null}` clears the property. Relationship change-detection and cascade are per-item.
     *
     * @param objs the objects to save (any mix of `@GraphView` / `@NodeFragment` types)
     * @param cascade the cascade policy for removed relationships, applied per item (default NONE)
     * @param nullPolicy how null field values are treated (default [NullPolicy.IGNORE]); see [NullPolicy]
     * @return the saved objects in input order; empty in → empty out
     */
    @JvmOverloads
    fun <T : Any> saveAll(objs: Collection<T>, cascade: CascadeType = CascadeType.NONE, nullPolicy: NullPolicy = NullPolicy.IGNORE): List<T> {
        validateCascadeSupport(cascade)
        val items = objs.toList()
        if (items.isEmpty()) return emptyList()

        // Statement building lives in BatchSaveOperations; the manager owns execution + snapshotting.
        persistenceManager.executeBatch(batchSave.buildBatchSpecs(items, cascade, nullPolicy))

        // Update snapshots after the batch commits, each under its own runtime class.
        items.forEach { @Suppress("UNCHECKED_CAST") snapshotResults(it.javaClass as Class<Any>, listOf<Any>(it)) }

        return items
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
        // Scored-search bound-parameter names now live on their planners (VectorSearchPlanner /
        // FullTextSearchPlanner), which build those queries.

        // Bound-parameter names for DSL pagination.
        const val SKIP_PARAM = "_skip"
        const val LIMIT_PARAM = "_limit"

        // Max rows per UNWIND statement in saveAll — caps server-side batch size; each chunk is one
        // statement (and, when not in an ambient transaction, still within the single batch transaction).
        const val UNWIND_CHUNK_SIZE = 1000
    }

    private fun validateCascadeSupport(cascade: CascadeType) {
        if (cascade == CascadeType.DELETE_ORPHAN && !grammar.supportsOrphanDelete) {
            throw UnsupportedOperationException(
                "CASCADE DELETE_ORPHAN is not supported on this database. " +
                grammar.orphanDeleteLimit?.let { "$it " }.orEmpty() +
                "Use CASCADE DELETE_ALL or CASCADE NONE instead."
            )
        }
    }
}
