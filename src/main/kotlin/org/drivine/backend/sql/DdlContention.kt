package org.drivine.backend.sql

import org.drivine.manager.SaveExecutor
import java.sql.SQLException

/**
 * The block that makes a graph's tables, turned away by a deadlock with another session's, as when sessions make the
 * tables, or write to them, together; and running it again until it is let through (#450).
 *
 * Only a deadlock is run again. A lock that is busy (`ORA-00054`) is waited out inside Oracle's block already, for as
 * long as it allows (see `OracleGraph.BUSY_ATTEMPTS`): running the block again on it would hold a first connection, and
 * an application's start, many times that long.
 *
 * Drivine for TypeScript has the same test (`ddlContended` in `src/manager/Contention.ts`, liberation-data/drivine#454).
 */
internal object DdlContention {

    /**
     * On Oracle: two sessions were each waiting on the other, for a row (`ORA-00060`) or for an object's library cache
     * lock (`ORA-04020`), as DDL over a table that other sessions' writes hold may be.
     */
    val ORACLE = setOf(60, 4020)

    /** On Postgres: a deadlock (`40P01`). */
    val POSTGRES = setOf("40P01")

    /** How many times [whenNotContended] runs a statement before it throws the first failure. */
    const val ATTEMPTS = 6

    /** How far down a failure's causes the store's own error is looked for. */
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * Put among the suppressed of a failure [whenNotContended] gave up on: no retry outside it runs the statement again
     * on it (see [gaveUp]).
     */
    private class GaveUp : RuntimeException("Run as many times as allowed, and turned away each time.", null, false, false)

    /** Whether [whenNotContended] is running a statement on this thread: one it runs within that is run once. */
    private val running = ThreadLocal.withInitial { false }

    /** Whether [failure], or a cause under it, is one [whenNotContended] gave up on, having run it as often as allowed. */
    fun gaveUp(failure: Throwable?): Boolean =
        generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any { cause -> cause.suppressed.any { it is GaveUp } }

    /**
     * Whether [failure], or a cause under it, is a deadlock with another session's (see [ORACLE] and [POSTGRES]), and
     * not one already run as often as allowed ([gaveUp]). Running the statement again may then succeed.
     */
    fun contended(failure: Throwable?): Boolean =
        !gaveUp(failure) && generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is SQLException && (it.errorCode in ORACLE || it.sqlState in POSTGRES)
        }

    /**
     * Runs [statement], again after a pause when the store turned it away as [contended], up to [ATTEMPTS] times in all.
     * The pause grows with each attempt and is part chance, as a contended save's is (see [SaveExecutor.contendedPause]),
     * so two sessions turned away by each other don't run again together. Once attempts run out the first failure is
     * thrown, with the later ones suppressed in it, and marked as given up on ([gaveUp]); a failure of another kind is
     * thrown at once, with any contended one before it suppressed in it.
     *
     * Retries don't multiply. Run within another run of this on the same thread, as the provider's first connection is
     * when an object manager making the tables asks for a connection, [statement] is run once, and the run outside
     * retries; and a failure given up on is not run again by a retry outside.
     */
    fun <T> whenNotContended(
        pause: (attempt: Int) -> Long = { SaveExecutor.contendedPause(it) },
        statement: () -> T,
    ): T {
        if (running.get()) return statement()
        running.set(true)
        try {
            var first: Exception? = null
            repeat(ATTEMPTS) { attempt ->
                try {
                    return statement()
                } catch (failure: Exception) {
                    if (!contended(failure)) throw failure.apply { first?.let(::addSuppressed) }
                    val earlier = first
                    if (earlier == null) first = failure else if (earlier !== failure) earlier.addSuppressed(failure)
                    // No pause after the last attempt: nothing follows it.
                    if (attempt < ATTEMPTS - 1) Thread.sleep(pause(attempt))
                }
            }
            throw checkNotNull(first).apply { addSuppressed(GaveUp()) }
        } finally {
            running.set(false)
        }
    }
}
