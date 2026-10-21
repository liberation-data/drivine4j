package org.drivine.postgres

import org.drivine.DrivineException
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import org.opentest4j.TestAbortedException

/**
 * While the Postgres backend is being built (see `docs/postgres-sql-backend-plan.md`), a test that
 * reaches what is not built yet, in the test or in what sets it up, is skipped, saying so, rather than
 * failed: deletes, groups, searches and schema management. A contract's Postgres subclass then runs
 * every test it can, and each skipped one passes as its step lands. Anything else that goes wrong
 * fails as it would.
 *
 * Removed once every step is in.
 */
class PostgresNotYet : TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) = skipped(throwable)

    override fun handleBeforeAllMethodExecutionException(context: ExtensionContext, throwable: Throwable) = skipped(throwable)

    override fun handleBeforeEachMethodExecutionException(context: ExtensionContext, throwable: Throwable) = skipped(throwable)

    /** Skips the test when [throwable] says something is not built yet, and fails it as it would otherwise. */
    private fun skipped(throwable: Throwable): Nothing {
        val notYet = generateSequence(throwable) { it.cause }.firstOrNull(::isNotYet) ?: throw throwable
        throw TestAbortedException("Not yet on Postgres: ${notYet.message}", throwable)
    }

    private fun isNotYet(failure: Throwable): Boolean {
        val message = failure.message.orEmpty()
        return when (failure) {
            is UnsupportedOperationException -> "Postgres" in message && ("does not yet support" in message || "does not delete yet" in message)
            is DrivineException -> "not supported for POSTGRES" in message
            else -> false
        }
    }
}
