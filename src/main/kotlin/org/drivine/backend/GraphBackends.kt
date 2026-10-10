package org.drivine.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.backend.oracle.OracleBackend
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager

internal object GraphBackends {

    /** The backend for the store [persistenceManager] speaks to. */
    fun of(persistenceManager: PersistenceManager, objectMapper: ObjectMapper): GraphBackend = when (persistenceManager.type) {
        DatabaseType.ORACLE -> OracleBackend(persistenceManager, objectMapper)
        else -> CypherBackend(persistenceManager, persistenceManager.grammar, objectMapper)
    }
}
