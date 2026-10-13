package org.drivine.backend.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory

/**
 * The properties a node of the graph can be found by: those a range index or a uniqueness constraint
 * begins with. On Oracle an index is over a property whatever the label, so a property is all there
 * is to ask about.
 *
 * A statement asks so that it knows how to test a root's labels. Tested the way the index over
 * labels answers, Oracle takes that index and reads every node of the label, whatever else the
 * statement asks: it estimates the test gives one row. Where a root is found by a property, the
 * labels are tested on what was found, and Oracle takes the property's index. See [OracleSelect.predicate].
 *
 * What Drivine made is read from the graph's catalog and kept for [KEPT_MILLIS], so the question
 * costs a statement a minute and not one for each query, and an index made while the application
 * runs is seen within that time. A catalog that cannot be read gives no properties: a query then
 * reads as it did, and none fails for it.
 */
internal class OracleIndexedProperties(private val persistenceManager: PersistenceManager) {

    private val logger = LoggerFactory.getLogger(OracleIndexedProperties::class.java)

    @Volatile
    private var read: Pair<Long, Set<String>>? = null

    /** Whether an index begins with [property], so that a test of it for equality with a text finds its nodes. */
    fun leads(property: String): Boolean = property in leading()

    private fun leading(): Set<String> {
        val now = System.currentTimeMillis()
        read?.takeIf { now - it.first < KEPT_MILLIS }?.let { return it.second }
        val found = try {
            (persistenceManager.indexes.list().filter { it.kind == SchemaItemKind.RANGE_INDEX } +
                persistenceManager.constraints.list().filter { it.kind == SchemaItemKind.UNIQUENESS_CONSTRAINT })
                .mapNotNull { item: SchemaItemInfo -> item.properties.firstOrNull() }
                .toSet()
        } catch (e: Exception) {
            logger.debug("Could not read the indexes of the graph, so labels are tested as if there were none", e)
            emptySet()
        }
        read = now to found
        return found
    }

    private companion object {
        const val KEPT_MILLIS = 60_000L
    }
}
