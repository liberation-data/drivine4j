package org.drivine.schema

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform

/**
 * Persists what a schema-catalog owner last applied to a database, in a reserved marker node.
 *
 * The version token ([SchemaCatalog.withVersion]) is how Drivine detects changes that index/constraint
 * introspection cannot see — e.g. an embedding-model swap that keeps the same dimensions. Because the
 * change is invisible structurally, the last-applied token has to be persisted out-of-band; a node in
 * the graph is the only store that is portable across Neo4j, Memgraph, and FalkorDB (`MERGE` / `SET` /
 * `timestamp()` all work) and travels with a database dump.
 *
 * Alongside the version, the marker records the **inventory**: the [SchemaItemSpec.inventoryKey] of
 * every item this owner applied. That is what ties the managed items to the marker entry — it lets a
 * version-change recreate act on exactly the recorded set, and lets an owner's no-longer-declared
 * items (orphans) be detected instead of leaking silently. The inventory is stored as a single
 * newline-joined string property, keeping the marker within the `SET`-a-scalar lowest common
 * denominator all three engines share. An item ensured at runtime ([SchemaManager.ensure]) is
 * recorded there too, its key prefixed `runtime:` (or `runtime-stale:`, when the owner was recreated
 * before the item was ensured again), so a later enforce keeps it rather than counting it an orphan.
 *
 * **One marker per owner per database**, keyed by [scope]:
 * `(:`_DrivineSchema` {scope: <owner|'schema'>, version, appliedAt, items})`. The anonymous
 * (unnamed) owner uses `scope: 'schema'`, exactly as before this store gained per-owner keying, so
 * existing single-owner deployments read and write their historical marker with no migration.
 *
 * The singleton invariant (one marker per scope) needs guarding: `MERGE` is only atomic across
 * concurrent transactions when its key is backed by a uniqueness constraint, so two processes
 * enforcing at the same time (rolling deploy, scaled replicas) could otherwise each create a marker.
 * [ensureSingleton] installs that constraint on `scope` (still guarding the singleton invariant, now
 * per scope) — and first collapses any duplicates a pre-constraint race left behind, keeping the
 * most recently applied.
 *
 * All operations run through the supplied [PersistenceManager], which must be the auto-commit
 * (non-transactional) one — schema/marker writes cannot run inside an open data transaction.
 */
