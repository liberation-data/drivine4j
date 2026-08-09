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
 * **Resolution.** Each catalog applies to one or more databases ([SchemaCatalog.target]); the default
 * broadcasts to every schema-capable database (incapable engines like Neptune are skipped with a
 * warning), while an explicitly named target is strict.
 *
 * **Ownership.** Per database, catalogs are grouped by owner ([SchemaCatalog.name]) and each owner is
 * versioned, recreated, and recorded **independently**, in its own [SchemaVersionStore] marker
 * (`scope: <name>`, or `scope: 'schema'` for the anonymous/unnamed owner). So an application bumping
 * its own version rebuilds only the application's items, never a library's — and vice versa. Within an
 * owner, specs are merged (identical declarations dedupe; conflicts fail fast). *Across* owners,
 * identical declarations still deduplicate at the DDL level (both an app and a library may legitimately
 * want `Chunk(id)` unique), and such **co-owned** items are never dropped by a single owner's version
 * bump — only [recreateAll] rebuilds them. Genuinely conflicting declarations across owners fail fast,
 * naming both owners.
 *
 * **Versioning.** A catalog tagged with [SchemaCatalog.withVersion] contributes to its owner's
 * *effective version*. [enforce] compares it against the last value recorded in that owner's marker —
 * when it **changes** (a prior value existed and differs) the owner's items are dropped and recreated
 * once. A first-ever value is adopted without recreating.
 *
 * **Isolation.** Owners are applied independently: one owner's `FAIL_FAST` problem does not prevent
 * another owner's version from being recorded. Any failures are aggregated and rethrown after every
 * owner has been attempted.
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
     * Idempotent, version-aware application of **every** owner. Safe to call on every startup and at
     * runtime: items are ensured (creating what's missing, reporting drift), and an owner's items are
     * recreated only when its effective version changed or [SchemaPolicy] requests it.
     */
    fun enforce() = apply(forceRecreate = false, ownerFilter = AllOwners)

    /**
     * Idempotent, version-aware application of a single owner's catalogs (see [SchemaCatalog.named]).
     * The specific call site for a library that wants to converge its own schema without touching the
     * host application's. Pass `null` to target the anonymous (unnamed) owner.
     */
    fun enforce(name: String?) = apply(forceRecreate = false, ownerFilter = OneOwner(name))

    /**
     * Brute-force: drop and recreate **every** item of **every** owner on every target database,
     * regardless of version. The post-embedding hammer. Destructive: recreating a vector index
     * rebuilds it from the stored embedding properties (it does not re-embed); recreating a
     * uniqueness constraint can surface a violation if data conflicts. Because it is explicit, it
     * **does** rebuild co-owned items too.
     */
    fun recreateAll() = apply(forceRecreate = true, ownerFilter = AllOwners)

    /**
     * Brute-force recreate of a single owner's items (see [SchemaCatalog.named]) — the escape hatch a
     * library uses to rebuild only its own schema after a bulk re-embed. Unlike a version-change
     * recreate, this **does** rebuild items co-owned with another owner, since the caller asked for it
     * explicitly; that will drop-and-recreate the shared item the other owner also depends on, so use
     * it deliberately. Pass `null` to target the anonymous (unnamed) owner.
     */
    fun recreateAll(name: String?) = apply(forceRecreate = true, ownerFilter = OneOwner(name))

    private sealed interface OwnerFilter {
        fun includes(name: String?): Boolean
    }

    private object AllOwners : OwnerFilter {
        override fun includes(name: String?) = true
    }

    private data class OneOwner(val name: String?) : OwnerFilter {
        override fun includes(name: String?) = name == this.name
    }

    private fun apply(forceRecreate: Boolean, ownerFilter: OwnerFilter) {
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
            applyToDatabase(database, forceRecreate, ownerFilter)
        }
    }

    private fun applyToDatabase(database: String, forceRecreate: Boolean, ownerFilter: OwnerFilter) {
        val contributing = catalogs.filter { it.target.includes(database) && !it.isEmpty() }
        if (contributing.isEmpty()) {
            return
        }

        // Group by owner. Each owner is deduplicated, versioned, and recorded independently. This is
        // computed over *all* owners on the database (even when the filter targets one), so a co-owned
        // item is still recognized as shared and a cross-owner conflict is still caught.
        val ownerSpecs: Map<String?, List<SchemaItemSpec>> = contributing
            .groupBy { it.name }
            .mapValues { (_, cats) -> SchemaCatalog.of(cats.flatMap { it.items }).items }

        detectCrossOwnerConflicts(database, ownerSpecs, ownerFilter)
        val coOwnedKeys = coOwnedKeys(ownerSpecs)

        // Schema DDL must run in auto-commit mode.
        val manager = persistenceManagerFactory.get(database, PersistenceManagerType.NON_TRANSACTIONAL)

        val failures = mutableListOf<DrivineException>()
        ownerSpecs.keys
            .filter { ownerFilter.includes(it) }
            .sortedBy { it ?: "" }
            .forEach { owner ->
                val cats = contributing.filter { it.name == owner }
                try {
                    applyOwner(database, manager, owner, ownerSpecs.getValue(owner), cats, coOwnedKeys, forceRecreate)
                } catch (e: DrivineException) {
                    // Isolate per owner: record the failure but keep applying other owners so a
                    // library's schema still converges (and records its version) when the app's fails.
                    logger.error("Schema application for owner '{}' on '{}' failed: {}", owner ?: "schema", database, e.message)
                    failures += e
                }
            }

        if (failures.isNotEmpty()) {
            throw DrivineException(
                "Schema application on database '$database' failed for ${failures.size} owner(s):\n" +
                    failures.joinToString("\n\n") { it.message ?: it.toString() }
            )
        }
    }

    private fun applyOwner(
        database: String,
        manager: PersistenceManager,
        owner: String?,
        specs: List<SchemaItemSpec>,
        cats: List<SchemaCatalog>,
        coOwnedKeys: Set<String>,
        forceRecreate: Boolean,
    ) {
        val ownerLabel = owner ?: "schema"
        val effectiveVersion = SchemaCatalog.combineVersions(cats)

        val versionStore = SchemaVersionStore(manager, SchemaVersionStore.scopeFor(owner))
        val storedVersion = effectiveVersion?.let {
            // Guard the marker's singleton invariant (dedupe + uniqueness constraint on the MERGE
            // key) before reading or writing it — concurrent enforcing processes could otherwise
            // each create a marker.
            versionStore.ensureSingleton()
            versionStore.storedVersion()
        }
        // A version *change* (a prior value existed and now differs) forces a rebuild of this owner's
        // items. A first-ever value is adopted without recreating, so enabling versioning never nukes
        // a healthy schema.
        val versionChanged = effectiveVersion != null && storedVersion != null && storedVersion != effectiveVersion

        // Report an owner's items that the marker recorded but that are no longer declared (orphans),
        // excluding any still co-owned by another live owner. Detection only; cleanup is manual or via
        // a deliberate recreateAll (see the docs) — auto-drop is a documented follow-up.
        reportOrphans(database, ownerLabel, versionStore, specs, coOwnedKeys)

        logger.info(
            "Applying schema for owner '{}' on '{}': {} items (forceRecreate={}, versionChanged={}, version={})",
            ownerLabel, database, specs.size, forceRecreate, versionChanged, effectiveVersion
        )

        val outcomes = mutableListOf<Pair<SchemaItemSpec, EnsureResult>>()
        specs.forEach { spec ->
            // A co-owned item is never dropped by a single owner's version bump — ensure it instead.
            // Only an explicit recreateAll (forceRecreate) rebuilds a co-owned item.
            val coOwned = spec.inventoryKey in coOwnedKeys
            val recreate = forceRecreate || ((policy.recreateOnStartup || versionChanged) && !coOwned)
            outcomes += spec to when (spec) {
                is IndexSpec -> applyIndex(manager, spec, recreate)
                is ConstraintSpec -> applyConstraint(manager, spec, recreate)
            }
        }

        finish(database, ownerLabel, outcomes, effectiveVersion, specs, versionStore)
    }

    /**
     * Fails fast when two owners declare the same (kind, label, properties) with non-identical specs.
     *
     * Conflicts are computed across **all** owners on the database, but only raised when at least one
     * side is an owner actually being applied — so a selective `enforce("rag")` is not blocked by a
     * misconfiguration between two unrelated owners, consistent with per-owner isolation.
     */
    private fun detectCrossOwnerConflicts(
        database: String,
        ownerSpecs: Map<String?, List<SchemaItemSpec>>,
        ownerFilter: OwnerFilter,
    ) {
        data class Owned(val owner: String?, val spec: SchemaItemSpec)

        val byIdentity = mutableMapOf<Triple<SchemaItemKind, String, Set<String>>, MutableList<Owned>>()
        ownerSpecs.forEach { (owner, specs) ->
            specs.forEach { spec ->
                val identity = Triple(spec.kind, spec.label, spec.properties.toSet())
                byIdentity.getOrPut(identity) { mutableListOf() } += Owned(owner, spec)
            }
        }
        byIdentity.forEach { (identity, owned) ->
            val distinctSpecs = owned.map { it.spec }.distinct()
            if (distinctSpecs.size > 1 && owned.any { ownerFilter.includes(it.owner) }) {
                throw DrivineException(
                    "Conflicting schema declarations for ${identity.first} on " +
                        "${identity.second}${identity.third.toList()} on database '$database', across owners: " +
                        owned.joinToString("; ") { "'${it.owner ?: "schema"}' declares ${it.spec}" } +
                        ". Reconcile the declaration, or have only one owner declare this item."
                )
            }
        }
    }

    /** The [SchemaItemSpec.inventoryKey]s declared by more than one live owner. */
    private fun coOwnedKeys(ownerSpecs: Map<String?, List<SchemaItemSpec>>): Set<String> =
        ownerSpecs.values
            .flatMap { specs -> specs.map { it.inventoryKey }.distinct() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    private fun reportOrphans(
        database: String,
        ownerLabel: String,
        versionStore: SchemaVersionStore,
        specs: List<SchemaItemSpec>,
        coOwnedKeys: Set<String>,
    ) {
        val stored = versionStore.storedInventory() ?: return // legacy marker: no known prior inventory
        val declared = specs.map { it.inventoryKey }.toSet()
        val orphans = stored.filter { it !in declared && it !in coOwnedKeys }
        if (orphans.isNotEmpty()) {
            logger.warn(
                "Owner '{}' on '{}' previously applied {} item(s) it no longer declares (orphans): {}. " +
                    "They are left in place; drop them manually, or via a deliberate recreate, if unwanted.",
                ownerLabel, database, orphans.size, orphans
            )
        }
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
     * Reports outcomes and records the owner's version and inventory. The marker is written only on
     * success — if FAIL_FAST throws on a problem, the marker is left untouched so the next enforce
     * retries. The inventory is recorded even when this owner has no version token, so orphan
     * detection works for un-versioned owners too.
     */
    private fun finish(
        database: String,
        ownerLabel: String,
        outcomes: List<Pair<SchemaItemSpec, EnsureResult>>,
        effectiveVersion: String?,
        specs: List<SchemaItemSpec>,
        versionStore: SchemaVersionStore,
    ) {
        val created = outcomes.count { it.second is EnsureResult.Created }
        val matching = outcomes.count { it.second is EnsureResult.AlreadyMatching }
        val recreated = outcomes.count { it.second is EnsureResult.Recreated }
        val problems = outcomes.filter { it.second is EnsureResult.Drift || it.second is EnsureResult.Violation }

        logger.info(
            "Schema for owner '{}' on '{}': {} created, {} already matching, {} recreated, {} needing attention",
            ownerLabel, database, created, matching, recreated, problems.size
        )

        if (problems.isNotEmpty() && policy.mode == SchemaMode.FAIL_FAST) {
            throw DrivineException(
                "Schema application for owner '$ownerLabel' on database '$database' found problems:\n" +
                    problems.joinToString("\n") { (spec, result) -> describe(spec, result) } + "\n" +
                    "Resolve them, set drivine.schema.recreate-on-drift=true (destructive), " +
                    "or set drivine.schema.mode=WARN to continue with warnings."
            )
        }

        // Record what this owner applied, versioned or not, so its inventory ties to the marker.
        versionStore.record(effectiveVersion, specs.map { it.inventoryKey })

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