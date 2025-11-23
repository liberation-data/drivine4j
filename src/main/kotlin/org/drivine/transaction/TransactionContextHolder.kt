package org.drivine.transaction

import org.drivine.connection.DatabaseRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class TransactionContextHolder {

    @Autowired
    lateinit var databaseRegistry: DatabaseRegistry

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
