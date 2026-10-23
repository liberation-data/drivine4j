package org.drivine.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.backend.oracle.OracleBackend
import org.drivine.backend.postgres.PostgresBackend
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager

/**
 * Chooses the [GraphBackend] of a store by its [DatabaseType]: [OracleBackend] for Oracle,
 * [PostgresBackend] for Postgres, and [CypherBackend] with the manager's grammar for the graph engines
 * that speak Cypher. A store that
 * speaks neither has no backend, and is refused here: it is not sent Cypher. Each compares a
 * case-insensitive lookup with a lowercased copy where it can: see [LowerCopyLookups].
 */
internal object GraphBackends {

    /** The backend for the store [persistenceManager] speaks to. */
    fun of(persistenceManager: PersistenceManager, objectMapper: ObjectMapper): GraphBackend {
        val type = persistenceManager.type
        val backend = when {
            type == DatabaseType.ORACLE -> OracleBackend(persistenceManager, objectMapper)
            type == DatabaseType.POSTGRES -> PostgresBackend(persistenceManager, objectMapper)
            type.speaksCypher -> CypherBackend(persistenceManager, persistenceManager.grammar, objectMapper)
            else -> throw UnsupportedOperationException("Drivine has no object manager for $type: it does not speak Cypher, and has no backend of its own.")
        }
        // A manager with no schema management keeps no record of the lowercased copies, and compares without them.
        return LowerCopyLookups(backend) {
            if (persistenceManager.supportsSchemaManagement) runCatching { persistenceManager.indexes.lowerCopies }.getOrNull() else null
        }
    }
}
