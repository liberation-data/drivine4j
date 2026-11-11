package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseRegistry
import org.drivine.cypher.store.plan.DeclaredCatalog
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.Owners
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.cypher.store.plan.RuntimeCatalog
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.cypher.store.schema.Neo4jSchema
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.slf4j.LoggerFactory
import java.time.Duration

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
 * Outcomes needing attention (drift, violations, an index in the way of a constraint, an index not built) are handled
 * per [SchemaPolicy].
 *
 * **Index builds.** Neo4j and FalkorDB build an index in the background, and an index is applied only once it is
 * built, waiting at most [indexBuildTimeout] for it (see [IndexManager.ensure]). An index whose build failed, or that
 * is still being built when the wait runs out, needs attention: under `FAIL_FAST` it fails its owner, naming the
 * index and giving the engine's reason, and under `WARN` it is logged so and the rest goes on, the index left as it
 * is and reported as the operation was (created, already matching or recreated). The next [enforce] waits for it
 * again.
 *
 * **Vector fields.** Before anything is applied, a [VectorIndexSpec] whose label and property a
 * fragment class maps to a field without `@VectorIndex` is refused, naming the class, the field and
 * the spec; a fragment class built later is checked against the specs applied here. See
 * [org.drivine.annotation.VectorIndex].
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
 * **Decided by the module.** What each call reads, makes, drops, recreates, waits for and records on a database, and
 * what it reports, is decided by the Cypher compiler's module's schema planner (`org.drivine.cypher.store.plan`), which
 * the three Drivine libraries share: this manager resolves the databases, in order of their names, asks the planner for
 * each ([PlanRequest]), runs what it decides ([SchemaPlanDriver]), and logs and throws what its report says. The items
 * ensured at runtime are kept as the module says ([SchemaPlanner.runtimeAfter]).
 *
 * All DDL runs in auto-commit mode (the non-transactional manager) — schema operations cannot run
 * inside an open data transaction.
 */
