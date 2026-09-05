package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Idempotent, drift-aware index management for a single database.
 *
 * Obtain via [org.drivine.manager.PersistenceManager.indexes]. All operations execute in
 * auto-commit mode regardless of any surrounding `@Transactional` context — schema DDL cannot run
 * inside an open data transaction.
 *
 * The orchestration here (ensure / drift / recreate) is engine-agnostic; all engine divergence
 * lives in the [SchemaGrammar].
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
    private val asyncDropTimeout: Duration = Duration.ofSeconds(30),
    private val asyncPollInterval: Duration = Duration.ofMillis(200),
) {

    internal constructor(connectionProvider: ConnectionProvider) :
        this(SchemaExecutor(connectionProvider), connectionProvider.schemaGrammar)

    private val logger = LoggerFactory.getLogger(IndexManager::class.java)

    /**
     * Ensures an index matching [spec] exists. Idempotent — safe to call on every startup.
     *
     * Never destructive: if an index exists with a different shape, returns [EnsureResult.Drift]
     * and changes nothing; call [recreate] to replace it.
     */
    fun ensure(spec: IndexSpec): EnsureResult {
        val items = introspectIndexes(spec.kind)
        val existing = items.firstOrNull { grammar.matchesIdentity(it, spec) }
        if (existing == null) {
            // A related item (same kind and label, not satisfying the spec) lets engines with
            // per-label indexes (FalkorDB) create only the missing properties
            val related = items.firstOrNull { it.kind == spec.kind && it.label == spec.label }
            warnUnsupportedTuning(spec)
            executor.execute(grammar.createIndex(spec, related))
            val created = find(spec) ?: SchemaItemInfo.fromSpec(spec)
            logger.info("Created {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
            logEffectiveVectorConfig(spec, created)
            return EnsureResult.Created(created)
        }
        return if (grammar.matchesShape(existing, spec)) {
            EnsureResult.AlreadyMatching(existing)
        } else {
            logger.warn(
                "Index drift on {}{}: existing {} does not match requested {}. " +
                    "Call recreate() to replace it (destructive).",
                spec.label, spec.properties, existing, spec
            )
            EnsureResult.Drift(existing, spec)
        }
    }

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
    fun find(spec: IndexSpec): SchemaItemInfo? =
        introspectIndexes(spec.kind).firstOrNull { grammar.matchesIdentity(it, spec) }

    /** Lists all managed indexes (vector, range, and fulltext) on the database. */
    fun list(): List<SchemaItemInfo> {
        val queries = listOf(
            SchemaItemKind.VECTOR_INDEX,
            SchemaItemKind.RANGE_INDEX,
            SchemaItemKind.FULLTEXT_INDEX,
        )
            .map { grammar.listIndexesQuery(it) }
            .distinct()
        return queries
            .flatMap { query -> introspect(query)?.let { grammar.parseIndexRows(it) } ?: emptyList() }
            .distinct()
    }

    /**
     * Drops the index that [spec] refers to.
     *
     * @return true if an index was found and dropped, false if none existed
     */
    fun drop(spec: IndexSpec): Boolean {
        val existing = find(spec) ?: return false
        dropExisting(existing, spec)
        return true
    }

    /**
     * Drops (if present) and recreates the index described by [spec]. Destructive — previously
     * indexed data may need re-processing (e.g. re-embedding after a vector dimension change).
     */
    fun recreate(spec: IndexSpec): EnsureResult.Recreated {
        val existing = find(spec)
        if (existing != null) {
            dropExisting(existing, spec)
        }
        // Re-introspect after the drop: on per-label-index engines the label may still have an
        // index covering other properties, which creation must take into account
        val related = introspectIndexes(spec.kind)
            .firstOrNull { it.kind == spec.kind && it.label == spec.label }
        warnUnsupportedTuning(spec)
        executor.execute(grammar.createIndex(spec, related))
        val created = find(spec) ?: SchemaItemInfo.fromSpec(spec)
        logEffectiveVectorConfig(spec, created)
        logger.warn(
            "Recreated {} {} on {}{} — previously indexed data may be stale " +
                "(vector indexes: stored embeddings need re-embedding)",
            grammar.engine, spec.kind, spec.label, spec.properties
        )
        return EnsureResult.Recreated(existing, created)
    }

    /**
     * Warns when a spec pins physical vector parameters this engine cannot express.
     *
     * Silently dropping them would reproduce the exact failure mode pinning exists to prevent: a
     * declaration that reads as authoritative while the server picks its own values.
     */
    private fun warnUnsupportedTuning(spec: IndexSpec) {
        if (spec !is VectorIndexSpec || !spec.pinsPhysicalConfig) return
        val unsupported = grammar.unsupportedVectorTuning(spec).ifEmpty { return }
        if (unsupported.isNotEmpty()) {
            logger.warn(
                "Vector index {} on {}{} pins {}, which {} cannot express — the engine will choose these " +
                    "instead. Remove the pin or accept engine-chosen values on this backend.",
                spec.effectiveName, spec.label, spec.properties, unsupported, grammar.engine
            )
        }
    }

    /**
     * Logs the physical configuration a vector index actually ended up with.
     *
     * Engine defaults for quantization and HNSW change between versions, so an unpinned parameter is
     * whatever that server chose today. Recording it at creation is what makes the value attributable
     * later, instead of a suspect to be chased during an incident.
     */
    private fun logEffectiveVectorConfig(spec: IndexSpec, created: SchemaItemInfo) {
        if (spec !is VectorIndexSpec) return
        val effective = created.vectorConfigDescription()
        if (effective.isEmpty()) return
        val pinned = if (spec.pinsPhysicalConfig) "partly pinned" else "engine defaults"
        logger.info(
            "Vector index {} on {}{} effective config ({}): {}",
            created.name ?: spec.effectiveName, spec.label, spec.properties, pinned, effective
        )
    }

    /**
     * Drops [existing], narrowed to what [spec] asked for, and — on engines that tear indexes down
     * in the background (FalkorDB) — waits until the index is actually gone. Without the wait the
     * DDL returns while introspection still reports the index, so a caller that drops and then
     * looks (or recreates) sees the index it just removed.
     */
    private fun dropExisting(existing: SchemaItemInfo, spec: IndexSpec) {
        executor.execute(grammar.dropIndex(narrowTo(existing, spec)))
        awaitDropped(spec)
        logger.info("Dropped {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
    }

    /** Polls until [spec] no longer resolves to an index, on engines whose drops are asynchronous. */
    private fun awaitDropped(spec: IndexSpec) {
        if (!grammar.indexOperationsAreAsync) return
        val deadline = System.currentTimeMillis() + asyncDropTimeout.toMillis()
        while (find(spec) != null) {
            if (System.currentTimeMillis() >= deadline) {
                throw DrivineException(
                    "Timed out after $asyncDropTimeout waiting for ${spec.kind} on " +
                        "${spec.label}${spec.properties} to be dropped"
                )
            }
            Thread.sleep(asyncPollInterval.toMillis())
        }
    }

    /**
     * Narrows an introspected item to the properties [spec] declares, so that engines whose
     * indexes cover whole labels (FalkorDB) never drop more than was asked for. On engines with
     * exact property matching this is a no-op.
     */
    private fun narrowTo(existing: SchemaItemInfo, spec: IndexSpec): SchemaItemInfo {
        val narrowed = existing.properties.filter { spec.properties.contains(it) }
        return if (narrowed.isEmpty() || narrowed.size == existing.properties.size) {
            existing
        } else {
            existing.copy(properties = narrowed)
        }
    }

    /** All current indexes able to satisfy specs of [kind], parsed and normalized. */
    private fun introspectIndexes(kind: SchemaItemKind): List<SchemaItemInfo> {
        val rows = introspect(grammar.listIndexesQuery(kind)) ?: return emptyList()
        return grammar.parseIndexRows(rows)
    }

    private fun introspect(query: String): List<Any?>? = try {
        executor.query(query)
    } catch (e: Exception) {
        // Some engines throw when nothing of the requested type exists yet (e.g. Memgraph's
        // vector_search procedure before any vector index is created) — treat as "none exist".
        logger.debug("Index introspection failed (treating as no indexes exist): {}", e.message)
        null
    }
}