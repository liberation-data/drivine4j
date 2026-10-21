package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphBackends
import org.drivine.annotation.NodeFragment
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.mapper.TransformPostProcessor
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.QuerySpecification
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.Stamping
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.QueryIndexAdvisor
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.grammar.CypherGrammar
import org.drivine.session.SessionManager
import org.drivine.store.StoreIdentity
import org.slf4j.LoggerFactory

/**
 * Manager for working with graph objects (GraphViews and GraphFragments).
 * Provides methods to query and retrieve graph-mapped objects from the database.
 *
 * Maintains a session to track loaded objects and enable dirty checking for optimized saves.
 *
 * Deprecated: a save writes what differs from the session's snapshot, which is what this manager
 * last saw and not what the store holds. A node that anything else changed or deleted gets a partial
 * save. [StatelessGraphObjectManager] keeps no snapshot.
 *
 * [save] and [saveAll] are written in Cypher alone. On a store that does not speak it, as Oracle does
 * not, they throw `UnsupportedOperationException`; loading, querying, deleting and [edges] work there.
 */
@Deprecated(
    "Use StatelessGraphObjectManager (GraphObjectManagerFactory.stateless()). A save by this manager depends on its " +
        "session's snapshot, so a node changed elsewhere gets a partial save. See the README: Migrating from GraphObjectManager.",
)
class GraphObjectManager internal constructor(
    private val persistenceManager: PersistenceManager,
    internal val sessionManager: SessionManager,
    private val objectMapper: ObjectMapper,
    internal val subtypeRegistry: SubtypeRegistry,
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

    /** What writes each operation as a statement in the store's language. */
    internal val backend: GraphBackend = GraphBackends.of(persistenceManager, objectMapper)

    /** The grammar of a store that speaks Cypher; null for one that does not. */
    internal val grammar: CypherGrammar?
        get() = backend.cypherGrammar

    /**
     * Reads a node's stored property keys, so a save of an untracked object under [NullPolicy.CLEAR]
     * can still remove the `@PropertyBag` keys it dropped, and the labels an open `@NodeLabels` field
     * recorded as its own. Only consulted on that path.
     */
    internal val storedKeys: StoredPropertyKeys
        get() = backend.storedKeys

    /**
     * Relationships whose type is known only at runtime, between stored nodes — see [EdgeOperations].
     * Kept apart from the rest of this class, which is about declared shapes.
     */
    override val edges: EdgeOperations = EdgeOperations(this, persistenceManager)

    /** Grouped reads: the nodes of a fragment by a key, with numbers about each group. See [GroupOperations]. */
    override val groups: GroupOperations = GroupOperations(this, persistenceManager)

    private val batchSave = BatchSaveOperations(objectMapper, sessionManager, BatchSaveOperations.UNWIND_CHUNK_SIZE, grammar, storedKeys, stamping)

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
        // Auto-register subtypes for sealed classes (both top-level and nested relationship targets)
        autoRegisterSubtypesIfNeeded(graphClass)

        val results = persistenceManager.query(backend.loadAll(graphClass).spec().transform(graphClass))
        snapshotResults(graphClass, results)
        return results
    }

    override fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T> {
        // Auto-register subtypes for sealed classes (both top-level and nested relationship targets)
        autoRegisterSubtypesIfNeeded(graphClass)

        val results = persistenceManager.query(backend.loadAll(graphClass, whereClause).spec().transform(graphClass))
        snapshotResults(graphClass, results)
        return results
    }

    override fun <T : Any> count(graphClass: Class<T>): Long =
        persistenceManager.getOne(backend.count(graphClass).spec().transform(Long::class.java))

    override fun <T : Any> count(graphClass: Class<T>, whereClause: String): Long =
        persistenceManager.getOne(backend.count(graphClass, whereClause).spec().transform(Long::class.java))

    override fun <T : Any, Q : Any> count(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): Long {
        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()
        return persistenceManager.getOne(backend.count(graphClass, querySpec).spec().transform(Long::class.java))
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
        // Auto-register subtypes for sealed classes (both top-level and nested relationship targets)
        autoRegisterSubtypesIfNeeded(graphClass)

        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()

        val plan = backend.loadAll(graphClass, querySpec)
        adviseOnIndexes(graphClass, plan.rootOrders, querySpec, isKeyset = plan.keyset)

        val results = persistenceManager.query(plan.statement.spec().transform(graphClass))
        snapshotResults(graphClass, results)
        return results
    }

    override fun <T : Any> load(id: String, graphClass: Class<T>): T? {
        // Auto-register subtypes for sealed classes (both top-level and nested relationship targets)
        autoRegisterSubtypesIfNeeded(graphClass)

        val result = persistenceManager.maybeGetOne(
            backend.load(graphClass).spec(mapOf(GraphBackend.ID_PARAM to id)).transform(graphClass)
        )
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
        return executeScoredSearch(graphClass, backend.nearest(graphClass, property, null, vector, topK, threshold, searchK, partitionLabel))
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
        return executeScoredSearch(graphClass, backend.nearest(graphClass, null, querySpec, vector, topK, threshold, searchK, partitionLabel))
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
        executeScoredSearch(graphClass, backend.matching(graphClass, property, null, query, topK, threshold))

    override fun <T : Any, Q : Any> loadMatching(
        graphClass: Class<T>,
        queryObject: Q,
        query: String,
        topK: Int,
        threshold: Double,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>> {
        val querySpec = GraphQuerySpec(queryObject).apply(spec)
        return executeScoredSearch(graphClass, backend.matching(graphClass, null, querySpec, query, topK, threshold))
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
     * where no index applies. Nor does one to an order that places its nulls: it sorts by the test
     * for null before the value, and an index holds no node without the property, so the query
     * sorts whatever is indexed and there is no index to recommend. Nor to an order by an
     * expression, whose value no index holds.
     */
    private fun <Q : Any> adviseOnIndexes(
        graphClass: Class<*>,
        rootOrders: List<OrderTerm>,
        querySpec: GraphQuerySpec<Q>,
        isKeyset: Boolean,
    ) {
        if (indexAdvice == IndexAdvicePolicy.OFF || rootOrders.isEmpty()) return
        if (!backend.supportsIndexBackedOrdering) return
        if (rootOrders.any { it.nulls != null || it !is OrderSpec }) return

        val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null
        val fragmentType = viewModel?.rootFragment?.fragmentType ?: graphClass
        val fragmentModel = FragmentModel.from(fragmentType)
        val label = fragmentModel.labels.firstOrNull() ?: return
        val rootAlias = viewModel?.rootFragment?.fieldName

        indexAdvisor.check(
            policy = indexAdvice,
            label = label,
            properties = rootOrders.filterIsInstance<OrderSpec>().map { it.propertyPath.substringAfter(".") },
            operation = if (isKeyset) "seek" else "orderBy",
            pinnedBy = pinningEqualities(querySpec, rootAlias),
            // The node id is unique by the model's own contract (it is the MERGE key and the load
            // key), so it terminates an ordering whether or not anyone declared a constraint on it.
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
        if (cascade == CascadeType.DELETE_ALL || cascade == CascadeType.DELETE_ORPHAN) refuseCascadeBelowDepth(id, graphClass, whereClause)
        return persistenceManager.getOne(
            backend.delete(graphClass, whereClause, cascade).spec(mapOf(GraphBackend.ID_PARAM to id)).transform(Int::class.java)
        )
    }

    override fun <T : Any> deleteAll(graphClass: Class<T>): Int {
        return deleteAll(graphClass, null as String?)
    }

    override fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int =
        persistenceManager.getOne(backend.deleteAll(graphClass, whereClause).spec().transform(Int::class.java))

    override fun <T : Any, Q : Any> deleteAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): Int {
        val querySpec = GraphQuerySpec(queryObject)
        querySpec.spec()
        return persistenceManager.getOne(backend.deleteAll(graphClass, querySpec).spec().transform(Int::class.java))
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
     * @throws UnsupportedOperationException on a store that does not speak Cypher (Oracle), and for
     *   [CascadeType.DELETE_ORPHAN] on Memgraph
     */
    @JvmOverloads
    fun <T : Any> save(obj: T, cascade: CascadeType = CascadeType.NONE, nullPolicy: NullPolicy = NullPolicy.IGNORE): T {
        requireCypher()
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
        SaveExecutor(persistenceManager, backend).execute(statements)

        // Update snapshot after save
        snapshotResults(graphClass, listOf(obj))

        return obj
    }

    /**
     * Batch-saves a collection of graph objects, mirroring [save]'s per-item semantics (cascade,
     * change detection, MERGE identity) while cutting round trips. The whole call is **atomic** —
     * inside an ambient transaction it joins it, otherwise it runs in a single transaction of its own
     * (see [PersistenceManager.executeBatch]) — so a failure on any item rolls the whole call back and
     * nothing is persisted, where the engine has transactions to roll back
     * ([PersistenceManager.atomicBatches]). On FalkorDB a batch of more than one statement is run
     * statement by statement, or in `STRICT` mode refused with [org.drivine.NonAtomicBatchException]
     * before anything is written. This manager writes a statement for each class, and more for a
     * view; [StatelessGraphObjectManager.saveAll] writes fragments of several classes as one.
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
     * @throws UnsupportedOperationException on a store that does not speak Cypher (Oracle), and for
     *   [CascadeType.DELETE_ORPHAN] on Memgraph
     */
    @JvmOverloads
    fun <T : Any> saveAll(objs: Collection<T>, cascade: CascadeType = CascadeType.NONE, nullPolicy: NullPolicy = NullPolicy.IGNORE): List<T> {
        requireCypher()
        validateCascadeSupport(cascade)
        val items = objs.toList()
        if (items.isEmpty()) return emptyList()

        // Statement building lives in BatchSaveOperations; the manager owns execution + snapshotting.
        persistenceManager.executeBatch(batchSave.buildBatchSpecs(items, cascade, nullPolicy))

        // Update snapshots after the batch commits, each under its own runtime class.
        items.forEach { @Suppress("UNCHECKED_CAST") snapshotResults(it.javaClass as Class<Any>, listOf<Any>(it)) }

        return items
    }

    /** This manager's own save is written in Cypher alone: it is deprecated, and was not carried to other stores. */
    private fun requireCypher() {
        if (grammar == null) {
            throw UnsupportedOperationException(
                "GraphObjectManager.save is not available on ${persistenceManager.type}. Use StatelessGraphObjectManager, from GraphObjectManagerFactory.stateless()."
            )
        }
    }

    /**
     * Refuses, before anything is written, a cascading delete that would stop short of the nodes a
     * recursive relationship reaches. The cascade follows such a relationship to its maxDepth; a node
     * one level further would have its edge to a deleted node dropped and stay, with all it holds.
     * The check is a read run before the delete: on FalkorDB, which has no transactions, nothing
     * else could stop the delete midway.
     */
    private fun refuseCascadeBelowDepth(id: String, graphClass: Class<*>, whereClause: String?) {
        backend.cascadeDepthChecks(graphClass, whereClause).forEach { check ->
            val beyond = persistenceManager.getOne(check.statement.spec(mapOf(GraphBackend.ID_PARAM to id)).transform(Int::class.java))
            if (beyond > 0) {
                val view = check.view.substringAfterLast('.')
                throw IllegalStateException(
                    "Cannot cascade-delete ${graphClass.simpleName} '$id': $view.${check.field} reaches ${check.maxDepth} " +
                        "level${if (check.maxDepth == 1) "" else "s"} deep (maxDepth = ${check.maxDepth}), and $beyond " +
                        "node${if (beyond == 1) "" else "s"} below that would be left behind. " +
                        "Raise maxDepth on ${check.field} to cover the deepest level. Nothing was deleted."
                )
            }
        }
    }

    private fun validateCascadeSupport(cascade: CascadeType) {
        if (cascade == CascadeType.DELETE_ORPHAN && !backend.supportsOrphanDelete) {
            throw UnsupportedOperationException(
                "CASCADE DELETE_ORPHAN is not supported on this database. " +
                backend.orphanDeleteLimit?.let { "$it " }.orEmpty() +
                "Use CASCADE DELETE_ALL or CASCADE NONE instead."
            )
        }
    }
}
