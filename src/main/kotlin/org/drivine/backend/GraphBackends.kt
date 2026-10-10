package org.drivine.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager

internal object GraphBackends {

    /** The backend for the store [persistenceManager] speaks to. */
    fun of(persistenceManager: PersistenceManager, objectMapper: ObjectMapper): GraphBackend = when (persistenceManager.type) {
        DatabaseType.ORACLE -> throw UnsupportedOperationException("The object managers do not yet speak to Oracle; PersistenceManager does.")
        else -> CypherBackend(persistenceManager, persistenceManager.grammar, objectMapper)
    }
}
