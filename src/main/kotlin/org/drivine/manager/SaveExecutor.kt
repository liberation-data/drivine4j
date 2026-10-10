package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.StampWrite
import org.neo4j.driver.exceptions.RetryableException

/**
 * Runs the statements of a save, in order. A checked statement returns no row when the node is not as
 * it was when the object was loaded, and the save stops there with a [StaleObjectException].
 */
internal class SaveExecutor(private val persistenceManager: PersistenceManager) {

    fun execute(statements: List<MergeStatement>) = statements.forEach { execute(it) }

    /** Runs [statement]. Returns the stamp its node is left with, or null when the statement does not say. */
    fun execute(statement: MergeStatement): String? {
        val spec = QuerySpecification.withStatement(statement.statement).bind(statement.bindings)
        val stamp = statement.stamp
        if (stamp?.returned != true) {
            persistenceManager.execute(spec)
            return null
        }
        val left = whenNotContended { persistenceManager.query(spec.transform(String::class.java)).firstOrNull() }
        if (left == null && stamp.expected != null) throw staleObject(stamp, stamp.expected)
        return left
    }

    /**
     * Runs a stamped statement, again if the engine turned it away because another writer was changing
     * the same node at that moment (a conflict or a deadlock, which the driver marks as retryable).
     * The next attempt runs after that writer, and so sees its stamp: a checked save is then refused as
     * stale, not failed. If the attempts run out, or a later one fails some other way, the first
     * failure is thrown.
     */
    private fun <T> whenNotContended(statement: () -> T): T {
        var first: DrivineException? = null
        repeat(CONTENDED_ATTEMPTS) { attempt ->
            try {
                return statement()
            } catch (failure: DrivineException) {
                if (!failure.isRetryable()) throw first?.apply { addSuppressed(failure) } ?: failure
                first = first ?: failure
                Thread.sleep((attempt + 1) * CONTENDED_PAUSE_MILLIS)
            }
        }
        throw checkNotNull(first)
    }

    private fun DrivineException.isRetryable(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is RetryableException }

    private companion object {
        const val CONTENDED_ATTEMPTS = 6
        const val CONTENDED_PAUSE_MILLIS = 15L
        const val MAX_CAUSE_DEPTH = 10
    }

    /** Says why a checked save matched nothing: the node is gone, or carries another stamp. */
    private fun staleObject(stamp: StampWrite, expected: String): StaleObjectException {
        // An absent stamp is read as '' so a node without one still gives a row on every engine.
        val found = persistenceManager.query(
            QuerySpecification
                .withStatement("MATCH (n:${stamp.labels} {${stamp.idProperty}: \$id}) RETURN coalesce(n.${Stamps.QUOTED}, '')")
                .bind(mapOf("id" to stamp.id))
                .transform(String::class.java)
        )
        return StaleObjectException(
            type = stamp.fragmentClass,
            id = stamp.id,
            expectedStamp = expected,
            foundStamp = found.firstOrNull()?.takeIf { it.isNotEmpty() },
            deleted = found.isEmpty(),
        )
    }
}
