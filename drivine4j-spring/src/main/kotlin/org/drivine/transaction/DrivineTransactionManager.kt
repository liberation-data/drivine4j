package org.drivine.transaction

import org.drivine.DrivineException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

/**
 * Spring PlatformTransactionManager implementation for Drivine.
 *
 * Lets `@Transactional` methods and `TransactionTemplate` run Drivine's transactions. Spring decides
 * propagation; the transaction itself is a [DrivineTransactionObject] kept in the
 * [TransactionContextHolder], where Drivine's own [Transactions] keeps the ones it begins. Given the
 * same holder, the two join each other's transactions on a thread.
 *
 * The actual database-specific behavior is delegated to ConnectionProvider and
 * Connection implementations (e.g., Neo4jConnectionProvider, Neo4jConnection).
 */
class DrivineTransactionManager(
    private val contextHolder: TransactionContextHolder
) : AbstractPlatformTransactionManager() {

    init {
        // Configure default behavior
        isNestedTransactionAllowed = false
        isValidateExistingTransaction = true
    }

    override fun doGetTransaction(): Any {
        val existingTxObject = contextHolder.currentTransaction
        return existingTxObject ?: DrivineTransactionObject()
    }

    override fun isExistingTransaction(transaction: Any): Boolean {
        val txObject = transaction as DrivineTransactionObject
        return contextHolder.currentTransaction === txObject
    }

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        val txObject = transaction as DrivineTransactionObject

        try {
            logger.debug("Beginning transaction: ${txObject.id}, readOnly=${definition.isReadOnly}")

            // Store in context holder
            contextHolder.currentTransaction = txObject

            // Connections will be lazily acquired when first query is executed
            // via the PersistenceManager

        } catch (ex: Exception) {
            throw DrivineException("Could not begin transaction", ex)
        }
    }

    /** Commits, unless a block of Drivine's own that joined it asked to roll back (`rollback = true`). */
    override fun doCommit(status: DefaultTransactionStatus) {
        val txObject = status.transaction as DrivineTransactionObject
        if (txObject.rollbackOnCompletion) txObject.rollback() else txObject.commit()
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        (status.transaction as DrivineTransactionObject).rollback()
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        val txObject = transaction as DrivineTransactionObject

        try {
            txObject.release()

            // Clear from context holder
            if (contextHolder.currentTransaction === txObject) {
                contextHolder.currentTransaction = null
            }

        } catch (ex: Exception) {
            logger.warn("Error during transaction cleanup: ${ex.message}", ex)
        }
    }

    override fun doSuspend(transaction: Any): Any {
        val txObject = transaction as DrivineTransactionObject
        contextHolder.currentTransaction = null
        return txObject
    }

    override fun doResume(transaction: Any?, suspendedResources: Any) {
        val txObject = suspendedResources as DrivineTransactionObject
        contextHolder.currentTransaction = txObject
    }

    override fun doSetRollbackOnly(status: DefaultTransactionStatus) {
        val txObject = status.transaction as DrivineTransactionObject
        txObject.rollbackOnly = true
    }
}
