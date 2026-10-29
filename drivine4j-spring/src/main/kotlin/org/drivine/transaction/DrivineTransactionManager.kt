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

    override fun doGetTransaction(): Any = SpringTransaction(contextHolder.currentTransaction)

    override fun isExistingTransaction(transaction: Any): Boolean =
        (transaction as SpringTransaction).drivine != null

    /**
     * Begins a new [DrivineTransactionObject]. For `REQUIRES_NEW` Spring calls this with the object
     * [doGetTransaction] gave, once [doSuspend] has set the current transaction aside: so the new
     * transaction is made here, and the suspended one is never begun again.
     */
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        val springTransaction = transaction as SpringTransaction

        try {
            val txObject = DrivineTransactionObject()
            logger.debug("Beginning transaction: ${txObject.id}, readOnly=${definition.isReadOnly}")
            springTransaction.drivine = txObject

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
        val txObject = status.drivine
        if (txObject.rollbackOnCompletion) txObject.rollback() else txObject.commit()
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        status.drivine.rollback()
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        val txObject = (transaction as SpringTransaction).drivine ?: return

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
        val springTransaction = transaction as SpringTransaction
        val suspended = checkNotNull(springTransaction.drivine) { "There is no transaction to suspend." }
        springTransaction.drivine = null
        contextHolder.currentTransaction = null
        return suspended
    }

    override fun doResume(transaction: Any?, suspendedResources: Any) {
        val txObject = suspendedResources as DrivineTransactionObject
        contextHolder.currentTransaction = txObject
    }

    override fun doSetRollbackOnly(status: DefaultTransactionStatus) {
        status.drivine.rollbackOnly = true
    }

    private val DefaultTransactionStatus.drivine: DrivineTransactionObject
        get() = checkNotNull((transaction as SpringTransaction).drivine) { "The transaction has no Drivine transaction." }

    /**
     * What Spring holds for one `getTransaction` call: the Drivine transaction it runs in, the
     * current one or one [doBegin] makes. It is not the transaction itself, so that `REQUIRES_NEW`
     * can set the current one aside and begin another.
     *
     * It is not a `SmartTransactionObject`: Spring doesn't read [DrivineTransactionObject.rollbackOnly],
     * and a transaction whose `@Transactional` participant failed still commits if the code that began
     * it carries on, as it always has.
     */
    private class SpringTransaction(var drivine: DrivineTransactionObject?)
}
