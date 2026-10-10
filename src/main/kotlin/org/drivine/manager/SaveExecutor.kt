package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.mapper.ResultPostProcessor
import org.drivine.model.Stamps
import org.drivine.query.MergeStatement
import org.drivine.query.NodeKey
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
     * stamped targets, in their order, then that of each of its repeats. Throws [StaleObjectException]
     * when the root is not as it was when the object was loaded, and [RootGone] when the statement
     * does not make its root and the root is not there; the statement has then written nothing. That
     * is so too when the engine turns a checked save away each time it is run: inside a transaction
     * that read the node before another writer changed it, Memgraph refuses the write itself.
     */
    fun save(statement: SaveStatement): List<String> {
        val rows = try {
            whenNotContended { persistenceManager.query(spec(statement)) }
        } catch (failure: DrivineException) {
            // Turned away every time: another writer was changing a node this save writes, and the
            // engine will not let it write over that. A checked save reports it as a stale object: it
            // wrote nothing, and the answer is the same, to load again and re-apply.
            val root = statement.root
            val expected = root.expected
            if (expected == null || !failure.isRetryable()) throw failure
            throw StaleObjectException(root.fragmentClass, root.id, expected, foundStamp = null, deleted = false, cause = failure)
        }
        return stamps(statement, rows)
    }

    /**
     * Runs [specs] as one batch, again if the engine turned it away as it does a contended [save]. A
     * batch that fails outside a caller's transaction is rolled back, on an engine that has
     * transactions, so a second run starts from what the first found. Inside a caller's transaction
     * the batch is part of it: what ran before a failure is undone when the caller's transaction is.
     *
     * @param offered the stamps the batch's statements offer the nodes they change. A root that is
     *   refused carrying a token of one was changed by an earlier statement of the batch, and so is
     *   one refused though it is as its object's stamp says once the batch is rolled back
     */
    fun batch(specs: List<QuerySpecification<*>>, offered: Collection<String> = emptyList()): List<List<Any?>> = try {
        whenNotContended { persistenceManager.queryBatch(specs) }
    } catch (failure: DrivineException) {
        // A checked statement of the batch found its root stale. The node is read to say what became
        // of it: as the batch left it, or as it was before the batch where the batch was rolled back.
        val refused = failure.refusal() ?: throw failure
        val expected = requireNotNull(refused.root.expected)
        val stale = staleObject(refused.root, expected)
        val found = stale.foundStamp
        val nodeTokens = offered.map { Stamps.nodeToken(it) }
        val linksTokens = offered.mapNotNull { Stamps.linksToken(it) }
        val ownDoing = found != null && (found == expected || Stamps.nodeToken(found) in nodeTokens || Stamps.linksToken(found) in linksTokens)
        throw if (ownDoing) StaleObjectException(stale.type, stale.id, expected, found, deleted = false, bySameBatch = true) else stale
    }

    /** The refusal among the causes of this failure; null when it failed some other way. */
    private fun DrivineException.refusal(): RefusedInBatch? =
        generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<RefusedInBatch>().firstOrNull()

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

    /** A statement that does not make its root found the root gone. It wrote nothing. */
    class RootGone(val root: StampWrite) : RuntimeException("${root.fragmentClass.simpleName} '${root.id}' is not there.")

    /** The stamps in the [rows] a save statement returned: the root's, each stamped target's, each repeat's. */
    fun stamps(statement: SaveStatement, rows: List<Any?>): List<String> {
        val row = rows.firstOrNull() as? String
        if (row != null) {
            val returned = row.split(',')
            val rootStamp = returned.first()
            // Each related node's is keyed `index/found`: found is the stamp its part found it with.
            val byIndex = returned.drop(1).associate { entry ->
                val (key, stamp) = entry.split('=', limit = 2)
                key.substringBefore('/').toInt() to (key.substringAfter('/') to stamp)
            }
            // A node the object holds more than once is written by more than one part, and a later
            // part finds what an earlier one wrote. What the node carried before the statement is
            // what the first of them found.
            val rootKey = statement.rootKey
            val first = mutableListOf<Triple<NodeKey, String, String>>()
            fun handedBack(key: NodeKey?, carried: String?, found: String?, stamp: String?): String? {
                if (key == null) return Stamps.handedBack(carried, found ?: return null, stamp ?: return null)
                if (key.sameNodeAs(rootKey)) {
                    // The root's own part found it first, and its stamp is handed back as that part
                    // found it. A fragment that carried another stamp than the root keeps its own:
                    // nothing here says what the node carried before the statement.
                    return if (carried == statement.root.carried) rootStamp else carried ?: Stamps.withoutLinks(rootStamp)
                }
                val seen = first.firstOrNull { it.first.sameNodeAs(key) }
                    ?: Triple(key, found ?: return null, stamp ?: return null).also { first.add(it) }
                return Stamps.handedBack(carried, seen.second, stamp ?: seen.third)
            }
            val targets = statement.stamped.indices.map { index ->
                val (found, stamp) = byIndex.getValue(index)
                // A node that changed since its object was loaded keeps the object's token: the stamp
                // handed back does not vouch for data or relationships the object never held.
                checkNotNull(handedBack(statement.keys[index], statement.carried[index], found, stamp))
            }
            // A repeat was not written: it is handed what the one written for its node is, if it carried the same.
            val repeats = statement.repeats.map { repeat ->
                handedBack(repeat.key, repeat.carried, null, null) ?: repeat.carried ?: ""
            }
            return listOf(rootStamp) + targets + repeats
        }
        throw statement.root.expected?.let { staleObject(statement.root, it) }
            ?: if (statement.createsRoot) {
                IllegalStateException("The save of ${statement.root.fragmentClass.simpleName} '${statement.root.id}' returned nothing.")
            } else {
                RootGone(statement.root)
            }
    }

    /**
     * Runs a save, or any one statement that writes, again if the engine turned it away because another writer was changing the same
     * node at that moment (a conflict or a deadlock, which the Neo4j driver, used for Neo4j and
     * Memgraph, reports as transient). The next attempt runs after that writer, and so sees its stamp: a
     * checked save is then refused as stale, not failed: a later attempt of a batch that is refused
     * so is thrown as that refusal. If the attempts run out, or a later one fails some other way, the
     * first failure is thrown. FalkorDB runs one write at a time and never turns one away.
     *
     * Inside a caller's transaction the engine has usually failed the transaction with the statement,
     * so the later attempts fail too and the first failure is what the caller sees.
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
                // A refusal is the answer, whatever was turned away before it.
                if (failure.refusal() != null) throw failure.apply { first?.let(::addSuppressed) }
                if (!failure.isRetryable()) throw first?.apply { addSuppressed(failure) } ?: failure
                first = first ?: failure
                // No pause after the last attempt: nothing follows it.
                if (attempt < CONTENDED_ATTEMPTS - 1) Thread.sleep((attempt + 1) * CONTENDED_PAUSE_MILLIS)
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