class SchemaManager(
    private val persistenceManagerFactory: PersistenceManagerFactory,
    private val databaseRegistry: DatabaseRegistry,
    private val catalogs: List<SchemaCatalog>,
    private val policy: SchemaPolicy,
    /**
     * How long an index that Neo4j or FalkorDB builds in the background is waited for, each time it is applied,
     * before it is taken as still being built (`drivine.schema.index-build-timeout`): 60 seconds unless given.
     */
    val indexBuildTimeout: Duration,
) {

    /** A manager that waits [IndexManager.DEFAULT_BUILD_TIMEOUT], 60 seconds, for an index to be built. */
    constructor(
        persistenceManagerFactory: PersistenceManagerFactory,
        databaseRegistry: DatabaseRegistry,
        catalogs: List<SchemaCatalog>,
        policy: SchemaPolicy = SchemaPolicy(),
    ) : this(persistenceManagerFactory, databaseRegistry, catalogs, policy, IndexManager.DEFAULT_BUILD_TIMEOUT)

    init {
        require(!indexBuildTimeout.isNegative) { "indexBuildTimeout must not be negative: $indexBuildTimeout" }
    }

    private val logger = LoggerFactory.getLogger(SchemaManager::class.java)

    /**
     * What [ensure] was given, by owner and target, each item once by its identity (kind, label, properties), as the
     * module keeps them ([SchemaPlanner.runtimeAfter]): an item ensured again with the same identity replaces the one before.
     */
    private var runtime: List<RuntimeCatalog> = emptyList()

    /** Serializes [enforce], [recreateAll], [ensure] and [remove], which read and write the [runtime] items and the owners' markers. */
    private val lock = Any()

    /**
     * Idempotent, version-aware application of **every** owner. Safe to call on every startup and at
     * runtime: items are ensured (creating what's missing, reporting drift), and an owner's items are
     * recreated only when its effective version changed or [SchemaPolicy] requests it.
     */
    fun enforce() = apply(Operation.Enforce(Owners.All))

    /**
     * Idempotent, version-aware application of a single owner's catalogs (see [SchemaCatalog.named]).
     * The specific call site for a library that wants to converge its own schema without touching the
     * host application's. Pass `null` to target the anonymous (unnamed) owner.
     */
    fun enforce(name: String?) = apply(Operation.Enforce(Owners.One(name)))

    /**
     * Brute-force: drop and recreate **every** item of **every** owner on every target database,
     * regardless of version. The post-embedding hammer. Destructive: recreating a vector index
     * rebuilds it from the stored embedding properties (it does not re-embed); recreating a
     * uniqueness constraint can surface a violation if data conflicts. Because it is explicit, it
     * **does** rebuild co-owned items too.
     */
    fun recreateAll() = apply(Operation.RecreateAll(Owners.All))

    /**
     * Brute-force recreate of a single owner's items (see [SchemaCatalog.named]) — the escape hatch a
     * library uses to rebuild only its own schema after a bulk re-embed. Unlike a version-change
     * recreate, this **does** rebuild items co-owned with another owner, since the caller asked for it
     * explicitly; that will drop-and-recreate the shared item the other owner also depends on, so use
     * it deliberately. Pass `null` to target the anonymous (unnamed) owner.
     */
    fun recreateAll(name: String?) = apply(Operation.RecreateAll(Owners.One(name)))

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
     * applied as [enforce] applies the owner's: under the [SchemaPolicy] (a drift, a violation, or an index
     * whose build failed or did not end within [indexBuildTimeout] fails under `FAIL_FAST` and is logged
     * under `WARN`, and `recreateOnDrift` recreates a drifted item), with the same summary line, and
     * recorded in the owner's marker. Only the items given are applied; the owner's other items are left
     * as they are. Ensuring an item again with the same identity (kind,
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
            val operation = Operation.Ensure(catalog.name, catalog.items)
            val candidate = SchemaPlanner.runtimeAfter(operation, runtime, databasesOf(catalog.target))
            val resolution = SchemaTargets.resolve(listOf(catalog), databaseRegistry)
            warnSkipped(resolution)
            // Every database is checked before anything is kept or applied, so a conflict leaves both as they were.
            val planned = resolution.databases.sorted().map { database ->
                val planning = planning(database)
                val request = request(database, operation, candidate)
                planning.driver.validate(request)?.let { throw DrivineException(it) }
                planning to request
            }
            VectorAnnotationCheck.register(listOf(catalog))
            runtime = candidate
            planned.forEach { (planning, request) -> planning.run(request) }
        }
    }

    /**
     * Removes [specs] from what [owner] declares, on every schema-capable database. See the overload taking a
     * catalog.
     */
    fun remove(owner: String?, vararg specs: SchemaItemSpec) = remove(SchemaCatalog.of(specs.toList()).named(owner))

    /**
     * Removes the catalog's items from what its owner ([SchemaCatalog.named]) declares, on its databases
     * ([SchemaCatalog.forDatabase]): the counterpart of [ensure]. Each item is found by its identity (kind, label
     * and properties), whatever its name.
     *
     * An item [ensure] gave the owner is forgotten, so that a later [enforce] does not make it again. On each
     * database, the item is then dropped, unless a startup catalog (of any owner) or another owner's runtime items
     * declare it, or another owner's marker there records it (declared or ensured at runtime, by whichever manager
     * or process): it is then left in place, with a warning naming who declares it. Either way the owner's marker
     * no longer records it, so it is no orphan of the owner. An item that is not there is dropped as nothing,
     * and forgotten all the same.
     *
     * The catalog must not carry a version.
     */
    fun remove(catalog: SchemaCatalog) {
        if (catalog.version != null) {
            throw DrivineException("Schema removed at runtime cannot carry a version (${catalog.version}): an owner's version is declared by its startup catalogs.")
        }
        if (catalog.isEmpty()) return
        synchronized(lock) {
            val operation = Operation.Remove(catalog.name, catalog.items)
            VALIDATOR.validate(PlanRequest(DEFAULT_DATABASE, operation))?.let { throw DrivineException(it) }
            // The items are forgotten before the databases are resolved: a target refused then leaves them forgotten.
            runtime = SchemaPlanner.runtimeAfter(operation, runtime, databasesOf(catalog.target))
            val resolution = SchemaTargets.resolve(listOf(catalog), databaseRegistry)
            warnSkipped(resolution)
            resolution.databases.sorted().forEach { database -> planning(database).run(request(database, operation, runtime)) }
        }
    }

    /** What [ensure] has given [owner], on any database. */
    internal fun ensured(owner: String?): List<SchemaItemSpec> = synchronized(lock) {
        runtime.filter { it.owner == owner }.flatMap { it.items }
    }

    private fun apply(operation: Operation) {
        synchronized(lock) {
            val all = catalogs + runtime.map(::catalogOf)
            if (all.none { !it.isEmpty() }) {
                logNothingToApply()
                return
            }
            VectorAnnotationCheck.register(all)
            val resolution = SchemaTargets.resolve(all, databaseRegistry)
            warnSkipped(resolution)
            resolution.databases.sorted().forEach { database -> planning(database).run(request(database, operation, runtime)) }
        }
    }

    /** What the planner is asked of [database]: its startup catalogs, of every owner, and the [runtime] items on it. */
    private fun request(database: String, operation: Operation, runtime: List<RuntimeCatalog>) = PlanRequest(
        database = database,
        operation = operation,
        catalogs = catalogs.filter { it.target.includes(database) }.map { DeclaredCatalog(it.name, it.version, it.items) },
        runtime = SchemaPlanner.runtimeOn(database, runtime),
        policy = policy,
        buildTimeoutMillis = indexBuildTimeout.toMillis(),
    )

    /** Where [database]'s plans run: its auto-commit manager, whose schema managers run what the planner decides. */
    private inner class Planning(database: String) {
        // Schema DDL must run in auto-commit mode.
        val manager = persistenceManagerFactory.get(database, PersistenceManagerType.NON_TRANSACTIONAL)
        val driver = manager.indexes.driver

        fun run(request: PlanRequest) {
            driver.run(request, manager)
        }
    }

    private fun planning(database: String) = Planning(database)

    /** The runtime items of [runtime] as a catalog of their owner on their databases, which [SchemaTargets] resolves. */
    private fun catalogOf(runtime: RuntimeCatalog): SchemaCatalog =
        SchemaCatalog.of(runtime.items).named(runtime.owner).withTarget(runtime.databases?.let(DatabaseTarget::Named) ?: DatabaseTarget.All)

    /** The databases [target] names, as the module keeps runtime items by them: null for all. */
    private fun databasesOf(target: DatabaseTarget): Set<String>? = (target as? DatabaseTarget.Named)?.databases

    private fun warnSkipped(resolution: SchemaTargets.Resolution) {
        val (refused, incapable) = resolution.skipped.map { it to databaseRegistry.connectionProvider(it)?.schemaRefusal }
            .partition { (_, refusal) -> refusal != null }
        refused.forEach { (database, refusal) ->
            logger.warn("Skipping schema management for database '{}': {}", database, refusal)
        }
        if (incapable.isNotEmpty()) {
            logger.warn(
                "Skipping schema management for databases whose engine has no DDL support: {}",
                incapable.map { it.first }.toSet()
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

    private companion object {
        /** Checks what reads no store: a remove's items, before any database is resolved. */
        val VALIDATOR = SchemaPlanner(Neo4jSchema())

        const val DEFAULT_DATABASE = "default"
    }
}
