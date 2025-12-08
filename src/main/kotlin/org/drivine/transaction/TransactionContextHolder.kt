package org.drivine.transaction

import org.drivine.connection.DatabaseRegistry

/**
 * Holds transaction context in thread-local storage.
 * Must be configured as a bean in your Spring configuration.
 */
class TransactionContextHolder(
    val databaseRegistry: DatabaseRegistry
) {

    // ThreadLocal storage for context values
    private val localStorage: ThreadLocal<MutableMap<String, Any>> = ThreadLocal.withInitial { mutableMapOf() }

    /**
     * Gets the current DrivineTransactionObject from thread-local storage.
     * This is managed by Spring's PlatformTransactionManager.
     */
    var currentTransaction: DrivineTransactionObject?
        get() = localStorage.get()[TransactionContextKeys.TRANSACTION.name] as DrivineTransactionObject?
        set(value) {
            val map = localStorage.get()
            if (value == null) {
                map.remove(TransactionContextKeys.TRANSACTION.name)
            } else {
                map[TransactionContextKeys.TRANSACTION.name] = value
            }
        }


    private fun tearDown() {
        localStorage.remove()
    }
}
