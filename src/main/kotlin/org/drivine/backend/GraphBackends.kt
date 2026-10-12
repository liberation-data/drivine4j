package org.drivine.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.backend.oracle.OracleBackend
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager

/**
 * Chooses the [GraphBackend] of a store by its [DatabaseType]: [OracleBackend] for Oracle, and
 * [CypherBackend] with the manager's grammar for the graph engines that speak Cypher. A store that
 * speaks neither has no backend, and is refused here: it is not sent Cypher.
 */
internal object GraphBackends {

    /** The backend for the store [persistenceManager] speaks to. */
    fun of(persistenceManager: PersistenceManager, objectMapper: ObjectMapper): GraphBackend {
        val type = persistenceManager.type
        return when {
            type == DatabaseType.ORACLE -> OracleBackend(persistenceManager, objectMapper)
            type.speaksCypher -> CypherBackend(persistenceManager, persistenceManager.grammar, objectMapper)
            else -> throw UnsupportedOperationException("Drivine has no object manager for $type: it does not speak Cypher, and has no backend of its own.")
        }
    }
}
