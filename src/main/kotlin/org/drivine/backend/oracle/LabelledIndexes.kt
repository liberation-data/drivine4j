package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The labelled indexes of a graph: for each, the label and the properties, in the order the index
 * holds them. A range index makes one beside its index over the property (see [OracleSchemaGrammar]),
 * and a statement that reads the nodes of a label reads them through it where it can (see
 * [OraclePredicates.predicate]).
 */
internal class LabelledIndexes(private val indexes: List<Pair<String, List<String>>>) {

    /** Whether a labelled index on [label] begins with [key], so that a comparison with [key] alone can be answered by it. */
    fun leads(label: String, key: String): Boolean = indexes.any { (l, properties) -> l == label && properties.first() == key }

    /** Whether a labelled index on [label] holds [key] anywhere. */
    fun covers(label: String, key: String): Boolean = indexes.any { (l, properties) -> l == label && key in properties }

    companion object {
        val NONE = LabelledIndexes(emptyList())
    }
}

/**
 * The labelled indexes the catalog of [graph] records, and that are there, read once and kept. They
 * are read again when this process has made or dropped a range index of the graph since, and at least
 * every [maxAge] milliseconds, for one another process made. They are read as the range indexes the
 * catalog lists, which are those whose labelled index is there, on a connection of their own, as the
 * store is made: not in the caller's transaction, and not among the statements of the read.
 *
 * What is kept only decides how a statement is written, never what it means: a statement written for
 * an index that has since gone is read without it, and one written before an index came does not use
 * it. So a catalog that cannot be read is taken to record none.
 */
internal class LabelledIndexCatalog(
    private val persistenceManager: PersistenceManager,
    private val graph: OracleGraph,
    private val maxAge: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Read(val indexes: LabelledIndexes, val at: Long, val changes: Long)

    @Volatile
    private var read: Read? = null

    /** The labelled indexes there are, as last read. */
    fun current(): LabelledIndexes {
        val changes = changes(graph.name)
        val now = clock()
        read?.takeIf { it.changes == changes && now - it.at < maxAge }?.let { return it.indexes }
        return Read(fetch(), now, changes).also { read = it }.indexes
    }

    private fun fetch(): LabelledIndexes = try {
        val listed = OracleSchemaGrammar(graph).parseIndexRows(persistenceManager.indexes.run(query(graph)))
        LabelledIndexes(listed.map { it.label to it.properties })
    } catch (e: Exception) {
        logger.debug("The labelled indexes of {} could not be read, so none is used: {}", graph.name, e.message)
        LabelledIndexes.NONE
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LabelledIndexCatalog::class.java)

        /** The range indexes recorded for [graph] whose labelled index is there: see [OracleSchemaGrammar.listIndexesQuery]. */
        fun query(graph: OracleGraph): String = OracleSchemaGrammar(graph).listIndexesQuery(SchemaItemKind.RANGE_INDEX)

        private val counts = ConcurrentHashMap<String, AtomicLong>()

        /** How many times this process has made or dropped a range index of the graph [name]. */
        fun changes(name: String): Long = counts[name.lowercase()]?.get() ?: 0

        /** Says a range index of the graph [name] is being made or dropped, so what is kept of its catalog is read again. */
        fun changed(name: String) {
            counts.computeIfAbsent(name.lowercase()) { AtomicLong() }.incrementAndGet()
        }
    }
}
