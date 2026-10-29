package org.drivine.transaction

import org.drivine.connection.DatabaseRegistry

/**
 * Holds the transaction in progress in thread-local storage. [Transactions] begins and ends
 * transactions in it, and so does the Spring adapter's `DrivineTransactionManager`, given the same
 * holder: so the two join each other's transactions. The persistence managers read it to find the
 * transaction a statement runs in.
 */
class TransactionContextHolder(
    val databaseRegistry: DatabaseRegistry
) {

    // ThreadLocal storage for context values
    private val localStorage: ThreadLocal<MutableMap<String, Any>> = ThreadLocal.withInitial { mutableMapOf() }

    /**
     * Gets the current DrivineTransactionObject from thread-local storage, or null outside a
     * transaction. Set by [Transactions], or by Spring's `DrivineTransactionManager`.
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
