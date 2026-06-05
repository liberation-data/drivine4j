package org.drivine.schema

import org.drivine.connection.ConnectionProvider
import org.slf4j.LoggerFactory

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
            executor.execute(grammar.createIndex(spec, related))
            val created = find(spec) ?: SchemaItemInfo.fromSpec(spec)
            logger.info("Created {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
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
    ): EnsureResult = ensure(VectorIndexSpec(label, property, dimensions, similarity, name))

    /** Convenience for [ensure] with a [RangeIndexSpec] (single or composite). */
    fun ensureRange(label: String, vararg properties: String): EnsureResult =
        ensure(RangeIndexSpec(label, properties.toList()))

    /** Finds the existing index that [spec] refers to, or null if none exists. */
    fun find(spec: IndexSpec): SchemaItemInfo? =
        introspectIndexes(spec.kind).firstOrNull { grammar.matchesIdentity(it, spec) }

    /** Lists all managed indexes (vector and range) on the database. */
    fun list(): List<SchemaItemInfo> {
        val queries = listOf(SchemaItemKind.VECTOR_INDEX, SchemaItemKind.RANGE_INDEX)
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
        executor.execute(grammar.dropIndex(narrowTo(existing, spec)))
        logger.info("Dropped {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
        return true
    }

    /**
     * Drops (if present) and recreates the index described by [spec]. Destructive — previously
     * indexed data may need re-processing (e.g. re-embedding after a vector dimension change).
     */
    fun recreate(spec: IndexSpec): EnsureResult.Recreated {
        val existing = find(spec)
        if (existing != null) {
            executor.execute(grammar.dropIndex(narrowTo(existing, spec)))
        }
        // Re-introspect after the drop: on per-label-index engines the label may still have an
        // index covering other properties, which creation must take into account
        val related = introspectIndexes(spec.kind)
            .firstOrNull { it.kind == spec.kind && it.label == spec.label }
        executor.execute(grammar.createIndex(spec, related))
        val created = find(spec) ?: SchemaItemInfo.fromSpec(spec)
        logger.warn(
            "Recreated {} {} on {}{} — previously indexed data may be stale " +
                "(vector indexes: stored embeddings need re-embedding)",
            grammar.engine, spec.kind, spec.label, spec.properties
        )
        return EnsureResult.Recreated(existing, created)
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