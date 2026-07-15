package org.drivine.schema

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform

/**
 * Persists the last schema version applied to a database, in a reserved marker node.
 *
 * The version token ([SchemaCatalog.withVersion]) is how Drivine detects changes that index/constraint
 * introspection cannot see — e.g. an embedding-model swap that keeps the same dimensions. Because the
 * change is invisible structurally, the last-applied token has to be persisted out-of-band; a node in
 * the graph is the only store that is portable across Neo4j, Memgraph, and FalkorDB (`MERGE` / `SET` /
 * `timestamp()` all work) and travels with a database dump.
 *
 * One singleton marker per database (each database is its own graph):
 * `(:`_DrivineSchema` {scope: 'schema', version, appliedAt})`.
 *
 * The singleton invariant needs guarding: `MERGE` is only atomic across concurrent transactions
 * when its key is backed by a uniqueness constraint, so two processes enforcing at the same time
 * (rolling deploy, scaled replicas) could otherwise each create a marker. [ensureSingleton]
 * installs that constraint — and first collapses any duplicates a pre-constraint race left behind,
 * keeping the most recently applied.
 *
 * All operations run through the supplied [PersistenceManager], which must be the auto-commit
 * (non-transactional) one — schema/marker writes cannot run inside an open data transaction.
 */
class SchemaVersionStore(private val manager: PersistenceManager) {

    /**
     * The version last applied to this database, or null if none has been recorded.
     *
     * If duplicate markers exist (a pre-constraint race), the most recently applied wins rather
     * than failing the read — [ensureSingleton] repairs the duplication itself.
     */
    fun storedVersion(): String? = manager.maybeGetOne(
        QuerySpecification
            .withStatement(
                "MATCH (m:`$LABEL` {scope: '$SCOPE'}) " +
                    "RETURN m.version ORDER BY m.appliedAt DESC LIMIT 1"
            )
            .transform(String::class.java)
    )

    /** Records [version] as the last applied to this database. */
    fun record(version: String) {
        try {
            merge(version)
        } catch (e: Exception) {
            // Lost a concurrent-create race on the marker: the winner's node is committed now,
            // so a second MERGE matches it instead of creating.
            merge(version)
        }
    }

    /**
     * Guards the singleton invariant: collapses duplicate markers (keeping the most recently
     * applied) and backs the MERGE key with a uniqueness constraint so concurrent [record] calls
     * from separate processes cannot create a second marker. Idempotent; called by
     * [SchemaManager] before the marker is read or written.
     */
    fun ensureSingleton() {
        manager.execute(
            QuerySpecification.withStatement(
                "MATCH (m:`$LABEL` {scope: '$SCOPE'}) " +
                    "WITH m ORDER BY m.appliedAt DESC SKIP 1 DELETE m"
            )
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

    private fun merge(version: String) {
        manager.execute(
            QuerySpecification
                .withStatement(
                    "MERGE (m:`$LABEL` {scope: '$SCOPE'}) " +
                        "SET m.version = \$version, m.appliedAt = timestamp()"
                )
                .bind(mapOf("version" to version))
        )
    }

    /** Removes the marker (e.g. to force the next enforce to treat the schema as un-versioned). */
    fun clear() {
        manager.execute(
            QuerySpecification.withStatement("MATCH (m:`$LABEL` {scope: '$SCOPE'}) DELETE m")
        )
    }

    companion object {
        /** Reserved label for the schema-version marker node. */
        const val LABEL = "_DrivineSchema"

        /** Marker-node key property, uniqueness-constrained so MERGE is race-safe. */
        const val SCOPE_PROPERTY = "scope"

        /** Marker-node key, so MERGE always targets the single version node. */
        const val SCOPE = "schema"
    }
}