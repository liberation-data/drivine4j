package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.session.SessionManager

/**
 * Factory for creating GraphObjectManager instances.
 * Uses PersistenceManagerFactory to inject PersistenceManager instances.
 * Each GraphObjectManager gets its own SessionManager instance.
 */
class GraphObjectManagerFactory(
    private val persistenceManagerFactory: PersistenceManagerFactory,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry,
    /**
     * Applied to every manager this factory creates. See [GraphObjectManager.indexAdvice]; an
     * individual manager can still be turned up or down after it is handed out.
     */
    private val indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN,
) {
    private val managers: MutableMap<String, GraphObjectManager> = mutableMapOf()

    /**
     * Returns a GraphObjectManager for the database registered under the specified name.
     * @param database Unique name for the registered database.
     * @param type The type of PersistenceManager to use (TRANSACTIONAL, NON_TRANSACTIONAL, or DELEGATING).
     */
    @JvmOverloads
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): GraphObjectManager {
        val key = "$database:$type"
        if (!managers.containsKey(key)) {
            val persistenceManager = persistenceManagerFactory.get(database, type)
            val sessionManager = SessionManager(objectMapper)
            managers[key] = GraphObjectManager(persistenceManager, sessionManager, objectMapper, subtypeRegistry)
                .apply { indexAdvice = this@GraphObjectManagerFactory.indexAdvice }
        }
        return managers[key]!!
    }
}