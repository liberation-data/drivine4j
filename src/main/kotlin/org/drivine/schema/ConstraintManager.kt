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
 * conflicting values) rather than an exception, once the data confirms it. On Neo4j it can also fail
 * because a plain index is in the way of it, which surfaces as [EnsureResult.BlockedByIndex]. Engine divergence — including FalkorDB's
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
     * Never destructive unless asked: existing mismatched constraints surface as [EnsureResult.Drift];
     * existing violating data surfaces as [EnsureResult.Violation]; a plain index in the way of the
     * constraint surfaces as [EnsureResult.BlockedByIndex]. In each case nothing is changed and the
     * caller decides what to do. The one thing dropped unasked is a constraint whose build failed,
     * which FalkorDB keeps listing though it enforces nothing: it is dropped and built again. A
     * constraint FalkorDB still lists as under construction, from another process or an earlier run,
     * is waited on, as one made here is: matching once built, and dropped and built again if its
     * build fails.
     *
     * A failure counts as a [EnsureResult.Violation] only when the data shows one: the grammar
     * suspects it from the engine's error, and a key that repeats confirms it. Any other failure,
     * including one the grammar suspects that no repeated key bears out, is thrown.
     *
     * @param violationSampleSize how many conflicting property combinations to sample when a
     *   violation is detected, and how many of the nodes that hold each one to name (0 leaves the
     *   sample empty; the data is still read, to confirm it)
     * @param replaceBlockingIndex drop a plain index in the way of the constraint and make the
     *   constraint, whose own index takes its place, instead of returning [EnsureResult.BlockedByIndex].
     *   Only an engine whose constraint brings its own index reports one in the way (Neo4j; see
     *   [SchemaGrammar.isBlockedByIndex]), so on the others this changes nothing.
     */
    @JvmOverloads
    fun ensure(
        spec: ConstraintSpec,
        violationSampleSize: Int = DEFAULT_VIOLATION_SAMPLE_SIZE,
        replaceBlockingIndex: Boolean = false,
    ): EnsureResult {
        // A constraint still being built, by another process or an earlier run, may yet fail: wait for
        // its build to end, as for one made here. One dropped meanwhile is made again below.
        val existing = find(spec)?.let { if (it.buildInProgress) awaitBuild(spec, untilListed = false) else it }
        if (existing != null && existing.buildFailed) {
            // FalkorDB keeps listing a constraint whose build failed, though it enforces nothing.
            // It is not the constraint: drop it and build again, which gives a violation when the
            // data still repeats a key.
            run(grammar.dropConstraint(existing))
            logger.warn(
                "Dropped {} {} on {}{}, whose build had failed, to build it again",
                grammar.engine, spec.kind, spec.label, spec.properties
            )
        } else if (existing != null) {
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
            if (grammar.isBlockedByIndex(e)) {
                val index = blockingIndex(spec) ?: throw e
                if (!replaceBlockingIndex) {
                    logger.warn(
                        "Could not create constraint on {}{}: index {} is in the way of it",
                        spec.label, spec.properties, index.name
                    )
                    return EnsureResult.BlockedByIndex(spec, index)
                }
                // Neo4j refuses for the index before it reads the data, so a key that repeats would
                // refuse the constraint after the index is gone: look first, and keep the index, as
                // when the data cannot be read.
                val repeated = repeatedKeys(spec, violationSampleSize, e) ?: throw e
                if (repeated.isNotEmpty()) return violation(spec, repeated, violationSampleSize)
                run(grammar.dropIndex(index))
                logger.info("Dropped index {} on {}{}, in the way of a constraint that replaces it", index.name, spec.label, spec.properties)
                return ensure(spec, violationSampleSize, replaceBlockingIndex = false)
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
     * Drops the constraint that [spec] refers to, including one whose build failed, which FalkorDB
     * keeps listing though it enforces nothing.
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
     * prevents recreation (in which case the previous constraint has already been dropped), or
     * [EnsureResult.BlockedByIndex] as [ensure] does.
     */
    @JvmOverloads
    fun recreate(
        spec: ConstraintSpec,
        violationSampleSize: Int = DEFAULT_VIOLATION_SAMPLE_SIZE,
        replaceBlockingIndex: Boolean = false,
    ): EnsureResult {
        val existing = find(spec)
        if (existing != null) {
            run(grammar.dropConstraint(existing))
        }
        return when (val result = ensure(spec, violationSampleSize, replaceBlockingIndex)) {
            is EnsureResult.Created -> EnsureResult.Recreated(existing, result.info)
            else -> result
        }
    }

    // ----- Internals -----

    /**
     * Engines with asynchronous constraint creation (FalkorDB) return immediately from CREATE;
     * the constraint then transitions UNDER CONSTRUCTION → OPERATIONAL or FAILED. A FAILED
     * constraint is dropped: it enforces nothing, yet stays listed, where it would be taken for the
     * constraint by anything that reads the store. It is reported as a violation when the data
     * confirms one, and thrown when it does not.
     */
    private fun awaitAsyncCreation(spec: ConstraintSpec, violationSampleSize: Int): EnsureResult {
        val info = awaitBuild(spec, untilListed = true)!!
        if (info.buildFailed) {
            run(grammar.dropConstraint(info))
            val failed = DrivineException(
                "Constraint on ${spec.label}${spec.properties} failed to build on ${grammar.engine}, " +
                    "but no key it covers repeats in the data"
            )
            return confirmedViolation(spec, violationSampleSize, failed) ?: throw failed
        }
        logger.info("Created {} {} on {}{}", grammar.engine, spec.kind, spec.label, spec.properties)
        return EnsureResult.Created(info)
    }

    /**
     * Polls the constraint [spec] refers to until its build has ended, OPERATIONAL or FAILED, and returns
     * it as last listed. Each poll reads the status from one listing, so a build that fails between two
     * reads is never taken for built. While it is not listed, polling goes on when [untilListed] (a
     * constraint just made, not yet visible), and otherwise ends with null (one dropped meanwhile).
     */
    private fun awaitBuild(spec: ConstraintSpec, untilListed: Boolean): SchemaItemInfo? {
        val deadline = System.currentTimeMillis() + asyncCreationTimeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val info = find(spec)
            if (info == null && !untilListed) return null
            if (info != null && !info.buildInProgress) return info
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
    private fun confirmedViolation(spec: ConstraintSpec, limit: Int, failure: Throwable?): EnsureResult.Violation? =
        repeatedKeys(spec, limit, failure)?.takeIf { it.isNotEmpty() }?.let { violation(spec, it, limit) }

    /**
     * The key values of [spec] that repeat in the data, [limit] of them at most and at least one;
     * null when the data cannot be read, which [failure], when there is one, keeps.
     */
    private fun repeatedKeys(spec: ConstraintSpec, limit: Int, failure: Throwable?): List<Map<String, Any?>>? = try {
        executor.query(grammar.sampleConflictsQuery(spec, maxOf(limit, 1)))
            .filterIsInstance<Map<*, *>>()
            .map { row -> row.entries.associate { (key, value) -> key.toString() to value } }
    } catch (e: Exception) {
        logger.debug("Could not read the keys that repeat for {}{}: {}", spec.label, spec.properties, e.message)
        failure?.addSuppressed(e)
        null
    }

    private fun violation(spec: ConstraintSpec, repeated: List<Map<String, Any?>>, limit: Int): EnsureResult.Violation {
        logger.warn("Could not create constraint on {}{}: existing data violates it", spec.label, spec.properties)
        return EnsureResult.Violation(spec, repeated.take(limit.coerceAtLeast(0)))
    }

    /**
     * The plain range index over [spec]'s label and properties, in its order, that the engine said
     * is in the way of it; null when there is none, and the failure is then not the one it seemed.
     */
    private fun blockingIndex(spec: ConstraintSpec): SchemaItemInfo? =
        indexManager.list().firstOrNull {
            it.kind == SchemaItemKind.RANGE_INDEX && it.label == spec.label && it.properties == spec.properties
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