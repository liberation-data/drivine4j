package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.slf4j.LoggerFactory

/**
 * Applies declared [SchemaCatalog]s to their target databases.
 *
 * The catalog-driven, multi-database counterpart to the per-database [IndexManager] /
 * [ConstraintManager]. Injectable and callable at runtime — not just at startup: the Spring starter
 * calls [enforce] on boot, and an application can call [enforce] or [recreateAll] on demand, e.g.
 * after a bulk re-embed.
 *
 * Resolution: each catalog applies to one or more databases ([SchemaCatalog.target]); the default
 * broadcasts to every schema-capable database (incapable engines like Neptune are skipped with a
 * warning), while an explicitly named target is strict. Per database, the specs of every catalog
 * applying to it are merged (duplicates dedupe; conflicts fail fast) and ensured with indexes before
 * constraints.
 *
 * Versioning: a catalog tagged with [SchemaCatalog.withVersion] contributes to the database's
 * *effective version*. [enforce] compares it against the last value recorded in the
 * [SchemaVersionStore] marker node — when it **changes** (a prior value existed and differs) the
 * affected items are dropped and recreated once. A first-ever value is adopted without recreating.
 *
 * Outcomes needing attention (drift, violations) are handled per [SchemaPolicy].
 *
 * All DDL runs in auto-commit mode (the non-transactional manager) — schema operations cannot run
 * inside an open data transaction.
 */
class SchemaManager(
    private val persistenceManagerFactory: PersistenceManagerFactory,
    private val databaseRegistry: DatabaseRegistry,
    private val catalogs: List<SchemaCatalog>,
    private val policy: SchemaPolicy = SchemaPolicy(),
) {

    private val logger = LoggerFactory.getLogger(SchemaManager::class.java)

    /**
     * Idempotent, version-aware application. Safe to call on every startup and at runtime: items are
     * ensured (creating what's missing, reporting drift), and a database's items are recreated only
     * when its effective version changed or [SchemaPolicy] requests it.
     */
    fun enforce() = apply(forceRecreate = false)

    /**
     * Brute-force: drop and recreate **every** item on every target database, regardless of version.
     * The post-embedding hammer — e.g. after re-embedding nodes following a model or dimension change.
     * Destructive: recreating a vector index rebuilds it from the stored embedding properties (it does
     * not re-embed); recreating a uniqueness constraint can surface a violation if data conflicts.
     */
    fun recreateAll() = apply(forceRecreate = true)

    private fun apply(forceRecreate: Boolean) {
        if (catalogs.none { !it.isEmpty() }) {
            return
        }
        val resolution = SchemaTargets.resolve(catalogs, databaseRegistry)
        if (resolution.skipped.isNotEmpty()) {
            logger.warn(
                "Skipping schema management for databases whose engine has no DDL support: {}",
                resolution.skipped
            )
        }
        resolution.databases.sorted().forEach { database ->
            applyToDatabase(database, forceRecreate)
        }
    }

    private fun applyToDatabase(database: String, forceRecreate: Boolean) {
        val contributing = catalogs.filter { it.target.includes(database) && !it.isEmpty() }
        if (contributing.isEmpty()) {
            return
        }
        val catalog = SchemaCatalog.of(contributing.flatMap { it.items })
        val effectiveVersion = SchemaCatalog.combineVersions(contributing)

        // Schema DDL must run in auto-commit mode
        val manager = persistenceManagerFactory.get(database, PersistenceManagerType.NON_TRANSACTIONAL)
        val versionStore = SchemaVersionStore(manager)

        val storedVersion = effectiveVersion?.let {
            // Guard the marker's singleton invariant (dedupe + uniqueness constraint on the
            // MERGE key) before reading or writing it — concurrent enforcing processes could
            // otherwise each create a marker.
            versionStore.ensureSingleton()
            versionStore.storedVersion()
        }
        // A version *change* (a prior value existed and now differs) forces a rebuild. A first-ever
        // value is adopted without recreating, so enabling versioning never nukes a healthy schema.
        val versionChanged = effectiveVersion != null && storedVersion != null && storedVersion != effectiveVersion
        val recreate = forceRecreate || policy.recreateOnStartup || versionChanged

        logger.info(
            "Applying schema to '{}': {} indexes, {} constraints (recreate={}, version={})",
            database, catalog.indexes.size, catalog.constraints.size, recreate, effectiveVersion
        )

        val outcomes = mutableListOf<Pair<SchemaItemSpec, EnsureResult>>()
        catalog.indexes.forEach { spec -> outcomes += spec to applyIndex(manager, spec, recreate) }
        catalog.constraints.forEach { spec -> outcomes += spec to applyConstraint(manager, spec, recreate) }

        finish(database, outcomes, effectiveVersion, versionStore)
    }

    private fun applyIndex(manager: PersistenceManager, spec: IndexSpec, recreate: Boolean): EnsureResult {
        if (recreate) {
            return manager.indexes.recreate(spec)
        }
        val result = manager.indexes.ensure(spec)
        return if (result is EnsureResult.Drift && policy.recreateOnDrift) {
            manager.indexes.recreate(spec)
        } else {
            result
        }
    }

    private fun applyConstraint(manager: PersistenceManager, spec: ConstraintSpec, recreate: Boolean): EnsureResult {
        if (recreate) {
            return manager.constraints.recreate(spec, policy.violationSampleSize)
        }
        val result = manager.constraints.ensure(spec, policy.violationSampleSize)
        return if (result is EnsureResult.Drift && policy.recreateOnDrift) {
            manager.constraints.recreate(spec, policy.violationSampleSize)
        } else {
            result
        }
    }

    /**
     * Reports outcomes and records the version. The version is recorded only on success — if
     * FAIL_FAST throws on a problem, the marker is left untouched so the next enforce retries.
     */
    private fun finish(
        database: String,
        outcomes: List<Pair<SchemaItemSpec, EnsureResult>>,
        effectiveVersion: String?,
        versionStore: SchemaVersionStore,
    ) {
        val created = outcomes.count { it.second is EnsureResult.Created }
        val matching = outcomes.count { it.second is EnsureResult.AlreadyMatching }
        val recreated = outcomes.count { it.second is EnsureResult.Recreated }
        val problems = outcomes.filter { it.second is EnsureResult.Drift || it.second is EnsureResult.Violation }

        logger.info(
            "Schema for '{}': {} created, {} already matching, {} recreated, {} needing attention",
            database, created, matching, recreated, problems.size
        )

        if (problems.isNotEmpty() && policy.mode == SchemaMode.FAIL_FAST) {
            throw DrivineException(
                "Schema application for database '$database' found problems:\n" +
                    problems.joinToString("\n") { (spec, result) -> describe(spec, result) } + "\n" +
                    "Resolve them, set drivine.schema.recreate-on-drift=true (destructive), " +
                    "or set drivine.schema.mode=WARN to continue with warnings."
            )
        }

        if (effectiveVersion != null) {
            versionStore.record(effectiveVersion)
        }

        problems.forEach { (spec, result) -> logger.warn(describe(spec, result)) }
    }

    private fun describe(spec: SchemaItemSpec, result: EnsureResult): String = when (result) {
        is EnsureResult.Drift ->
            "  - DRIFT: ${spec.kind} on ${spec.label}${spec.properties} exists with a different shape: ${result.existing}"

        is EnsureResult.Violation ->
            "  - VIOLATION: ${spec.kind} on ${spec.label}${spec.properties} cannot be created — " +
                "existing data violates it. Conflicting sample: ${result.conflictingSample}"

        else -> ""
    }
}