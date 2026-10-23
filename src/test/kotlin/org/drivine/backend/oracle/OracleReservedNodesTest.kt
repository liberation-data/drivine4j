package org.drivine.backend.oracle

import org.drivine.query.SqlParameters
import org.drivine.schema.SchemaVersionStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The schema marker and the store's stamp as Oracle keeps them: nodes of the graph, written in SQL. */
class OracleReservedNodesTest {

    private val graph = OracleGraph("g")
    private val marker = OracleVersionMarker(graph, SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)
    private val stamp = OracleStoreStamp(graph, "DrivineStore", "key")

    private fun parameters(sql: String): Set<String> = SqlParameters.positional(sql).names.toSet()

    private val isMarker = "JSON_EXISTS(n.labels, '$[*]?(@ == \"_DrivineSchema\")') AND JSON_VALUE(n.props, '$.scope') = \$scope"

    // ----- The schema marker -----

    @Test
    fun `the marker and the stamp are nodes of the graph, so each makes the graph before anything else`() {
        assertEquals(graph.ensuring, marker.prepare)
        assertEquals(graph.ensuring, stamp.prepare)
    }

    @Test
    fun `a property of the marker is read as a CLOB from the newest marker of the scope, one row at most`() {
        assertEquals(
            "SELECT JSON_VALUE(n.props, '$.items' RETURNING CLOB) FROM g_node n WHERE $isMarker" +
                " ORDER BY JSON_VALUE(n.props, '$.appliedAt' RETURNING NUMBER) DESC NULLS LAST, n.nid DESC FETCH FIRST 1 ROWS ONLY",
            marker.read("items"),
        )
        assertEquals(setOf("scope"), parameters(marker.read("version")))
    }

    @Test
    fun `the merge binds exactly what its bindings give, the moment among them`() {
        val before = System.currentTimeMillis()
        val bindings = marker.mergeBindings("owner", "v2", "a\nb")
        val after = System.currentTimeMillis()

        assertEquals(parameters(marker.merge), bindings.keys)
        assertEquals("owner", bindings["scope"])
        assertEquals("v2", bindings["version"])
        assertTrue(bindings["now"] as Long in before..after)
    }

    @Test
    fun `the merge updates the marker of the scope, or makes one with the marker's label and the scope as its key`() {
        val merge = marker.merge
        val newest = "SELECT m.nid FROM g_node m WHERE ${isMarker.replace("n.", "m.")}" +
            " ORDER BY JSON_VALUE(m.props, '$.appliedAt' RETURNING NUMBER) DESC NULLS LAST, m.nid DESC FETCH FIRST 1 ROWS ONLY"

        // Matched by the id of the marker found: Oracle refuses an update of a column the match reads (ORA-38104).
        assertTrue(merge.startsWith("MERGE INTO g_node n\nUSING (SELECT ($newest) AS nid FROM dual) found\nON (n.nid = found.nid)\n"), merge)
        assertTrue("WHEN MATCHED THEN UPDATE SET n.props = JSON_MERGEPATCH(n.props, JSON_OBJECT('version' VALUE \$version, 'items' VALUE \$items, 'appliedAt' VALUE \$now RETURNING JSON))" in merge, merge)
        assertTrue("VALUES ('[\"_DrivineSchema\"]', JSON_OBJECT('scope' VALUE \$scope, 'version' VALUE \$version, 'items' VALUE \$items, 'appliedAt' VALUE \$now ABSENT ON NULL RETURNING JSON))" in merge, merge)
    }

    @Test
    fun `a version that is null is bound as null, so a marker can be written with none`() {
        val bindings = marker.mergeBindings("schema", null, "")

        assertTrue("version" in bindings)
        assertEquals(null, bindings["version"])
    }

    @Test
    fun `an empty inventory is stored as text that is not empty, and read back as empty`() {
        val stored = marker.mergeBindings("schema", "v1", "")["items"] as String

        // Oracle keeps an empty string as null, which would read back as a marker with no inventory at all.
        assertTrue(stored.isNotEmpty())
        assertEquals("", marker.items(stored))
    }

    @Test
    fun `an inventory is read back as it was given, one that begins with the mark itself included`() {
        for (items in listOf("RANGE_INDEX:Person:name", "a\nb\nc", "items:", "items:items:x", " ")) {
            assertEquals(items, marker.items(marker.storedItems(items)), items)
        }
        assertEquals("items:a\nb", marker.storedItems("a\nb"))
    }

    @Test
    fun `all but the newest marker of the scope are removed, the higher id breaking a tie, and a clear removes every one`() {
        val collapse = marker.deleteAllButNewest

        assertTrue(collapse.startsWith("DELETE FROM g_node WHERE nid IN ("), collapse)
        assertTrue("ROW_NUMBER() OVER (ORDER BY JSON_VALUE(n.props, '$.appliedAt' RETURNING NUMBER) DESC NULLS LAST, n.nid DESC) AS position FROM g_node n WHERE $isMarker)" in collapse, collapse)
        assertTrue(collapse.trimEnd().endsWith("WHERE position > 1)"), collapse)
        assertEquals(setOf("scope"), parameters(collapse))

        assertEquals("DELETE FROM g_node n WHERE $isMarker", marker.delete)
    }

    @Test
    fun `a scope property with a dot in it is one key, in what is read and in what is written`() {
        val dotted = OracleVersionMarker(graph, "Marker", "owner.scope")

        assertTrue("JSON_VALUE(n.props, '$.\"owner.scope\"') = \$scope" in dotted.read("version"), dotted.read("version"))
        assertTrue("JSON_OBJECT('owner.scope' VALUE \$scope" in dotted.merge, dotted.merge)
    }

    // ----- The store's stamp -----

    @Test
    fun `the stamp is made only when no node of its label has the key, and binds the four values the resolver gives`() {
        val assign = stamp.assignIfAbsent

        assertEquals(setOf("key", "storeId", "assignedAt", "engine"), parameters(assign))
        assertTrue(assign.startsWith("INSERT INTO g_node (labels, props)\nSELECT '[\"DrivineStore\"]', JSON_OBJECT('key' VALUE \$key, 'storeId' VALUE \$storeId, 'assignedAt' VALUE \$assignedAt, 'engine' VALUE \$engine)"), assign)
        assertTrue(
            assign.endsWith("WHERE NOT EXISTS (SELECT 1 FROM g_node n WHERE JSON_EXISTS(n.labels, '$[*]?(@ == \"DrivineStore\")') AND JSON_VALUE(n.props, '$.key') = \$key)"),
            assign,
        )
    }

    @Test
    fun `every stamp is read as a map of its id and when it was assigned, the oldest first and the id breaking a tie`() {
        val read = stamp.readStamps

        assertTrue(read.startsWith("SELECT JSON_OBJECT('id' VALUE JSON_VALUE(n.props, '$.storeId'), 'assignedAt' VALUE JSON_VALUE(n.props, '$.assignedAt') RETURNING JSON)"), read)
        assertTrue(read.endsWith("ORDER BY JSON_VALUE(n.props, '$.assignedAt'), JSON_VALUE(n.props, '$.storeId')"), read)
        // Every stamp of the label, whatever its key: a duplicate is to be seen, not hidden.
        assertFalse("\$" in read.replace("'$", "'"), read)
        assertTrue(parameters(read).isEmpty())
    }
}
