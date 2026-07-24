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
 * denominator all three engines share.
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
     * The version last applied under this scope, or null if none has been recorded.
     *
     * If duplicate markers exist (a pre-constraint race), the most recently applied wins rather
     * than failing the read — [ensureSingleton] repairs the duplication itself.
     */
    fun storedVersion(): String? = manager.maybeGetOne(
        QuerySpecification
            .withStatement(
                "MATCH (m:`$LABEL` {scope: \$scope}) " +
                    "RETURN m.version ORDER BY m.appliedAt DESC LIMIT 1"
            )
            .bind(mapOf("scope" to scope))
            .transform(String::class.java)
    )

    /**
     * The inventory keys last applied under this scope, or null if the marker predates inventory
     * tracking (a legacy marker with a `version` but no `items`). An empty list means the marker was
     * written by inventory-aware code with nothing to record; null means "unknown prior inventory",
     * so callers must not treat absence as "everything is an orphan".
     */
    fun storedInventory(): List<String>? {
        val raw = manager.maybeGetOne(
            QuerySpecification
                .withStatement(
                    "MATCH (m:`$LABEL` {scope: \$scope}) " +
                        "RETURN m.items ORDER BY m.appliedAt DESC LIMIT 1"
                )
                .bind(mapOf("scope" to scope))
                .transform(String::class.java)
        ) ?: return null
        return if (raw.isEmpty()) emptyList() else raw.split(INVENTORY_SEPARATOR)
    }

    /** Records [version] with an empty inventory. Convenience for callers that only track a version. */
    fun record(version: String?) = record(version, emptyList())

    /** Records [version] and [inventory] as the last applied under this scope. */
    fun record(version: String?, inventory: List<String>) {
        try {
            merge(version, inventory)
        } catch (e: Exception) {
            // Lost a concurrent-create race on the marker: the winner's node is committed now,
            // so a second MERGE matches it instead of creating.
            merge(version, inventory)
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
                .withStatement(
                    "MATCH (m:`$LABEL` {scope: \$scope}) " +
                        "WITH m ORDER BY m.appliedAt DESC SKIP 1 DELETE m"
                )
                .bind(mapOf("scope" to scope))
        )
        val constraint = UniquenessConstraintSpec(LABEL, SCOPE_PROPERTY)
        try {
            manager.constraints.ensure(constraint)
        } catch (e: Exception) {
            // Neo4j refuses to create a constraint while an equivalent plain index exists — e.g. a
            // hand-created emergency fix on the marker key. The label is reserved to Drivine, so
            // reclaim it: replace the index with the constraint. Anything else rethrows.
            if (manager.constraints.find(constraint) != null ||
                !manager.indexes.drop(RangeIndexSpec(LABEL, SCOPE_PROPERTY))
            ) {
                throw e
            }
            manager.constraints.ensure(constraint)
        }
    }

    private fun merge(version: String?, inventory: List<String>) {
        manager.execute(
            QuerySpecification
                .withStatement(
                    "MERGE (m:`$LABEL` {scope: \$scope}) " +
                        "SET m.version = \$version, m.items = \$items, m.appliedAt = timestamp()"
                )
                .bind(
                    mapOf(
                        "scope" to scope,
                        "version" to version,
                        "items" to inventory.joinToString(INVENTORY_SEPARATOR),
                    )
                )
        )
    }

    /** Removes this scope's marker (e.g. to force the next enforce to treat the schema as un-versioned). */
    fun clear() {
        manager.execute(
            QuerySpecification
                .withStatement("MATCH (m:`$LABEL` {scope: \$scope}) DELETE m")
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

        /** The marker scope for a catalog owner: its name, or [DEFAULT_SCOPE] when unnamed. */
        fun scopeFor(ownerName: String?): String = ownerName ?: DEFAULT_SCOPE
    }
}