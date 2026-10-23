package org.drivine.transaction

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
    /**
     * Where the transaction in progress is kept, for the thread. `drivine4j-coroutines` reads it to
     * carry a coroutine's transaction to each thread the coroutine runs on.
     */
    val contextHolder: TransactionContextHolder,
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
     * - [Propagation.NESTED] is refused, with a transaction or without one: no engine Drivine speaks
     *   to has savepoints it uses. Spring's `DrivineTransactionManager` refuses it in a transaction,
     *   and without one Spring runs it as `REQUIRED`.
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
    ): T = propagate(propagation, rollback, contextHolder.currentTransaction) { transaction ->
        runIn(transaction) { block.call() }
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

    /**
     * What [transaction] does, for code that keeps the transaction in progress somewhere other than
     * the thread: `drivine4j-coroutines` keeps it in a coroutine's context. Given the transaction in
     * progress, [current], it works out the transaction a block runs in as [propagation] says, and
     * calls [run] with it: [current] to join it, a transaction it begins (and ends when [run]
     * returns or throws), or null to run without one. [run] puts that transaction in place for the
     * block. It is inline, so [run] may suspend.
     *
     * @throws IllegalTransactionStateException where [propagation] refuses to run, or where
     *   [current] is a transaction this code may not use (see [DrivineTransactionObject.refusal]).
     */
    inline fun <T> propagate(
        propagation: Propagation,
        rollback: Boolean,
        current: DrivineTransactionObject?,
        run: (DrivineTransactionObject?) -> T,
    ): T = when (propagation) {
        Propagation.REQUIRED -> if (current != null) {
            current.runJoined(rollback) { run(current) }
        } else {
            DrivineTransactionObject().runToEnd(rollback) { run(it) }
        }
        Propagation.REQUIRES_NEW -> DrivineTransactionObject().runToEnd(rollback) { run(it) }
        Propagation.SUPPORTS -> if (current != null) current.runJoined(rollback) { run(current) } else run(null)
        Propagation.MANDATORY -> {
            val joined = current ?: throw IllegalTransactionStateException(
                "Propagation MANDATORY needs a transaction, and there is none."
            )
            joined.runJoined(rollback) { run(joined) }
        }
        Propagation.NEVER -> if (current != null) {
            throw IllegalTransactionStateException(
                "Propagation NEVER refuses to run in a transaction, and transaction ${current.id} is running."
            )
        } else {
            run(null)
        }
        Propagation.NOT_SUPPORTED -> run(null)
        Propagation.NESTED -> throw IllegalTransactionStateException(
            "Propagation NESTED is not supported: Drivine has no savepoints. Use REQUIRED or REQUIRES_NEW."
        )
    }

    /** Makes [transaction] (or none) the thread's while [block] runs, and puts back what was there. */
    private fun <T> runIn(transaction: DrivineTransactionObject?, block: () -> T): T {
        val previous = contextHolder.currentTransaction
        contextHolder.currentTransaction = transaction
        try {
            return block()
        } finally {
            contextHolder.currentTransaction = previous
        }
    }
}
