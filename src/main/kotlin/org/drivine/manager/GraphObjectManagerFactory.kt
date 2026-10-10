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
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class GraphObjectManagerFactory(
    private val persistenceManagerFactory: PersistenceManagerFactory,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry,
    /**
     * Applied to every manager this factory creates. See [GraphObjectManager.indexAdvice]; an
     * individual manager can still be turned up or down after it is handed out.
     */
    private val indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN,
    /** The session bound of every manager this factory creates. See [SessionManager.maxEntries]. */
    private val sessionMaxEntries: Int = SessionManager.DEFAULT_MAX_ENTRIES,
) {
    private val managers: MutableMap<String, GraphObjectManager> = mutableMapOf()
    private val statelessManagers: MutableMap<String, StatelessGraphObjectManager> = mutableMapOf()

    /**
     * Returns a GraphObjectManager for the database registered under the specified name.
     * @param database Unique name for the registered database.
     * @param type The type of PersistenceManager to use (TRANSACTIONAL, NON_TRANSACTIONAL, or DELEGATING).
     */
    @Deprecated("Use stateless(): GraphObjectManager is deprecated in favour of StatelessGraphObjectManager.", ReplaceWith("stateless(database, type)"))
    @JvmOverloads
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): GraphObjectManager {
        val key = "$database:$type"
        if (!managers.containsKey(key)) {
            val persistenceManager = persistenceManagerFactory.get(database, type)
            val sessionManager = SessionManager(objectMapper, sessionMaxEntries)
            managers[key] = GraphObjectManager(persistenceManager, sessionManager, objectMapper, subtypeRegistry)
                .apply { indexAdvice = this@GraphObjectManagerFactory.indexAdvice }
        }
        return managers[key]!!
    }

    /**
     * Returns a [StatelessGraphObjectManager] for the database registered under the specified name.
     * It keeps no session, so it can share a database with a manager from [get].
     */
    @JvmOverloads
    @Synchronized
    fun stateless(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): StatelessGraphObjectManager =
        statelessManagers.getOrPut("$database:$type") {
            StatelessGraphObjectManager(persistenceManagerFactory.get(database, type), objectMapper, subtypeRegistry)
                .apply { indexAdvice = this@GraphObjectManagerFactory.indexAdvice }
        }
}
