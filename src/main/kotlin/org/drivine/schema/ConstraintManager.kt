package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Idempotent, drift-aware constraint management for a single database.
 *
 * Obtain via [org.drivine.manager.PersistenceManager.constraints]. All operations execute in
 * auto-commit mode regardless of any surrounding `@Transactional` context.
 *
 * Constraints differ from indexes in lifecycle: creation can fail because existing data violates
 * the constraint, which surfaces as [EnsureResult.Violation] (with a bounded sample of the
 * conflicting values) rather than an exception, once the data confirms it. Engine divergence — including FalkorDB's
 * native-command constraints, required backing indexes, and asynchronous creation — lives in the
 * [SchemaGrammar].
 *
 * Example:
 * ```kotlin
 * val result = persistenceManager.constraints.ensure(
 *     UniquenessConstraintSpec("ChatSession", "sessionId")
 * )
 * if (result is EnsureResult.Violation) {
 *     log.error("Duplicate sessionIds exist: {}", result.conflictingSample)
 * }
 * ```
 */
class ConstraintManager internal constructor(
    private val executor: SchemaExecutor,
    val grammar: SchemaGrammar,
    private val indexManager: IndexManager,
    private val asyncCreationTimeout: Duration = Duration.ofSeconds(30),
    private val asyncPollInterval: Duration = Duration.ofMillis(200),
) {

    internal constructor(connectionProvider: ConnectionProvider, indexManager: IndexManager) :
        this(SchemaExecutor(connectionProvider), connectionProvider.schemaGrammar, indexManager)

    private val logger = LoggerFactory.getLogger(ConstraintManager::class.java)

    /** Runs [statements], which make or drop a constraint, and tells the grammar they have. */
    private fun run(statements: List<SchemaStatement>) {
        try {
            executor.execute(statements)
        } finally {
            grammar.changed()
        }
    }

    /**
     * Ensures a constraint matching [spec] exists. Idempotent — safe to call on every startup.
     *
     * Never destructive: existing mismatched constraints surface as [EnsureResult.Drift];
     * existing violating data surfaces as [EnsureResult.Violation]. In both cases nothing is
     * changed and the caller decides what to do.
     *
     * A failure counts as a [EnsureResult.Violation] only when the data shows one: the grammar
     * suspects it from the engine's error, and a key that repeats confirms it. Any other failure,
     * including one the grammar suspects that no repeated key bears out, is thrown.
     *
     * @param violationSampleSize how many conflicting property combinations to sample when a
     *   violation is detected (0 leaves the sample empty; the data is still read, to confirm it)
     */
    @JvmOverloads
    fun ensure(spec: ConstraintSpec, violationSampleSize: Int = DEFAULT_VIOLATION_SAMPLE_SIZE): EnsureResult {
        val existing = find(spec)
        if (existing != null) {
            return if (grammar.matchesShape(existing, spec)) {
                EnsureResult.AlreadyMatching(existing)
            } else {
                logger.warn(
                    "Constraint drift on {}{}: existing {} does not match requested {}",
                    spec.label, spec.properties, existing, spec
                )
                EnsureResult.Drift(existing, spec)
            }
        }

        // Some engines (FalkorDB) require an exact-match index on the constrained properties
        // before the constraint can be created
        if (grammar.constraintsRequireBackingIndex) {
            indexManager.ensure(RangeIndexSpec(spec.label, spec.properties))
        }

        try {
            run(grammar.createConstraint(spec))
        } catch (e: Exception) {
            if (grammar.isConstraintViolation(e)) {
                confirmedViolation(spec, violationSampleSize, e)?.let { return it }
            }
            throw e
        }

        if (grammar.constraintCreationIsAsync) {
            return awaitAsyncCreation(spec, violationSampleSize)
        }

        val created = find(spec) ?: SchemaItemInfo.fromSpec(spec)
        logger.info("Created {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
        return EnsureResult.Created(created)
    }

    /** Convenience for [ensure] with a [UniquenessConstraintSpec] (single or composite). */
    fun ensureUnique(label: String, vararg properties: String): EnsureResult =
        ensure(UniquenessConstraintSpec(label, properties.toList()))

    /** Finds the existing constraint that [spec] refers to, or null if none exists. */
    fun find(spec: ConstraintSpec): SchemaItemInfo? {
        val rows = introspect(grammar.listConstraintsQuery()) ?: return null
        return grammar.parseConstraintRows(rows).firstOrNull { grammar.matchesIdentity(it, spec) }
    }

    /** Lists all managed constraints on the database. */
    fun list(): List<SchemaItemInfo> =
        introspect(grammar.listConstraintsQuery())?.let { grammar.parseConstraintRows(it) } ?: emptyList()

    /**
     * Drops the constraint that [spec] refers to.
     *
     * @return true if a constraint was found and dropped, false if none existed
     */
    fun drop(spec: ConstraintSpec): Boolean {
        val existing = find(spec) ?: return false
        run(grammar.dropConstraint(existing))
        logger.info("Dropped {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
        return true
    }

    /**
     * Drops (if present) and recreates the constraint described by [spec].
     *
     * Returns [EnsureResult.Recreated] on success, or [EnsureResult.Violation] if existing data
     * prevents recreation (in which case the previous constraint has already been dropped).
     */
    @JvmOverloads
    fun recreate(spec: ConstraintSpec, violationSampleSize: Int = DEFAULT_VIOLATION_SAMPLE_SIZE): EnsureResult {
        val existing = find(spec)
        if (existing != null) {
            run(grammar.dropConstraint(existing))
        }
        return when (val result = ensure(spec, violationSampleSize)) {
            is EnsureResult.Created -> EnsureResult.Recreated(existing, result.info)
            else -> result
        }
    }

    // ----- Internals -----

    /**
     * Engines with asynchronous constraint creation (FalkorDB) return immediately from CREATE;
     * the constraint then transitions UNDER CONSTRUCTION → OPERATIONAL or FAILED. A FAILED
     * constraint must be dropped (FalkorDB does not allow retrying a failed constraint); it is
     * reported as a violation when the data confirms one, and thrown when it does not.
     */
    private fun awaitAsyncCreation(spec: ConstraintSpec, violationSampleSize: Int): EnsureResult {
        val deadline = System.currentTimeMillis() + asyncCreationTimeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val info = find(spec)
            val status = info?.status?.uppercase()
            when {
                info == null -> { /* not yet visible — keep polling */ }

                status == null || status == "OPERATIONAL" -> {
                    logger.info("Created {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
                    return EnsureResult.Created(info)
                }

                status.contains("FAILED") -> {
                    run(grammar.dropConstraint(info))
                    val failed = DrivineException(
                        "Constraint on ${spec.label}${spec.properties} failed to build on ${grammar.engine}, " +
                            "but no key it covers repeats in the data"
                    )
                    return confirmedViolation(spec, violationSampleSize, failed) ?: throw failed
                }

                // UNDER CONSTRUCTION → keep polling
            }
            Thread.sleep(asyncPollInterval.toMillis())
        }
        throw DrivineException(
            "Timed out after $asyncCreationTimeout waiting for constraint on " +
                "${spec.label}${spec.properties} to become operational"
        )
    }

    /**
     * The violation a failure to make [spec] is suspected of, confirmed against the data: the key
     * values that repeat, at most [limit] of them (none when [limit] is 0 or less, though the data is still
     * read). Null when no key repeats, or the data cannot be read, so that a failure with another
     * cause is not blamed on the data: the caller then throws it. [failure], when there is one, keeps
     * why the data could not be read.
     */
    private fun confirmedViolation(spec: ConstraintSpec, limit: Int, failure: Throwable?): EnsureResult.Violation? {
        val conflicts = try {
            executor.query(grammar.sampleConflictsQuery(spec, maxOf(limit, 1)))
                .filterIsInstance<Map<*, *>>()
                .map { row -> row.entries.associate { (key, value) -> key.toString() to value } }
        } catch (e: Exception) {
            logger.debug("Could not read the keys that repeat for {}{}: {}", spec.label, spec.properties, e.message)
            failure?.addSuppressed(e)
            return null
        }
        if (conflicts.isEmpty()) return null
        logger.warn("Could not create constraint on {}{}: existing data violates it", spec.label, spec.properties)
        return EnsureResult.Violation(spec, conflicts.take(limit.coerceAtLeast(0)))
    }

    /**
     * Runs an introspection query, treating failures as "nothing exists yet". The query string is
     * built by the caller (outside this method) so that unsupported engines fail loudly instead of
     * having their error swallowed here.
     */
    private fun introspect(query: String): List<Any?>? = try {
        executor.query(query)
    } catch (e: Exception) {
        logger.debug("Constraint introspection failed (treating as no constraints exist): {}", e.message)
        null
    }

    companion object {
        const val DEFAULT_VIOLATION_SAMPLE_SIZE = 10
    }
}