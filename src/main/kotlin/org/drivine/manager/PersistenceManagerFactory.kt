package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseRegistry
import org.drivine.transaction.TransactionContextHolder
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap



data class PersistenceManagerEntry(
    val transactional: TransactionalPersistenceManager,
    val nonTransactional: NonTransactionalPersistenceManager,
    val delegating: DelegatingPersistenceManager
)

class PersistenceManagerFactory(
    private val registry: DatabaseRegistry,
    private val contextHolder: TransactionContextHolder
) {
    /**
     * Concurrent, and populated atomically per name: each manager caches per-instance state (such as
     * store identity), so two threads first-touching one database must share one entry, not race to
     * register two.
     */
    private val managers = ConcurrentHashMap<String, PersistenceManagerEntry>()

    /**
     * Returns a PersistenceManager for the database registered under the specified name.
     * @param database Unique name for the registered database.
     * @param type Either TRANSACTIONAL, NON_TRANSACTION or (default) delegating persistence manager. The latter
     * will decide at runtime, depending on whether a transaction is in flight, ie whether the current context of execution
     * is @Transactional().
     */
    @JvmOverloads
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): PersistenceManager {
        val entry = managers.computeIfAbsent(database, ::register)
        return when (type) {
            PersistenceManagerType.TRANSACTIONAL -> entry.transactional
            PersistenceManagerType.NON_TRANSACTIONAL -> entry.nonTransactional
            PersistenceManagerType.DELEGATING -> entry.delegating
        }
    }

    private fun register(name: String): PersistenceManagerEntry {
        val connectionProvider = registry.connectionProvider(name)
            ?: throw DrivineException("No database is registered under name: $name")

        val grammar = connectionProvider.grammar
        // The non-transactional manager owns the schema managers (DDL must run in auto-commit
        // mode); the transactional manager borrows them from it.
        val nonTransactional = NonTransactionalPersistenceManager(connectionProvider, name, connectionProvider.type, registry.subtypeRegistry)
        return PersistenceManagerEntry(
            transactional = TransactionalPersistenceManager(contextHolder, name, connectionProvider.type, registry.subtypeRegistry, grammar, nonTransactional),
            nonTransactional = nonTransactional,
            delegating = DelegatingPersistenceManager(name, connectionProvider.type, contextHolder, this, registry.subtypeRegistry, grammar)
        )
    }
}
