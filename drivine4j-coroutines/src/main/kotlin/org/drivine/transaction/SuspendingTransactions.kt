package org.drivine.transaction

import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.drivine.Drivine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block], a suspend block, in a transaction as [propagation] says, and returns what it returns:
 * [Transactions.transaction] for coroutines. The transaction follows the coroutine: it is kept in the
 * coroutine's context, and put on whichever thread the coroutine resumes on, so a block can suspend,
 * or switch dispatcher with `withContext`, and still be in it. Another coroutine that runs on a thread
 * the block left finds no transaction there.
 *
 * ```kotlin
 * suspend fun onboard(person: Person, hire: Hire) = withContext(Dispatchers.IO) {
 *     transactions.transactionSuspending {
 *         gom.save(person)
 *         notifyPayroll(hire)    // suspends, and resumes on any thread, still in the transaction
 *         gom.save(hire)
 *     } // both commit here; a throw out of the block rolls both back
 * }
 * ```
 *
 * The propagations, [rollback], and when the transaction commits or rolls back, are as for
 * [Transactions.transaction]. Drivine's calls in the block are blocking calls, as they are anywhere,
 * and so are the commit and the rollback, which run in the caller's context once the block ends: call
 * it on a dispatcher for blocking work, such as `Dispatchers.IO`.
 *
 * - **Nesting.** A suspend block, a [Transactions.transaction] block, a `@Transactional` method or a
 *   `TransactionTemplate` run in the block joins its transaction, as `REQUIRED` says. A suspend block
 *   run on a thread already in a transaction, one Spring or a blocking block began before calling
 *   `runBlocking`, joins that one.
 * - **Coroutines launched in the block** (`launch`, `async`, and what is built on them, such as
 *   `flowOn`) don't share its transaction: two coroutines using one transaction's connection at once
 *   isn't safe on every driver. A statement such a coroutine runs, or a block it runs that would join
 *   the transaction, is refused with [IllegalTransactionStateException]. Run that work in the block
 *   itself, one step after another, or give each coroutine a transaction of its own with
 *   `transactionSuspending(Propagation.REQUIRES_NEW) { }`. A coroutine launched in the block that never
 *   reaches Drivine runs as it would anywhere. A coroutine handed the block's whole context,
 *   `launch(coroutineContext) { }`, is the exception: kotlinx merges the element as `withContext` does,
 *   and nothing tells the two apart, so that coroutine shares the transaction.
 * - Two coroutines that both find the thread's transaction, launched by `runBlocking` in a
 *   `@Transactional` method say, can't both run a suspend block in it at once: the second is refused.
 *
 * @throws IllegalTransactionStateException where [propagation] refuses to run, or in a coroutine
 *   launched in a transaction block, where the block would join that transaction.
 */
suspend fun <T> Transactions.transactionSuspending(
    propagation: Propagation = Propagation.REQUIRED,
    rollback: Boolean = false,
    block: suspend CoroutineScope.() -> T,
): T {
    val holder = contextHolder
    val carried = currentCoroutineContext()[TransactionElement.Key(holder)]
    // A coroutine no suspend block has put in a transaction is in the thread's, if it is in any.
    val current = if (carried != null) carried.transaction else holder.currentTransaction
    val joins = propagation == Propagation.REQUIRED || propagation == Propagation.SUPPORTS || propagation == Propagation.MANDATORY
    // A block run through runBlocking by a coroutine that carries the thread's transaction is nested in
    // that coroutine, which waits for it, so it joins as a nested block does, with no claim of its own.
    val claimed = if (carried == null && current != null && joins && !TransactionElement.carriedOnThread(current)) {
        ThreadTransactionUsers.claim(current)
    } else {
        null
    }
    try {
        return propagate(propagation, rollback, current) { transaction ->
            withContext(TransactionElement(holder, transaction), block)
        }
    } finally {
        claimed?.let { ThreadTransactionUsers.release(current!!, it) }
    }
}

/** Runs [block], a suspend block, in a transaction: see [Transactions.transactionSuspending]. */
suspend fun <T> Drivine.transactionSuspending(
    propagation: Propagation = Propagation.REQUIRED,
    rollback: Boolean = false,
    block: suspend CoroutineScope.() -> T,
): T = transactions.transactionSuspending(propagation, rollback, block)

