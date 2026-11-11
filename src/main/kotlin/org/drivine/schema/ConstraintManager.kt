package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.cypher.store.plan.ItemCall
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
 * because a plain index is in the way of it, which surfaces as [EnsureResult.BlockedByIndex]. What each call
 * does on the store, FalkorDB's native-command constraints, required backing indexes and asynchronous creation
 * among it, is decided by the Cypher compiler's module's schema planner (`org.drivine.cypher.store.plan`), which
 * this manager runs ([SchemaPlanDriver]).
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
 *
 * @param asyncCreationTimeout how long [inForce] waits for a constraint FalkorDB is still building
 * @param asyncPollInterval how often [inForce] looks again
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

    private val driver: SchemaPlanDriver get() = indexManager.driver

    /**
     * Ensures a constraint matching [spec] exists. Idempotent — safe to call on every startup.
     *
     * Never destructive unless asked: existing mismatched constraints surface as [EnsureResult.Drift];
     * existing violating data surfaces as [EnsureResult.Violation]; a plain index in the way of the
     * constraint surfaces as [EnsureResult.BlockedByIndex]. In each case nothing is changed and the
     * caller decides what to do, save that a constraint that ignores case (see
     * [UniquenessConstraintSpec.ignoreCase]) first fills in the lowercased copies it is made over, as
     * [IndexManager.ensure] does, again where the constraint is not there yet. FalkorDB cannot make one, and throws a [DrivineException] before that.
     * The one thing dropped unasked is a constraint whose build failed,
     * which FalkorDB keeps listing though it enforces nothing: it is dropped and built again. A
     * constraint FalkorDB still lists as under construction, from another process or an earlier run,
     * is waited on, as one made here is: matching once built, and dropped and built again if its
     * build fails.
     *
     * A failure counts as a [EnsureResult.Violation] only when the data shows one: the store's error
     * suggests it, and a key that repeats confirms it. Any other failure,
     * including one suspected that no repeated key bears out, is thrown.
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
        refuseUnconstrainable(spec)
        return checkNotNull(driver.item(ItemCall.ENSURE, spec, policy(violationSampleSize, replaceBlockingIndex)).result)
    }

    /** Convenience for [ensure] with a [UniquenessConstraintSpec] (single or composite). */
    fun ensureUnique(label: String, vararg properties: String): EnsureResult =
        ensure(UniquenessConstraintSpec(label, properties.toList()))

    /** Finds the existing constraint that [spec] refers to, or null if none exists. */
    fun find(spec: ConstraintSpec): SchemaItemInfo? {
        val rows = introspect(driver.store.ddl.listConstraintsQuery()) ?: return null
        val made = spec.made()
        return driver.store.parseConstraintRows(rows).firstOrNull { driver.store.matchesIdentity(it, made) }
    }

    /**
     * Whether the constraint that [spec] refers to is there and built, so that the engine enforces it, as one
     * another process made. One still being built (FalkorDB) is waited on; one whose build failed is not in force,
     * and is left as it is. The store identity's check before it makes its stamp: no schema plan, but a read.
     */
    internal fun inForce(spec: ConstraintSpec): Boolean =
        awaitBuild(spec)?.takeUnless { it.buildFailed } != null

    /** Lists all managed constraints on the database. */
    fun list(): List<SchemaItemInfo> =
        introspect(driver.store.ddl.listConstraintsQuery())?.let { driver.store.parseConstraintRows(it) } ?: emptyList()

    /** Every managed constraint, as [list] gives them, with the aliases its record keeps (see [SchemaAliases]). */
    internal fun recorded(): List<RecordedItem> =
        introspect(driver.store.ddl.listConstraintsQuery())?.let { recordedItems(it, driver.store::parseConstraintRows) } ?: emptyList()

    /**
     * Drops the constraint that [spec] refers to, including one whose build failed, which FalkorDB
     * keeps listing though it enforces nothing.
     *
     * @return true if a constraint was found and dropped, false if none existed
     */
    fun drop(spec: ConstraintSpec): Boolean = checkNotNull(driver.item(ItemCall.DROP, spec).dropped)

    /**
     * Drops (if present) and recreates the constraint described by [spec].
     *
     * Returns [EnsureResult.Recreated] on success, or [EnsureResult.Violation] if existing data
     * prevents recreation (in which case the previous constraint has already been dropped), or
     * [EnsureResult.BlockedByIndex] as [ensure] does. A constraint that ignores case fills the
     * lowercased copies in again on every node of its label, recorded or not. The constraint made again
     * keeps the aliases its record kept (see [SchemaAliases]).
     */
    @JvmOverloads
    fun recreate(
        spec: ConstraintSpec,
        violationSampleSize: Int = DEFAULT_VIOLATION_SAMPLE_SIZE,
        replaceBlockingIndex: Boolean = false,
    ): EnsureResult {
        refuseUnconstrainable(spec)
        return checkNotNull(driver.item(ItemCall.RECREATE, spec, policy(violationSampleSize, replaceBlockingIndex)).result)
    }

    /**
     * The grammar's own refusal of a constraint its store can't make ([SchemaGrammar.requireConstrainable]), asked
     * before anything is filled in or made for it, as these calls always asked it. The planner refuses such a
     * constraint itself only where the store is one the module knows (FalkorDB's), so a grammar of the application's
     * own is asked here. The module is to ask it too (liberation-data/drivine-cypher-spec#170).
     */
    private fun refuseUnconstrainable(spec: ConstraintSpec) = grammar.requireConstrainable(spec.made())

    /** The call's arguments as the policy an item's plan reads them from. */
    private fun policy(violationSampleSize: Int, replaceBlockingIndex: Boolean) =
        SchemaPolicy(violationSampleSize = violationSampleSize, replaceBlockingIndex = replaceBlockingIndex)

    /**
     * Polls the constraint [spec] refers to until its build has ended, OPERATIONAL or FAILED, and returns it as last
     * listed, or null where it is not listed. Each poll reads the status from one listing, so a build that fails
     * between two reads is never taken for built.
     */
    private fun awaitBuild(spec: ConstraintSpec): SchemaItemInfo? {
        val deadline = System.currentTimeMillis() + asyncCreationTimeout.toMillis()
        return executor.poll("constraint build", ::pollOutcome) {
            while (System.currentTimeMillis() < deadline) {
                val info = find(spec) ?: return@poll null
                if (!info.buildInProgress) return@poll info
                Thread.sleep(asyncPollInterval.toMillis())
            }
            throw DrivineException(
                "Timed out after $asyncCreationTimeout waiting for constraint on " +
                    "${spec.label}${spec.properties} to become operational"
            )
        }
    }

    /** How a poll of a constraint's build ended, as a [SchemaStatementTrace] observer is told. */
    private fun pollOutcome(polled: SchemaItemInfo?): String = when {
        polled == null -> "absent"
        polled.buildFailed -> "failed"
        else -> "built"
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
