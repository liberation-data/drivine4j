package drivine.transaction

import drivine.connection.DatabaseRegistry
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
            // Safely update the thread-local map by setting the value
            localStorage.get()[TransactionContextKeys.TRANSACTION.name] = value ?: return // Avoid setting null value
        }


    private fun tearDown() {
        localStorage.remove()
    }
}
