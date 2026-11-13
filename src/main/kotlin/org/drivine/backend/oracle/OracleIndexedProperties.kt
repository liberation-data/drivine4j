package org.drivine.backend.oracle

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The leading property of each labelled index a graph's store has, by label, as a Cypher statement run through a connection
 * is compiled with: the compiler writes a key equality on such a property as the index's expression, so Oracle reads the
 * node through the index. Compiled without them, a keyed `MATCH` read every node of the label, for each row of an `UNWIND`:
 * a batch of 20 relationships as `apoc.export.cypher` writes it took 137 s, and 0.16 s with them (drivine4j#805, #675).
 *
 * Read from the graph's catalog, as [LabelledIndexCatalog] reads it for a filter through the query DSL, on the connection the
 * statement runs on: a connection has no persistence manager to hold a catalog. Kept for the database, its user and the
 * graph, and read again when [MAX_AGE] has passed or an index of the graph has been made or dropped in this JVM
 * ([LabelledIndexCatalog.changed]). What is kept only decides how a statement is written, never what it means: a statement
 * written for an index that has since gone is read without it. A catalog that can't be read leaves what was read before, or
 * none.
 */
internal object OracleIndexedProperties {

    private val logger = LoggerFactory.getLogger(OracleIndexedProperties::class.java)

    private class Read(val leading: Set<Pair<String, String>>, val at: Long, val changes: Long)

    /** What was last read for each database, user and graph. */
    private val reads = ConcurrentHashMap<String, Read>()

    /** The database, user and graph each said once to have a catalog that could not be read. */
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How long what was read is kept, as [LabelledIndexCatalog] keeps it. */
    val MAX_AGE: Duration = Duration.ofSeconds(60)

    /** Nanoseconds, as [System.nanoTime] counts them: replaced by a test that lets [MAX_AGE] pass. */
    internal var clock: () -> Long = System::nanoTime

    /**
     * The labelled indexes' leading properties of [graph], by label, read with [rows], which runs a statement on
     * [connection] and gives its rows as the connection's query maps them. A query that fails rolls back only itself, so a
     * transaction open on [connection] is left as it was.
     */
    fun leading(connection: Connection, graph: String, rows: (String) -> List<Any?>): Set<Pair<String, String>> {
        // A connection whose metadata can't be read, or a stand-in that has none, names no database or user to keep what is read
        // for: its statement is compiled with no index, as one that can't read the catalog is, and nothing is read or kept.
        val key = runCatching { connection.metaData?.let { "${it.url}\u0000${it.userName}\u0000${graph.lowercase()}" } }.getOrNull()
            ?: return emptySet()
        val last = reads[key]
        val changes = LabelledIndexCatalog.changes(graph)
        val now = clock()
        if (last != null && last.changes == changes && now - last.at < MAX_AGE.toNanos()) return last.leading
        val leading = try {
            LabelledIndexCatalog.read(rows(LabelledIndexCatalog.query(OracleGraph(graph)))).leading
        } catch (e: Exception) {
            if (warned.add(key)) {
                logger.warn("The labelled indexes of {} could not be read, so a Cypher statement is compiled without them until they can be: {}", graph, e.message)
            } else {
                logger.debug("The labelled indexes of {} could not be read again, so what was read before is kept: {}", graph, e.message)
            }
            last?.leading ?: emptySet()
        }
        reads[key] = Read(leading, now, changes)
        return leading
    }
}