class SchemaVersionStore(
    private val manager: PersistenceManager,
    /** The owner scope this store reads/writes; [DEFAULT_SCOPE] for the anonymous (unnamed) owner. */
    private val scope: String = DEFAULT_SCOPE,
) {

    /**
     * The marker's statements in the store's own language: Cypher, unless the manager's grammar says
     * otherwise, as Oracle's does. See [SchemaGrammar.versionMarker].
     */
    private val statements: VersionMarkerStatements by lazy {
        manager.reservedNode("the schema marker", { versionMarker(LABEL, SCOPE_PROPERTY) }) { CypherVersionMarker(LABEL, SCOPE_PROPERTY) }
    }

    /**
     * The version last applied under this scope, or null if none has been recorded.
     *
     * If duplicate markers exist (a pre-constraint race), the most recently applied wins rather
     * than failing the read — [ensureSingleton] repairs the duplication itself.
     */
    fun storedVersion(): String? = manager.maybeGetOne(
        QuerySpecification
            .withStatement(statements.read("version"))
            .bind(mapOf("scope" to scope))
            .transform(String::class.java)
    )

    /**
     * The inventory keys last applied under this scope, or null if the marker predates inventory
     * tracking (a legacy marker with a `version` but no `items`). An empty list means the marker was
     * written by inventory-aware code with nothing to record; null means "unknown prior inventory",
     * so callers must not treat absence as "everything is an orphan".
     */
    fun storedInventory(): List<String>? = storedRecord()?.keys

    /**
     * The inventory last applied under this scope, with which of its items were ensured at runtime
     * ([SchemaManager.ensure]) and which of those wait to be recreated; null as for [storedInventory].
     */
    internal fun storedRecord(): RecordedInventory? {
        val raw = manager.maybeGetOne(
            QuerySpecification
                .withStatement(statements.read("items"))
                .bind(mapOf("scope" to scope))
                .transform(String::class.java)
        ) ?: return null
        val items = statements.items(raw)
        if (items.isEmpty()) {
            return RecordedInventory(emptyList(), emptySet(), emptySet())
        }
        val keys = mutableListOf<String>()
        val runtime = mutableSetOf<String>()
        val stale = mutableSetOf<String>()
        items.split(INVENTORY_SEPARATOR).forEach { entry ->
            when {
                entry.startsWith(STALE_RUNTIME_PREFIX) -> entry.removePrefix(STALE_RUNTIME_PREFIX).also { keys += it; runtime += it; stale += it }
                entry.startsWith(RUNTIME_PREFIX) -> entry.removePrefix(RUNTIME_PREFIX).also { keys += it; runtime += it }
                else -> keys += entry
            }
        }
        return RecordedInventory(keys, runtime, stale)
    }

    /**
     * What a marker recorded: the key of every item applied, which of them were ensured at runtime
     * rather than declared by a catalog, and which of those are to be recreated when next ensured,
     * because the owner was recreated while their specs were not known.
     */
    internal data class RecordedInventory(val keys: List<String>, val runtime: Set<String>, val stale: Set<String>)

    /** Records [version] with an empty inventory. Convenience for callers that only track a version. */
    fun record(version: String?) = record(version, emptyList())

    /** Records [version] and [inventory] as the last applied under this scope. */
    fun record(version: String?, inventory: List<String>) = record(version, inventory, emptySet(), emptySet())

    /**
     * Records [version] and [inventory], marking the keys in [runtime] as ensured at runtime and those
     * in [stale] as to be recreated when next ensured. A marked key is written with a prefix, in the
     * same newline-joined property, so the marker keeps its shape on every engine.
     */
    internal fun record(version: String?, inventory: List<String>, runtime: Set<String>, stale: Set<String>) {
        val entries = inventory.map { key ->
            when (key) {
                in stale -> STALE_RUNTIME_PREFIX + key
                in runtime -> RUNTIME_PREFIX + key
                else -> key
            }
        }
        try {
            merge(version, entries)
        } catch (e: Exception) {
            // Lost a concurrent-create race on the marker: the winner's node is committed now,
            // so a second MERGE matches it instead of creating.
            merge(version, entries)
        }
    }

    /**
     * Guards the singleton invariant: collapses duplicate markers for this scope (keeping the most
     * recently applied) and backs the MERGE key with a uniqueness constraint on `scope` so
     * concurrent [record] calls from separate processes cannot create a second marker. Idempotent;
     * called by [SchemaManager] before the marker is read or written. The constraint spans all
     * scopes (it is on the `scope` property), so it is installed once and shared by every owner.
     */
    fun ensureSingleton() {
        manager.execute(
            QuerySpecification
                .withStatement(statements.deleteAllButNewest)
                .bind(mapOf("scope" to scope))
        )
        // Neo4j refuses to create a constraint while an equivalent plain index exists — e.g. a
        // hand-created emergency fix on the marker key. The label is reserved to Drivine, so
        // reclaim it: replace the index with the constraint.
        manager.constraints.ensure(UniquenessConstraintSpec(LABEL, SCOPE_PROPERTY), replaceBlockingIndex = true)
    }

    private fun merge(version: String?, inventory: List<String>) {
        val items = inventory.joinToString(INVENTORY_SEPARATOR)
        manager.execute(QuerySpecification.withStatement(statements.merge).bind(statements.mergeBindings(scope, version, items)))
    }

    /** Removes this scope's marker (e.g. to force the next enforce to treat the schema as un-versioned). */
    fun clear() {
        manager.execute(
            QuerySpecification
                .withStatement(statements.delete)
                .bind(mapOf("scope" to scope))
        )
    }

    companion object {
        /** Reserved label for the schema-version marker node. */
        const val LABEL = "_DrivineSchema"

        /** Marker-node key property, uniqueness-constrained so MERGE is race-safe. */
        const val SCOPE_PROPERTY = "scope"

        /** Marker-node key for the anonymous (unnamed) owner — the historical, backward-compatible scope. */
        const val DEFAULT_SCOPE = "schema"

        /**
         * Historical alias kept for source compatibility; the anonymous owner's scope value.
         * @see DEFAULT_SCOPE
         */
        @Deprecated("Use DEFAULT_SCOPE", ReplaceWith("DEFAULT_SCOPE"))
        const val SCOPE = DEFAULT_SCOPE

        /** Separator for the newline-joined inventory string stored in `m.items`. */
        private const val INVENTORY_SEPARATOR = "\n"

        /** Prefix of an inventory entry for an item ensured at runtime. An inventory key starts with its kind, never with this. */
        private const val RUNTIME_PREFIX = "runtime:"

        /** Prefix of an inventory entry for an item ensured at runtime that is to be recreated when next ensured. */
        private const val STALE_RUNTIME_PREFIX = "runtime-stale:"

        /** The marker scope for a catalog owner: its name, or [DEFAULT_SCOPE] when unnamed. */
        fun scopeFor(ownerName: String?): String = ownerName ?: DEFAULT_SCOPE
    }
}
