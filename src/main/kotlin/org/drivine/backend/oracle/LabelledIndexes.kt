package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.schema.CatalogReads
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
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
internal class LabelledIndex(val label: String, val properties: List<String>, val unique: Boolean = false)

/** The [LabelledIndex]es of a graph. */
internal class LabelledIndexes(private val indexes: List<LabelledIndex>) {

    /** Each label and the property an index on it begins with: what the module's compiler reads an equality through. */
    val leading: Set<Pair<String, String>> get() = indexes.map { it.label to it.properties.first() }.toSet()

    companion object {
        val NONE = LabelledIndexes(emptyList())
    }
}

/**
 * The labelled indexes the catalog of [graph] records, and that are there, as last read. They are
 * read again once this process has made or dropped an index or a constraint of the graph since, and
 * at least every [maxAge] milliseconds, for one another process made.
 *
 * A read never waits for them. They are read on [refresh], by default a thread of its own (see
 * [CatalogReads]), on a schema connection of their own. One read at a time: meanwhile,
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
    private val refresh: Executor = CatalogReads.BACKGROUND,
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
        read(persistenceManager.indexes.run(query(graph))).also { succeeded = true }
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

        /** The labelled indexes recorded for [graph] that are there, as the module lists them: see [org.drivine.cypher.store.ddl.OracleDdl.labelledIndexesQuery]. */
        fun query(graph: OracleGraph): String = graph.store.labelledIndexesQuery()

        /** The labelled indexes the rows of [query] list, as a connection's query gives them, a map each. */
        fun read(rows: List<Any?>): LabelledIndexes = LabelledIndexes(rows.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            LabelledIndex(map["label"] as String, (map["properties"] as List<*>).map { it.toString() }, unique = map["kind"] == SchemaItemKind.UNIQUENESS_CONSTRAINT.name)
        })

        private val counts = ConcurrentHashMap<String, AtomicLong>()

        /** How many times this process has made or dropped an index or a constraint of the graph [name]. */
        fun changes(name: String): Long = counts[name.lowercase()]?.get() ?: 0

        /** Says an index or a constraint of the graph [name] has been made or dropped, so what is kept of its catalog is read again. */
        fun changed(name: String) {
            counts.computeIfAbsent(name.lowercase()) { AtomicLong() }.incrementAndGet()
        }
    }
}
