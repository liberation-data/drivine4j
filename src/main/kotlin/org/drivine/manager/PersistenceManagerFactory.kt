package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseRegistry
import org.drivine.transaction.TransactionContextHolder
import org.springframework.stereotype.Component



data class PersistenceManagerEntry(
    val transactional: TransactionalPersistenceManager,
    val nonTransactional: NonTransactionalPersistenceManager,
    val delegating: DelegatingPersistenceManager
)

class PersistenceManagerFactory(
    private val registry: DatabaseRegistry,
    private val contextHolder: TransactionContextHolder
) {
    private val managers: MutableMap<String, PersistenceManagerEntry> = mutableMapOf()

    /**
     * Returns a PersistenceManager for the database registered under the specified name.
     * @param database Unique name for the registered database.
     * @param type Either TRANSACTIONAL, NON_TRANSACTION or (default) delegating persistence manager. The latter
     * will decide at runtime, depending on whether a transaction is in flight, ie whether the current context of execution
     * is @Transactional().
     */
    @JvmOverloads
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): PersistenceManager {
        if (!managers.containsKey(database)) {
            register(database)
        }
        return when (type) {
            PersistenceManagerType.TRANSACTIONAL -> {
                managers[database]?.transactional
                    ?: throw DrivineException("No transactional manager found for database: $database")
            }
            PersistenceManagerType.NON_TRANSACTIONAL -> {
                managers[database]?.nonTransactional
                    ?: throw DrivineException("No non-transactional manager found for database: $database")
            }
            PersistenceManagerType.DELEGATING -> {
                managers[database]?.delegating
                    ?: throw DrivineException("No delegating manager found for database: $database")
            }
        }
    }

    private fun register(name: String) {
        val connectionProvider = registry.connectionProvider(name)
            ?: throw DrivineException("No database is registered under name: $name")

        managers[name] = PersistenceManagerEntry(
            transactional = TransactionalPersistenceManager(contextHolder, name, connectionProvider.type, registry.subtypeRegistry),
            nonTransactional = NonTransactionalPersistenceManager(connectionProvider, name, connectionProvider.type, registry.subtypeRegistry),
            delegating = DelegatingPersistenceManager(name, connectionProvider.type, contextHolder, this, registry.subtypeRegistry)
        )
    }
}
