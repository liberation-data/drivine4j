package org.drivine.schema

import org.drivine.connection.ConnectionProvider
import org.drivine.cypher.store.plan.ItemCall
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.manager.PersistenceManager
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Idempotent, drift-aware index management for a single database.
 *
 * Obtain via [org.drivine.manager.PersistenceManager.indexes]. All operations execute in
 * auto-commit mode regardless of any surrounding `@Transactional` context — schema DDL cannot run
 * inside an open data transaction.
 *
 * What each call does on the store is decided by the Cypher compiler's module, whose schema planner
 * (`org.drivine.cypher.store.plan`) the three Drivine libraries share: this manager runs what it decides
 * ([SchemaPlanDriver]). Engine divergence lives in the module's schema of the store ([SchemaGrammar] says which).
 *
 * Example:
 * ```kotlin
 * val result = persistenceManager.indexes.ensure(
 *     VectorIndexSpec(label = "Proposition", property = "embedding", dimensions = 1536)
 * )
 * when (result) {
 *     is EnsureResult.Created, is EnsureResult.AlreadyMatching -> { /* good to go */ }
 *     is EnsureResult.Drift -> persistenceManager.indexes.recreate(spec) // destructive, caller's call
 *     else -> {}
 * }
 * ```
 */
class IndexManager internal constructor(
    private val executor: SchemaExecutor,
    val grammar: SchemaGrammar,
    /** The auto-commit manager this one is of, which an item that ignores case fills its copies in through; null for none. */
    private val owner: PersistenceManager? = null,
) {

    internal constructor(connectionProvider: ConnectionProvider, owner: PersistenceManager? = null) :
        this(SchemaExecutor(connectionProvider), connectionProvider.schemaGrammar, owner = owner)

    /**
     * Which lowercased copies every node of a label has, which an index or a constraint that ignores
     * case fills in when it is ensured. Null for a manager with no [owner] to fill them in through.
     */
    internal val lowerCopies: LowerCopyCatalog? by lazy { owner?.let { LowerCopyCatalog(it, ::run) } }

    /** Runs what the module's schema planner decides on this database: every call here, and the [SchemaManager]'s. */
    internal val driver: SchemaPlanDriver by lazy { SchemaPlanDriver(executor, grammar, { lowerCopies }) }

    private val logger = LoggerFactory.getLogger(IndexManager::class.java)

    /**
     * Runs a schema statement that is not an index: on a connection of its own, committed as it runs, which is the one
     * a schema command holds where one does (see [org.drivine.connection.PinnedConnections]).
     */
    internal fun run(statement: String): List<Any?> = executor.query(statement)

    /**
     * Ensures an index matching [spec] exists. Idempotent — safe to call on every startup.
     *
     * Never destructive: if an index exists with a different shape, returns [EnsureResult.Drift]
     * and changes nothing; call [recreate] to replace it. On an engine that builds an index in the
     * background (Neo4j, FalkorDB), returns once the index, made here or there already, is built. A
     * build that failed, or did not end within 60 seconds, is thrown, as a [org.drivine.DrivineException]
     * that names the index. ([SchemaManager] waits as long as it is told to, and under [SchemaMode.WARN] logs such an
     * index and goes on.)
     * An index that ignores case (see [RangeIndexSpec.ignoreCase]) first fills in the lowercased copy on the nodes of its label that
     * have none, and records that they all have it, whatever the result. Where the index is not there
     * yet, the copies are filled in again even if recorded, as by [recreate], since those a field kept
     * before it lost `@CaseInsensitive` went out of step meanwhile.
     */
    fun ensure(spec: IndexSpec): EnsureResult = checkNotNull(driver.item(ItemCall.ENSURE, spec).result)

    /** Convenience for [ensure] with a [VectorIndexSpec]. */
    @JvmOverloads
    fun ensureVector(
        label: String,
        property: String,
        dimensions: Int,
        similarity: SimilarityFunction = SimilarityFunction.COSINE,
        name: String? = null,
        hnswM: Int? = null,
        hnswEfConstruction: Int? = null,
        engineOptions: List<EngineVectorOptions> = emptyList(),
    ): EnsureResult = ensure(
        VectorIndexSpec(
            label, property, dimensions, similarity, name,
            hnswM, hnswEfConstruction, engineOptions,
        )
    )

    /** Convenience for [ensure] with a [RangeIndexSpec] (single or composite). */
    fun ensureRange(label: String, vararg properties: String): EnsureResult =
        ensure(RangeIndexSpec(label, properties.toList()))

    /** Finds the existing index that [spec] refers to, or null if none exists. */
    fun find(spec: IndexSpec): SchemaItemInfo? {
        if (spec is TextIndexSpec && !driver.store.supportsTextIndex) throw unsupportedTextIndex(spec, grammar.engine)
        val made = spec.made()
        val rows = introspect(driver.store.ddl.listIndexesQuery(made.kind)) ?: return null
        return driver.store.parseIndexRows(rows).firstOrNull { driver.store.matchesIdentity(it, made) }
    }

    /** Lists all managed indexes (vector, range, fulltext, and on Neo4j text) on the database. */
    fun list(): List<SchemaItemInfo> =
        listings().flatMap { query -> introspect(query)?.let { driver.store.parseIndexRows(it) } ?: emptyList() }.distinct()

    /** Every managed index, as [list] gives them, with the aliases its record keeps (see [SchemaAliases]). */
    internal fun recorded(): List<RecordedItem> =
        listings().flatMap { query -> introspect(query)?.let { recordedItems(it, driver.store::parseIndexRows) } ?: emptyList() }.distinct()

    /** The listings of every kind of index the store has, each statement once. */
    private fun listings(): List<String> = listOfNotNull(
        SchemaItemKind.VECTOR_INDEX,
        SchemaItemKind.RANGE_INDEX,
        SchemaItemKind.FULLTEXT_INDEX,
        SchemaItemKind.TEXT_INDEX.takeIf { driver.store.supportsTextIndex },
    ).map { driver.store.ddl.listIndexesQuery(it) }.distinct()

    /** Records [aliases] as all the aliases of [item], an index or a constraint whose record is there; nothing, on a store that keeps none. */
    internal fun alias(item: SchemaItemInfo, aliases: List<String>) {
        val store = grammar as? SchemaAliases ?: return
        executor.execute(store.aliased(item, aliases))
    }

    /**
     * Drops the index that [spec] refers to.
     *
     * @return true if an index was found and dropped, false if none existed
     */
    fun drop(spec: IndexSpec): Boolean = checkNotNull(driver.item(ItemCall.DROP, spec).dropped)

    /**
     * Drops (if present) and recreates the index described by [spec]. Destructive — previously
     * indexed data may need re-processing (e.g. re-embedding after a vector dimension change).
     * An index that ignores case fills the lowercased copies in again on every node of its label,
     * recorded or not. The index made again keeps the aliases its record kept (see [SchemaAliases]),
     * and is returned once it is built, as by [ensure].
     */
    fun recreate(spec: IndexSpec): EnsureResult.Recreated = driver.item(ItemCall.RECREATE, spec).result as EnsureResult.Recreated

    /** A listing's rows, or null where it fails: some engines refuse to list a kind before there is one. */
    private fun introspect(query: String): List<Any?>? = try {
        executor.query(query)
    } catch (e: Exception) {
        logger.debug("Index introspection failed (treating as no indexes exist): {}", e.message)
        null
    }

    internal companion object {
        /** The longest an index is waited on to be built, the planner's: as long as Drivine's own tests wait, with `db.awaitIndexes(60)`. */
        val DEFAULT_BUILD_TIMEOUT: Duration = Duration.ofMillis(PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS)
    }
}
