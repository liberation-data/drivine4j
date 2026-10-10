package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.mapper.ResultPostProcessor
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.neo4j.driver.exceptions.TransientException

/** Runs the statements of a save. */
internal class SaveExecutor(private val persistenceManager: PersistenceManager) {

    /** Runs [statements] in order, each on its own. */
    fun execute(statements: List<MergeStatement>) = statements.forEach {
        persistenceManager.execute(QuerySpecification.withStatement(it.statement).bind(it.bindings))
    }

    /**
     * Runs a whole save. Returns the stamps to hand back: the root's, then that of each of the statement's
     * stamped targets, in their order. Throws [StaleObjectException] when the root is not as it was when the object was
     * loaded; the statement has then written nothing. That is so too when the engine turns a checked
     * save away each time it is run: inside a transaction that read the node before another writer
     * changed it, Memgraph refuses the write itself.
     */
    fun save(statement: SaveStatement): List<String> {
        val rows = try {
            whenNotContended { persistenceManager.query(spec(statement)) }
        } catch (failure: DrivineException) {
            // Turned away every time: another writer changed the node, and the engine will not let this
            // save write over it. For a checked save that is a stale object, and it wrote nothing.
            val root = statement.root
            val expected = root.expected
            if (expected == null || !failure.isRetryable()) throw failure
            throw StaleObjectException(root.fragmentClass, root.id, expected, foundStamp = null, deleted = false, cause = failure)
        }
        return stamps(statement, rows)
    }

    /**
     * Runs [specs] as one batch, again if the engine turned it away as it does a contended [save]. A
     * batch that fails is rolled back, so a second run starts from what the first found.
     */
    fun batch(specs: List<QuerySpecification<*>>): List<List<Any?>> = try {
        whenNotContended { persistenceManager.queryBatch(specs) }
    } catch (failure: DrivineException) {
        // A checked statement of the batch found its root stale. The batch is rolled back by now, so
        // the node can be read to say what became of it.
        val refused = generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<RefusedInBatch>().firstOrNull()
            ?: throw failure
        throw staleObject(refused.root, requireNotNull(refused.root.expected))
    }

    /**
     * The specification that runs [statement]. As one of a [batch], a checked statement that finds
     * its root stale fails the batch there and then, so the statements before it are rolled back
     * with it, on an engine that has transactions.
     */
    fun spec(statement: SaveStatement, inBatch: Boolean = false): QuerySpecification<String> {
        val spec = QuerySpecification.withStatement(statement.statement).bind(statement.bindings).transform(String::class.java)
        if (!inBatch || statement.root.expected == null) return spec
        return spec.addPostProcessors(object : ResultPostProcessor<Any, Any> {
            override fun apply(results: List<Any>): List<Any> = results.ifEmpty { throw RefusedInBatch(statement.root) }
        })
    }

    /** A checked statement of a batch gave no row: its root is not as the object's stamp says. */
    private class RefusedInBatch(val root: StampWrite) : RuntimeException()

    /** The stamps in the [rows] a save statement returned. */
    fun stamps(statement: SaveStatement, rows: List<Any?>): List<String> {
        val row = rows.firstOrNull() as? String
        if (row != null) {
            val returned = row.split(',')
            // Each related node's is keyed `index/found`: found is the stamp it had before the save.
            val byIndex = returned.drop(1).associate { entry ->
                val (key, stamp) = entry.split('=', limit = 2)
                key.substringBefore('/').toInt() to (key.substringAfter('/') to stamp)
            }
            return listOf(returned.first()) + statement.stamped.indices.map { index ->
                val (found, stamp) = byIndex.getValue(index)
                // A node that changed since its object was loaded keeps the object's token: the stamp
                // handed back does not vouch for data or relationships the object never held.
                Stamps.handedBack(statement.carried[index], found, stamp)
            }
        }
        throw statement.root.expected?.let { staleObject(statement.root, it) }
            ?: IllegalStateException("The save of ${statement.root.fragmentClass.simpleName} '${statement.root.id}' returned nothing.")
    }

    /**
     * Runs a save, or any one statement that writes, again if the engine turned it away because another writer was changing the same
     * node at that moment (a conflict or a deadlock, which the Neo4j driver, used for Neo4j and
     * Memgraph, reports as transient). The next attempt runs after that writer, and so sees its stamp: a
     * checked save is then refused as stale, not failed. If the attempts run out, or a later one fails
     * some other way, the first failure is thrown. FalkorDB runs one write at a time and never turns
     * one away.
     *
     * A lost connection is not run again, though the driver calls it retryable: the save may have
     * been applied before the connection went, and a second run would find its own stamp and report
     * a save that was applied as refused.
     */
    fun <T> whenNotContended(statement: () -> T): T {
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
        generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is TransientException }

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
