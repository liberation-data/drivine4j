package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.neo4j.driver.exceptions.RetryableException

/** Runs the statements of a save. */
internal class SaveExecutor(private val persistenceManager: PersistenceManager) {

    /** Runs [statements] in order, each on its own. */
    fun execute(statements: List<MergeStatement>) = statements.forEach {
        persistenceManager.execute(QuerySpecification.withStatement(it.statement).bind(it.bindings))
    }

    /**
     * Runs a whole save. Returns the stamps to hand back: the root's, then that of each of the statement's
     * stamped targets, in their order. Throws [StaleObjectException] when the root is not as it was when the object was
     * loaded; the statement has then written nothing.
     */
    fun save(statement: SaveStatement): List<String> = stamps(statement, whenNotContended { persistenceManager.query(spec(statement)) })

    fun spec(statement: SaveStatement): QuerySpecification<String> =
        QuerySpecification.withStatement(statement.statement).bind(statement.bindings).transform(String::class.java)

    /** The stamps in the [rows] a save statement returned. */
    fun stamps(statement: SaveStatement, rows: List<Any?>): List<String> {
        val row = rows.firstOrNull() as? String
        if (row != null) {
            val returned = row.split(',')
            // Each related node's is keyed `index/found`: found is the relationship token it had before the save.
            val byIndex = returned.drop(1).associate { entry ->
                val (key, stamp) = entry.split('=', limit = 2)
                key.substringBefore('/').toInt() to (key.substringAfter('/') to stamp)
            }
            return listOf(returned.first()) + statement.stamped.indices.map { index ->
                val (found, stamp) = byIndex.getValue(index)
                // A node whose relationships changed since its object was loaded keeps the object's token:
                // the stamp handed back does not vouch for relationships the object never held.
                val carried = statement.carriedLinks[index]
                if (carried == null || carried == found) stamp else "${Stamps.nodeToken(stamp)}:$carried"
            }
        }
        throw statement.root.expected?.let { staleObject(statement.root, it) }
            ?: IllegalStateException("The save of ${statement.root.fragmentClass.simpleName} '${statement.root.id}' returned nothing.")
    }

    /**
     * Runs a save, again if the engine turned it away because another writer was changing the same
     * node at that moment (a conflict or a deadlock, which the Neo4j driver, used for Neo4j and
     * Memgraph, marks as retryable). The next attempt runs after that writer, and so sees its stamp: a
     * checked save is then refused as stale, not failed. If the attempts run out, or a later one fails
     * some other way, the first failure is thrown. FalkorDB runs one write at a time and never turns
     * one away.
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
