package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An index over properties of the nodes of [label] alone, null for every other node, which a filter
 * on the label's nodes can be read through (see [OraclePredicates.predicate]): the labelled index a
 * range index makes, or the index of a uniqueness constraint over one property, [unique]. See
 * [OracleSchemaGrammar].
 *
 * @param properties in the order the index holds them
 */
internal class LabelledIndex(val label: String, val properties: List<String>, val unique: Boolean = false) {

    /** The property [key] of the node [alias], as this index reads it. */
    fun expression(alias: String, key: String): String =
        if (unique) OracleSql.unique(alias, label, properties, key) else OracleSql.labelled(alias, label, key)
}

/** The [LabelledIndex]es of a graph. */
internal class LabelledIndexes(indexes: List<LabelledIndex>) {

    /** A uniqueness constraint's index first: where both are there, it is the one Oracle answers from by a unique scan. */
    private val indexes = indexes.sortedByDescending { it.unique }

    /** Whether an index on [label] begins with [key], so that a comparison with [key] alone can be answered by it. */
    fun leads(label: String, key: String): Boolean = indexes.any { it.label == label && it.properties.first() == key }

    /**
     * The property [key] of the node [alias] as an index on [label] that holds it reads it, one that
     * begins with [key] before one that holds it further in; null when none does. Whichever it is,
     * it is null on a node without [label].
     */
    fun expression(alias: String, label: String, key: String): String? =
        indexes.filter { it.label == label && key in it.properties }
            .minByOrNull { if (it.properties.first() == key) 0 else 1 }
            ?.expression(alias, key)

    companion object {
        val NONE = LabelledIndexes(emptyList())
    }
}

/**
 * The labelled indexes the catalog of [graph] records, and that are there, as last read. They are
 * read again once this process has made or dropped an index or a constraint of the graph since, and
 * at least every [maxAge] milliseconds, for one another process made.
 *
 * A read never waits for them. They are read on [refresh], by default a thread of its own, on a
 * schema connection of their own: not in the caller's transaction, not among the statements of the
 * read, and not by a reader that may hold the pool's last connection. One read at a time: meanwhile,
 * and until the first is done, statements are written with what was read last, or with none.
 *
 * What is kept only decides how a statement is written, never what it means: a statement written for
 * an index that has since gone is read without it, and one written before an index came does not use
 * it. So a catalog that cannot be read leaves what was read before.
 */
internal class LabelledIndexCatalog(
    private val persistenceManager: PersistenceManager,
    private val graph: OracleGraph,
    private val maxAge: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refresh: Executor = BACKGROUND,
) {
    private class Read(val indexes: LabelledIndexes, val at: Long, val changes: Long)

    @Volatile
    private var read: Read? = null

    /** Whether a read of the catalog has ever succeeded. */
    @Volatile
    private var succeeded = false

    private val reading = AtomicBoolean(false)

    /** The labelled indexes there are, as last read; a read again is begun when that is old. */
    fun current(): LabelledIndexes {
        val last = read
        if (last == null || last.changes != changes(graph.name) || clock() - last.at >= maxAge) readAgain()
        return read?.indexes ?: LabelledIndexes.NONE
    }

    private fun readAgain() {
        if (!reading.compareAndSet(false, true)) return
        try {
            refresh.execute {
                try {
                    // Taken before the read: a change made while it runs is read again next time.
                    val changes = changes(graph.name)
                    val at = clock()
                    read = Read(fetch() ?: read?.indexes ?: LabelledIndexes.NONE, at, changes)
                } finally {
                    reading.set(false)
                }
            }
        } catch (e: Throwable) {
            // Not begun, whatever stopped it: a thread that could not be made is an Error, not a rejection.
            reading.set(false)
            if (e !is RejectedExecutionException) throw e
        }
    }

    private fun fetch(): LabelledIndexes? = try {
        LabelledIndexes(persistenceManager.indexes.run(query(graph)).mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            LabelledIndex(map["label"] as String, (map["properties"] as List<*>).map { it.toString() }, unique = map["kind"] == SchemaItemKind.UNIQUENESS_CONSTRAINT.name)
        }).also { succeeded = true }
    } catch (e: Exception) {
        // Loud until a read has succeeded: until then no filter is read through an index.
        if (!succeeded) {
            logger.warn("The labelled indexes of {} could not be read, so filters are written without them until they can be: {}", graph.name, e.message)
        } else {
            logger.debug("The labelled indexes of {} could not be read again, so what was read before is kept: {}", graph.name, e.message)
        }
        null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LabelledIndexCatalog::class.java)

        private val threads = AtomicLong()

        /**
         * Threads that read catalogs, made as needed and ended when idle; none keeps the process alive,
         * and none holds the class loader of the code that happened to start it, which a redeployed
         * application would otherwise keep until the thread ends.
         */
        private val BACKGROUND: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "drivine-oracle-catalog-${threads.incrementAndGet()}").apply {
                isDaemon = true
                contextClassLoader = null
            }
        }

        /** The labelled indexes recorded for [graph] that are there: see [OracleSchemaGrammar.labelledIndexesQuery]. */
        fun query(graph: OracleGraph): String = OracleSchemaGrammar(graph).labelledIndexesQuery()

        private val counts = ConcurrentHashMap<String, AtomicLong>()

        /** How many times this process has made or dropped an index or a constraint of the graph [name]. */
        fun changes(name: String): Long = counts[name.lowercase()]?.get() ?: 0

        /** Says an index or a constraint of the graph [name] has been made or dropped, so what is kept of its catalog is read again. */
        fun changed(name: String) {
            counts.computeIfAbsent(name.lowercase()) { AtomicLong() }.incrementAndGet()
        }
    }
}