/**
 * Carries [transaction] in a coroutine's context, for [holder]: it is made the thread's transaction
 * each time the coroutine runs on a thread, and what the thread held is put back each time the
 * coroutine suspends. A null [transaction] runs the coroutine without one.
 *
 * A coroutine launched under it gets a stand-in that refuses to be used, not the transaction: see
 * [copyForChild].
 */
internal class TransactionElement(
    private val holder: TransactionContextHolder,
    val transaction: DrivineTransactionObject?,
    /** False for an element made to be put in a context with `withContext`, and true once it is there. */
    private val inContext: Boolean = false,
) : CopyableThreadContextElement<DrivineTransactionObject?> {

    override val key: CoroutineContext.Key<*> = Key(holder)

    override fun updateThreadContext(context: CoroutineContext): DrivineTransactionObject? {
        val previous = holder.currentTransaction
        holder.currentTransaction = transaction
        carried.set(Carried(transaction, carried.get()))
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: DrivineTransactionObject?) {
        holder.currentTransaction = oldState
        carried.set(carried.get()?.below)
    }

    /**
     * kotlinx calls this for each coroutine launched in a context that holds this element (`launch`,
     * `async`), and for an element `withContext` puts in a context where none was. The first gets a
     * stand-in for the transaction, which refuses to be used; the second is the block's own, and keeps
     * it. `withContext` without this element, to switch dispatcher say, keeps the element as it is, so
     * the block's own code is in the transaction on any thread.
     */
    override fun copyForChild(): CopyableThreadContextElement<DrivineTransactionObject?> = when {
        !inContext -> TransactionElement(holder, transaction, inContext = true)
        transaction == null || transaction.refusal != null -> this
        else -> TransactionElement(holder, DrivineTransactionObject(refusal = launchedInBlock(transaction)), inContext = true)
    }

    /** A block sets its own transaction, or none, over the one its coroutine was in. */
    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext {
        val overwriting = overwritingElement as TransactionElement
        return if (overwriting.inContext) overwriting else TransactionElement(holder, overwriting.transaction, inContext = true)
    }

    /** One key for each [TransactionContextHolder], so that two [Drivine]s' transactions are carried apart. */
    data class Key(val holder: TransactionContextHolder) : CoroutineContext.Key<TransactionElement>

    /** A transaction an element has put on the thread, over those put there before it and not yet taken off. */
    private class Carried(val transaction: DrivineTransactionObject?, val below: Carried?)

    companion object {
        private val carried = ThreadLocal<Carried?>()

        /** Whether [transaction] is on the thread because a coroutine running here carries it, not because the thread began it. */
        fun carriedOnThread(transaction: DrivineTransactionObject): Boolean =
            generateSequence(carried.get()) { it.below }.any { it.transaction === transaction }
    }

    private fun launchedInBlock(transaction: DrivineTransactionObject) =
        "A coroutine launched inside transaction ${transaction.id}'s block can't use the transaction: " +
            "coroutines launched in a transaction block (launch, async) don't share it, since two of them " +
            "using one transaction's connection at once isn't safe on every driver. Run the work in the block " +
            "itself, one step after another, or give the coroutine a transaction of its own with " +
            "transactionSuspending(Propagation.REQUIRES_NEW) { }."
}

/**
 * The coroutine running a suspend block in each transaction that it found on the thread, not in its
 * context: so that two such coroutines, launched by `runBlocking` in a `@Transactional` method say,
 * never use the transaction at once.
 */
private object ThreadTransactionUsers {
    private val users = ConcurrentHashMap<DrivineTransactionObject, Job>()

    /** Claims [transaction] for the calling coroutine, and returns its job, or null where it needs no claim. */
    suspend fun claim(transaction: DrivineTransactionObject): Job? {
        val job = currentCoroutineContext()[Job] ?: return null
        val user = users.putIfAbsent(transaction, job)
        if (user != null && user !== job) {
            throw IllegalTransactionStateException(
                "Transaction ${transaction.id}, begun on the thread, is in use by another coroutine's block. " +
                    "Two coroutines using one transaction's connection at once isn't safe on every driver. Run " +
                    "the blocks one after another, or give each a transaction of its own with " +
                    "transactionSuspending(Propagation.REQUIRES_NEW) { }."
            )
        }
        return if (user == null) job else null
    }

    fun release(transaction: DrivineTransactionObject, job: Job) {
        users.remove(transaction, job)
    }
}
