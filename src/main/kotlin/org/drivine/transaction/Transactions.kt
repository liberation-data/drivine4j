package org.drivine.transaction

import org.slf4j.LoggerFactory
import java.util.concurrent.Callable

/**
 * Runs a block in a transaction: the same transactions Spring's `@Transactional` and
 * `TransactionTemplate` run through `drivine4j-spring`'s `DrivineTransactionManager`, kept in the same
 * [TransactionContextHolder]. So a block joins a `@Transactional` method's transaction, and a
 * `@Transactional` method called from a block joins the block's. The starter makes one as a bean; an
 * application with no framework gets one from [org.drivine.Drivine].
 *
 * ```kotlin
 * val transactions = Transactions(contextHolder)
 * transactions.transaction {
 *     gom.save(person)
 *     gom.save(audit)
 * } // both commit here; a throw out of the block rolls both back
 * ```
 *
 * From Java, `transactions.transaction(() -> gom.save(person))`, or [transactionWithoutResult]
 * for a block that returns nothing.
 *
 * The transaction is the calling thread's, as Spring's is: a block's work on another thread is not in it.
 *
 * - The outermost block's transaction commits when the block returns, and rolls back when anything
 *   is thrown out of it, a checked exception too.
 * - A block that joins a transaction leaves its end to the code that began it. An exception thrown
 *   out of the joining block marks the transaction [DrivineTransactionObject.rollbackOnly], as Spring
 *   marks one for a `@Transactional` method that throws, but the transaction still commits if the
 *   caller catches the exception and carries on: Drivine's Spring adapter has always committed it.
 * - A connection is opened only when a statement runs, so a block that never reaches Drivine costs
 *   no more than setting a thread-local.
 * - On FalkorDB, which commits each statement as it runs, how a transaction is taken is its
 *   [org.drivine.connection.FalkorDbTransactionMode]: in `WARN` the block runs and a rollback undoes
 *   nothing, in `STRICT` the block's first statement is refused.
 */
class Transactions(
    private val contextHolder: TransactionContextHolder,
) {

    /** The transaction the calling thread is in, begun by a block or by Spring, or null. */
    val current: DrivineTransactionObject?
        get() = contextHolder.currentTransaction

    /**
     * Runs [block] in a transaction as [propagation] says, and returns what it returns.
     *
     * - [Propagation.REQUIRED] (the default): join the current transaction, or begin one.
     * - [Propagation.REQUIRES_NEW]: begin one, setting the current transaction aside until it ends.
     * - [Propagation.SUPPORTS]: join the current transaction, or run without one.
     * - [Propagation.MANDATORY]: join the current transaction; refuse to run without one.
     * - [Propagation.NEVER]: run without a transaction; refuse to run in one.
     * - [Propagation.NOT_SUPPORTED]: run without a transaction, setting the current one aside.
     * - [Propagation.NESTED] is refused, as Spring's `DrivineTransactionManager` refuses it: no engine
     *   Drivine speaks to has savepoints it uses.
     *
     * Without a transaction each statement commits as it runs.
     *
     * @param rollback roll the transaction back at its end, instead of committing, though nothing
     *   failed: for a test that leaves nothing behind. Given to a block that joins a transaction, it
     *   makes the whole transaction roll back. Where [propagation] runs the block without a transaction
     *   (`SUPPORTS` with none, `NOT_SUPPORTED`, `NEVER`) there is nothing to roll back: each statement
     *   commits as it runs.
     * @throws IllegalTransactionStateException where [propagation] refuses to run.
     */
    @JvmOverloads
    fun <T> transaction(
        propagation: Propagation = Propagation.REQUIRED,
        rollback: Boolean = false,
        block: Callable<T>,
    ): T {
        val current = contextHolder.currentTransaction
        return when (propagation) {
            Propagation.REQUIRED -> if (current != null) join(current, rollback, block) else begin(rollback, block)
            Propagation.REQUIRES_NEW -> setAside { begin(rollback, block) }
            Propagation.SUPPORTS -> if (current != null) join(current, rollback, block) else block.call()
            Propagation.MANDATORY -> join(
                current ?: throw IllegalTransactionStateException("Propagation MANDATORY needs a transaction, and there is none."),
                rollback,
                block,
            )
            Propagation.NEVER -> if (current != null) {
                throw IllegalTransactionStateException("Propagation NEVER refuses to run in a transaction, and transaction ${current.id} is running.")
            } else {
                block.call()
            }
            Propagation.NOT_SUPPORTED -> setAside { block.call() }
            Propagation.NESTED -> throw IllegalTransactionStateException(
                "Propagation NESTED is not supported: Drivine has no savepoints. Use REQUIRED or REQUIRES_NEW."
            )
        }
    }

    /** As [transaction], for a block that returns nothing: from Java, a lambda with no `return`. */
    @JvmOverloads
    fun transactionWithoutResult(
        propagation: Propagation = Propagation.REQUIRED,
        rollback: Boolean = false,
        block: Runnable,
    ) {
        transaction(propagation, rollback) { block.run() }
    }

    private fun <T> join(transaction: DrivineTransactionObject, rollback: Boolean, block: Callable<T>): T {
        if (rollback) transaction.rollbackOnCompletion = true
        try {
            return block.call()
        } catch (ex: Throwable) {
            transaction.rollbackOnly = true
            throw ex
        }
    }

    /**
     * Begins a transaction, runs [block] in it, and ends it: the steps, and the order of them,
     * Spring's `AbstractPlatformTransactionManager` takes through the adapter.
     */
    private fun <T> begin(rollback: Boolean, block: Callable<T>): T {
        val transaction = DrivineTransactionObject().also { it.rollbackOnCompletion = rollback }
        logger.debug("Beginning transaction: ${transaction.id}")
        contextHolder.currentTransaction = transaction
        try {
            val result = try {
                block.call()
            } catch (ex: Throwable) {
                rollBackAfter(transaction, ex)
                throw ex
            }
            if (transaction.rollbackOnCompletion) {
                transaction.rollback()
            } else {
                try {
                    transaction.commit()
                } catch (ex: Exception) {
                    rollBackAfter(transaction, ex)
                    throw ex
                }
            }
            return result
        } finally {
            transaction.release()
            contextHolder.currentTransaction = null
        }
    }

    /** Rolls back on the way out of a failure, and keeps a failure to roll back from hiding the one that caused it. */
    private fun rollBackAfter(transaction: DrivineTransactionObject, cause: Throwable) {
        try {
            transaction.rollback()
        } catch (ex: Exception) {
            cause.addSuppressed(ex)
        }
    }

    private fun <T> setAside(block: () -> T): T {
        val suspended = contextHolder.currentTransaction
        contextHolder.currentTransaction = null
        try {
            return block()
        } finally {
            contextHolder.currentTransaction = suspended
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(Transactions::class.java)
    }
}
