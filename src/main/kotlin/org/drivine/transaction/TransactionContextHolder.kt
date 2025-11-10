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
     * Gets the current Transaction from thread-local storage.
     */
    var currentTransaction: Transaction?
        get() = localStorage.get()[TransactionContextKeys.TRANSACTION.name] as Transaction?
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
