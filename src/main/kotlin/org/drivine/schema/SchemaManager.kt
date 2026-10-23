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
 * **Catalogs are read once.** The manager is given its catalogs when it is built — under Spring, every
 * [SchemaCatalog] bean there is when the `SchemaManager` bean is made — and keeps that list. A catalog
 * made later is never seen, and calling [enforce] again applies the same catalogs. When there is
 * nothing to apply (no catalog, or only empty ones) the manager says so at INFO, naming the empty ones.
 *
 * **Runtime schema.** Schema known only later — a vector index whose dimensions arrive with the
 * embedding model, an index per label a tenant declares — is given to [ensure], under an owner. Its
 * items join what that owner declared at startup, for the life of this manager: they are applied under
 * the same policy, recorded in the owner's inventory, checked against other owners, recreated with the
 * owner, and a later [enforce] keeps them. The marker records them as ensured at runtime, so a manager
 * built later (the next process) keeps them in the inventory, without reporting them as orphans, until
 * they are ensured again.
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
     * What [ensure] was given, by owner and target, each item by its identity (kind, label,
     * properties): an item ensured again with the same identity replaces the one before.
     */
    private val runtime = linkedMapOf<Pair<String?, DatabaseTarget>, LinkedHashMap<Identity, SchemaItemSpec>>()

    /** Serializes [enforce], [recreateAll] and [ensure], which read and write the [runtime] items and the owners' markers. */
    private val lock = Any()

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

    /**
     * Ensures schema known only at runtime under [owner] (see [SchemaCatalog.named]; `null` is the
     * anonymous owner), on every schema-capable database. See the overload taking a catalog.
     */
    fun ensure(owner: String?, specs: List<SchemaItemSpec>) = ensure(SchemaCatalog.of(specs).named(owner))

    /** Ensures schema known only at runtime under [owner]. See the overload taking a catalog. */
    fun ensure(owner: String?, vararg specs: SchemaItemSpec) = ensure(owner, specs.toList())

    /**
     * Ensures schema known only at runtime — a vector index whose dimensions arrive with the embedding
     * model, an index per label a tenant declares — under the catalog's owner ([SchemaCatalog.named])
     * and on its databases ([SchemaCatalog.forDatabase]).
     *
     * The items are **added** to what the owner declared at startup, for the life of this manager, and
     * applied as [enforce] applies the owner's: under the [SchemaPolicy] (a drift or violation fails
     * under `FAIL_FAST`, is logged under `WARN`, and `recreateOnDrift` recreates a drifted item), with
     * the same summary line, and recorded in the owner's marker. Only the items given are applied; the
     * owner's other items are left as they are. Ensuring an item again with the same identity (kind,
     * label, properties) but a different shape replaces it — a drift, handled per the policy.
     *
     * From then on the owner's [enforce] and [recreateAll] apply these items with the rest, and they
     * count as the owner's for co-ownership. A declaration that conflicts with another owner's, or with
     * the owner's own, fails here, naming both, and nothing is added or applied.
     *
     * The marker records the items as ensured at runtime, so an [enforce] by a manager built later,
     * before they are ensured again, keeps them in the inventory and does not report them as orphans;
     * when that enforce recreates the owner (its version changed, or [recreateAll]), they are marked, and
     * recreated the next time they are ensured. An item replaced by one of the same identity under
     * another name is an orphan of the owner, reported as such.
     *
     * `ensure` neither adopts nor acts on a change of the owner's version, which is [enforce]'s, and
     * `recreateOnStartup` does not apply to it. The catalog must not carry a version.
     */
    fun ensure(catalog: SchemaCatalog) {
        if (catalog.version != null) {
            throw DrivineException(
                "Schema ensured at runtime cannot carry a version (${catalog.version}): an owner's version " +
                    "is declared by its startup catalogs, with SchemaCatalog.withVersion."
            )
        }
        if (catalog.isEmpty()) {
            logger.info("Nothing to ensure for owner '{}': no items given", catalog.name ?: "schema")
            return
        }
        synchronized(lock) {
            val candidate = withRuntime(catalog)
            val runtimeCatalogs = runtimeCatalogs(candidate)
            val resolution = SchemaTargets.resolve(listOf(catalog), databaseRegistry)
            warnSkipped(resolution)
            val ownerFilter = OneOwner(catalog.name)
            // Plan every database before anything is kept or applied, so a conflict leaves both as they were.
            val plans = resolution.databases.sorted().mapNotNull { database ->
                plan(database, runtimeCatalogs, ownerFilter)?.let { database to it }
            }
            runtime.clear()
            runtime.putAll(candidate)
            val given = catalog.items.map { it.inventoryKey }.toSet()
            plans.forEach { (database, plan) ->
                applyToDatabase(database, plan, forceRecreate = false, ownerFilter = ownerFilter, only = given)
            }
        }
    }

    private sealed interface OwnerFilter {
        fun includes(name: String?): Boolean
    }

    private object AllOwners : OwnerFilter {
        override fun includes(name: String?) = true
    }

    private data class OneOwner(val name: String?) : OwnerFilter {
        override fun includes(name: String?) = name == this.name
    }

    /** What makes two declarations the same item: kind, label and property set. */
    private data class Identity(val kind: SchemaItemKind, val label: String, val properties: Set<String>)

    private fun identityOf(spec: SchemaItemSpec) = Identity(spec.kind, spec.label, spec.properties.toSet())

    /** The [runtime] items with [catalog]'s added, each replacing an item of its identity, owner and target. */
    private fun withRuntime(catalog: SchemaCatalog): Map<Pair<String?, DatabaseTarget>, LinkedHashMap<Identity, SchemaItemSpec>> {
        val copy = runtime.mapValuesTo(linkedMapOf()) { (_, specs) -> LinkedHashMap(specs) }
        val specs = copy.getOrPut(catalog.name to catalog.target) { linkedMapOf() }
        catalog.items.forEach { specs[identityOf(it)] = it }
        return copy
    }

    private fun runtimeCatalogs(items: Map<Pair<String?, DatabaseTarget>, Map<Identity, SchemaItemSpec>>): List<SchemaCatalog> =
        items.map { (key, specs) -> SchemaCatalog.of(specs.values.toList()).named(key.first).withTarget(key.second) }

    private fun apply(forceRecreate: Boolean, ownerFilter: OwnerFilter) {
        synchronized(lock) {
            val runtimeCatalogs = runtimeCatalogs(runtime)
            val all = catalogs + runtimeCatalogs
            if (all.none { !it.isEmpty() }) {
                logNothingToApply()
                return
            }
            val resolution = SchemaTargets.resolve(all, databaseRegistry)
            warnSkipped(resolution)
            resolution.databases.sorted().forEach { database ->
                val plan = plan(database, runtimeCatalogs, ownerFilter) ?: return@forEach
                applyToDatabase(database, plan, forceRecreate, ownerFilter, only = null)
            }
        }
    }

    private fun warnSkipped(resolution: SchemaTargets.Resolution) {
        if (resolution.skipped.isNotEmpty()) {
            logger.warn(
                "Skipping schema management for databases whose engine has no DDL support: {}",
                resolution.skipped
            )
        }
    }

    /**
     * Says why there is nothing to apply, so that a catalog never registered and a catalog that
     * registered nothing can be told from an application with no schema to manage. Both name the
     * snapshot: a catalog bean made after this manager was built is not seen.
     */
    private fun logNothingToApply() {
        if (catalogs.isEmpty()) {
            logger.info(
                "Schema management found no SchemaCatalog; nothing to apply. " +
                    "Catalogs are read once, when the SchemaManager is built; schema known later is given to SchemaManager.ensure."
            )
            return
        }
        val owners = catalogs
            .groupingBy { catalog -> catalog.name?.let { "'$it'" } ?: "unnamed" }
            .eachCount()
            .map { (owner, count) -> if (count == 1) owner else "$owner ($count)" }
        logger.info(
            "Schema management found {} SchemaCatalog(s), all empty: {}; nothing to apply. " +
                "Catalogs are read once, when the SchemaManager is built; schema known later is given to SchemaManager.ensure.",
            catalogs.size, owners.joinToString(", ")
        )
    }

    /** What one database is to be given: its catalogs, each owner's merged items, and which are co-owned or runtime-only. */
    private class Plan(
        val contributing: List<SchemaCatalog>,
        val ownerSpecs: Map<String?, List<SchemaItemSpec>>,
        val coOwnedKeys: Set<String>,
        /** Per owner, the keys of the items only [ensure] gave it, not one of its startup catalogs. */
        val runtimeOnlyKeys: Map<String?, Set<String>>,
    )

    /**
     * Merges what each owner declares on [database], startup and runtime alike, and fails on a
     * conflict; null when nothing applies there.
     */
    private fun plan(database: String, runtimeCatalogs: List<SchemaCatalog>, ownerFilter: OwnerFilter): Plan? {
        val declared = catalogs.filter { it.target.includes(database) && !it.isEmpty() }
        val ensured = runtimeCatalogs.filter { it.target.includes(database) && !it.isEmpty() }
        val contributing = declared + ensured
        if (contributing.isEmpty()) {
            return null
        }

        // Group by owner. Each owner is deduplicated, versioned, and recorded independently. This is
        // computed over *all* owners on the database (even when the filter targets one), so a co-owned
        // item is still recognized as shared and a cross-owner conflict is still caught.
        val ownerSpecs: Map<String?, List<SchemaItemSpec>> = contributing
            .groupBy { it.name }
            .mapValues { (_, cats) -> SchemaCatalog.of(cats.flatMap { it.items }).items }

        detectCrossOwnerConflicts(database, ownerSpecs, ownerFilter)

        val runtimeOnlyKeys = ensured.groupBy { it.name }.mapValues { (owner, cats) ->
            val atStartup = declared.filter { it.name == owner }.flatMap { it.items }.map { it.inventoryKey }.toSet()
            cats.flatMap { it.items }.map { it.inventoryKey }.toSet() - atStartup
        }
        return Plan(contributing, ownerSpecs, coOwnedKeys(ownerSpecs), runtimeOnlyKeys)
    }

    /** Applies [plan] to [database] for the owners [ownerFilter] takes; [only] the items of those keys, when given. */
    private fun applyToDatabase(database: String, plan: Plan, forceRecreate: Boolean, ownerFilter: OwnerFilter, only: Set<String>?) {
        val ownerSpecs = plan.ownerSpecs

        // Schema DDL must run in auto-commit mode.
        val manager = persistenceManagerFactory.get(database, PersistenceManagerType.NON_TRANSACTIONAL)

        val failures = mutableListOf<DrivineException>()
        ownerSpecs.keys
            .filter { ownerFilter.includes(it) }
            .sortedBy { it ?: "" }
            .forEach { owner ->
                val cats = plan.contributing.filter { it.name == owner }
                val declaredByOthers = ownerSpecs.filterKeys { it != owner }.values.flatten().map { it.inventoryKey }.toSet()
                try {
                    applyOwner(
                        database, manager, owner, ownerSpecs.getValue(owner), cats, plan.coOwnedKeys,
                        plan.runtimeOnlyKeys[owner].orEmpty(), declaredByOthers, forceRecreate, only,
                    )
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

    /**
     * Applies one owner's items on one database and records them in its marker. [only], when given,
     * is the keys of the items [ensure] was given: just those are applied, the version stays as it was
     * recorded, and `recreateOnStartup` does not apply.
     */
    private fun applyOwner(
        database: String,
        manager: PersistenceManager,
        owner: String?,
        specs: List<SchemaItemSpec>,
        cats: List<SchemaCatalog>,
        coOwnedKeys: Set<String>,
        runtimeOnlyKeys: Set<String>,
        declaredByOthers: Set<String>,
        forceRecreate: Boolean,
        only: Set<String>?,
    ) {
        val ownerLabel = owner ?: "schema"
        val ensuring = only != null
        val effectiveVersion = SchemaCatalog.combineVersions(cats)

        val versionStore = SchemaVersionStore(manager, SchemaVersionStore.scopeFor(owner))
        if (effectiveVersion != null) {
            // Guard the marker's singleton invariant (dedupe + uniqueness constraint on the MERGE
            // key) before reading or writing it — concurrent enforcing processes could otherwise
            // each create a marker.
            versionStore.ensureSingleton()
        }
        // ensure() records the version it finds, so a change it does not act on is left to enforce.
        val storedVersion = if (effectiveVersion != null || ensuring) versionStore.storedVersion() else null
        // A version *change* (a prior value existed and now differs) forces a rebuild of this owner's
        // items. A first-ever value is adopted without recreating, so enabling versioning never nukes
        // a healthy schema.
        val versionChanged = !ensuring && effectiveVersion != null && storedVersion != null && storedVersion != effectiveVersion
        val recreateOwner = versionChanged || (!ensuring && policy.recreateOnStartup)

        // Items an earlier manager ensured at runtime that this one has not been given: kept in the
        // inventory, not orphans, since they arrive later — unless an item declared now has the same
        // identity under another key (a rename), which makes the recorded one an orphan.
        val recorded = versionStore.storedRecord()
        val declaredKeys = specs.map { it.inventoryKey }.toSet()
        val identities = specs.map { it.inventoryKey.removeSuffix(it.effectiveName) }
        val carried = recorded?.runtime.orEmpty().filter { key -> key !in declaredKeys && identities.none { key.startsWith(it) } }
        val stale = recorded?.stale.orEmpty()

        // Report an owner's items that the marker recorded but that are no longer declared (orphans),
        // excluding any still co-owned by another live owner. Detection only; cleanup is manual or via
        // a deliberate recreateAll (see the docs) — auto-drop is a documented follow-up.
        reportOrphans(database, ownerLabel, recorded?.keys, declaredKeys + carried, coOwnedKeys)

        val toApply = if (only == null) specs else specs.filter { it.inventoryKey in only }
        if (ensuring) {
            logger.info(
                "Ensuring schema for owner '{}' on '{}': {} runtime items, {} declared in all",
                ownerLabel, database, toApply.size, specs.size
            )
        } else {
            logger.info(
                "Applying schema for owner '{}' on '{}': {} items (forceRecreate={}, versionChanged={}, version={})",
                ownerLabel, database, specs.size, forceRecreate, versionChanged, effectiveVersion
            )
        }

        val outcomes = mutableListOf<Pair<SchemaItemSpec, EnsureResult>>()
        toApply.forEach { spec ->
            // A co-owned item is never dropped by a single owner's version bump — ensure it instead.
            // Only an explicit recreateAll (forceRecreate) rebuilds a co-owned item. A runtime item
            // marked stale missed its owner's recreate, and is recreated now.
            val coOwned = spec.inventoryKey in coOwnedKeys
            val recreate = forceRecreate || ((recreateOwner || spec.inventoryKey in stale) && !coOwned)
            outcomes += spec to when (spec) {
                is IndexSpec -> applyIndex(manager, spec, recreate)
                is ConstraintSpec -> applyConstraint(manager, spec, recreate)
            }
        }

        finish(database, ownerLabel, outcomes) {
            // A carried item misses a recreate of its owner, having no spec here: mark it, so that it
            // is recreated when next ensured. A version change spares one another owner declares, as
            // it spares a co-owned item. A runtime item not applied this time keeps its mark.
            val applied = toApply.map { it.inventoryKey }.toSet()
            val staleAfter = stale.filter { it in runtimeOnlyKeys && it !in applied } +
                carried.filter { it in stale || forceRecreate || (recreateOwner && it !in declaredByOthers) }
            versionStore.record(
                if (ensuring) storedVersion else effectiveVersion,
                specs.map { it.inventoryKey } + carried,
                runtimeOnlyKeys + carried,
                staleAfter.toSet(),
            )
        }
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
        stored: List<String>?,
        kept: Set<String>,
        coOwnedKeys: Set<String>,
    ) {
        if (stored == null) return // legacy marker: no known prior inventory
        val orphans = stored.filter { it !in kept && it !in coOwnedKeys }
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
     * Reports outcomes and records the owner's version and inventory ([record]). The marker is written
     * only on success — if FAIL_FAST throws on a problem, the marker is left untouched so the next
     * enforce retries. The inventory is recorded even when this owner has no version token, so orphan
     * detection works for un-versioned owners too.
     */
    private fun finish(
        database: String,
        ownerLabel: String,
        outcomes: List<Pair<SchemaItemSpec, EnsureResult>>,
        record: () -> Unit,
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
        record()

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