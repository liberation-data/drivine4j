package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The properties that find a few nodes of the graph: those with a range index of their own whose
 * statistics say a value of the property is held by [FEW] nodes at most.
 *
 * A statement asks so that it knows how to test a root's labels. Tested the way the index over
 * labels answers, Oracle takes that index and reads every node of the label, whatever else the
 * statement asks: it estimates the test gives one row. Where a root is found by such a property, the
 * labels are tested on what was found, and Oracle takes the property's index. See [OracleSelect.predicate].
 *
 * That an index is there is not enough. An index is over a property whatever the label, and with
 * the labels tested on what is found, the index over labels is no longer Oracle's to take. A
 * property that many nodes share a value of, the tenant of every node, would then find its roots by
 * reading far more than their label holds, or the whole table. So the index must be one that finds
 * few, by Oracle's own count of it: reading [FEW] nodes at the worst is a cost that does not grow
 * with the graph. An index with no statistics yet is not counted, and neither is one over several
 * properties, whose statistics count its whole key and not the property it begins with.
 *
 * The properties are read in one statement, on the caller's own connection, and kept for
 * [KEPT_NANOS]. When that has passed the next caller reads them again, and the callers beside it go
 * on with what was kept. A read that fails keeps what was known and is not tried again until the
 * time has passed once more: a query then reads as it did, and none fails for it.
 */
internal class OracleIndexedProperties(private val persistenceManager: PersistenceManager, private val graph: OracleGraph) {

    private val logger = LoggerFactory.getLogger(OracleIndexedProperties::class.java)

    private class Read(val at: Long, val properties: Set<String>)

    @Volatile
    private var read: Read? = null

    private val reading = AtomicBoolean(false)

    /** Whether a test of [property] for equality with a text finds few nodes, through an index of its own. */
    fun finds(property: String): Boolean = property in few()

    private fun few(): Set<String> {
        val known = read
        if (known != null && System.nanoTime() - known.at < KEPT_NANOS) return known.properties
        if (!reading.compareAndSet(false, true)) return known?.properties.orEmpty()
        try {
            val found = try {
                persistenceManager.query(QuerySpecification.withStatement(statement).bind(mapOf("few" to FEW)).transform(String::class.java)).toSet()
            } catch (e: Exception) {
                logger.debug("Could not read which properties of the graph {} find few nodes; keeping what was known", graph.name, e)
                known?.properties.orEmpty()
            }
            read = Read(System.nanoTime(), found)
            return found
        } finally {
            reading.set(false)
        }
    }

    /**
     * The property of each range index over one property that Drivine recorded, and that Oracle has
     * counted as holding [FEW] rows to a key at most. The index is named in Oracle's dictionary as
     * Oracle keeps a name written bare, in upper case.
     */
    private val statement: String = """
        SELECT JSON_VALUE(c.properties, '$[0]') AS property
        FROM ${graph.catalog} c
        JOIN user_indexes i ON i.index_name = UPPER(c.object_name)
        WHERE c.kind = '${SchemaItemKind.RANGE_INDEX.name}'
          AND JSON_VALUE(c.properties, '$.size()' RETURNING NUMBER) = 1
          AND i.distinct_keys > 0
          AND i.num_rows <= i.distinct_keys * ${'$'}few
    """.trimIndent()

    companion object {
        /** The most nodes a value of a property may be held by for the property to count as finding few. */
        const val FEW = 1_000

        private const val KEPT_NANOS = 60_000_000_000L
    }
}
