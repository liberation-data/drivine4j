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
 * All operations run through the supplied [PersistenceManager], which must be the auto-commit
 * (non-transactional) one — schema/marker writes cannot run inside an open data transaction.
 */
class SchemaVersionStore(private val manager: PersistenceManager) {

    /** The version last applied to this database, or null if none has been recorded. */
    fun storedVersion(): String? = manager.maybeGetOne(
        QuerySpecification
            .withStatement("MATCH (m:`$LABEL` {scope: '$SCOPE'}) RETURN m.version")
            .transform(String::class.java)
    )

    /** Records [version] as the last applied to this database. */
    fun record(version: String) {
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

        /** Marker-node key, so MERGE always targets the single version node. */
        const val SCOPE = "schema"
    }
}