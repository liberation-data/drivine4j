package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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

    /** The property [key] of the node [alias] as an index on [label] that holds it reads it; null when none does. */
    fun expression(alias: String, label: String, key: String): String? =
        indexes.firstOrNull { it.label == label && key in it.properties }?.expression(alias, key)

    companion object {
        val NONE = LabelledIndexes(emptyList())
    }
}

/**
 * The labelled indexes the catalog of [graph] records, and that are there, read once and kept. They
 * are read again when this process has made or dropped an index or a constraint of the graph since,
 * and at least every [maxAge] milliseconds, for one another process made. They are read on a
 * connection of their own, as the store is made: not in the caller's transaction, and not among the
 * statements of the read.
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
        // Taken before the read: a change made while it runs is read again next time.
        val changes = changes(graph.name)
        val now = clock()
        read?.takeIf { it.changes == changes && now - it.at < maxAge }?.let { return it.indexes }
        return Read(fetch(), now, changes).also { read = it }.indexes
    }

    private fun fetch(): LabelledIndexes = try {
        LabelledIndexes(persistenceManager.indexes.run(query(graph)).mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            LabelledIndex(map["label"] as String, (map["properties"] as List<*>).map { it.toString() }, unique = map["kind"] == SchemaItemKind.UNIQUENESS_CONSTRAINT.name)
        })
    } catch (e: Exception) {
        logger.debug("The labelled indexes of {} could not be read, so none is used: {}", graph.name, e.message)
        LabelledIndexes.NONE
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LabelledIndexCatalog::class.java)

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
