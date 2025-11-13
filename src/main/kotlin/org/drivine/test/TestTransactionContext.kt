package org.drivine.test

/**
 * Thread-local context for test transaction configuration.
 * Used by DrivineTestTransactionListener to communicate rollback preferences
 * to the TransactionalAspect.
 */
object TestTransactionContext {
    private val rollbackFlag = ThreadLocal<Boolean>()

    fun setRollback(shouldRollback: Boolean) {
        rollbackFlag.set(shouldRollback)
    }

    fun shouldRollback(): Boolean? = rollbackFlag.get()

    fun clear() {
        rollbackFlag.remove()
    }
}
