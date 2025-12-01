package org.drivine.manager

/**
 * Factory for creating GraphViewManager instances.
 * Uses PersistenceManagerFactory to inject PersistenceManager instances.
 */
class GraphViewManagerFactory(
    private val persistenceManagerFactory: PersistenceManagerFactory
) {
    private val managers: MutableMap<String, GraphViewManager> = mutableMapOf()

    /**
     * Returns a GraphViewManager for the database registered under the specified name.
     * @param database Unique name for the registered database.
     * @param type The type of PersistenceManager to use (TRANSACTIONAL, NON_TRANSACTIONAL, or DELEGATING).
     */
    @JvmOverloads
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): GraphViewManager {
        val key = "$database:$type"
        if (!managers.containsKey(key)) {
            val persistenceManager = persistenceManagerFactory.get(database, type)
            managers[key] = GraphViewManager(persistenceManager)
        }
        return managers[key]!!
    }
}