package org.drivine.backend.oracle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.drivine.query.SqlParameters

class OracleNamesTest {

    @Test
    fun `a number is written as the plain form Oracle reads a JSON number back as`() {
        assertEquals("3", OracleSql.key(3.0))
        assertEquals("3", OracleSql.key(3.0f))
        assertEquals("3", OracleSql.key(BigDecimal("3.00")))
        assertEquals("2.5", OracleSql.key(2.5))
        assertEquals("42", OracleSql.key(42L))
        assertEquals("ada", OracleSql.key("ada"))
    }

    @Test
    fun `an object's name fits Oracle's limit with what is made beside it, whatever the graph is called`() {
        val longest = "g" + "x".repeat(113)
        val graph = OracleGraph(longest)
        val labels = listOf("A".repeat(60), "embedding".repeat(5))
        // A table beside the nodes has a trigger and an index named after it; an index on the nodes stands alone.
        for (side in listOf(graph.vectors(labels[0], labels[1]), graph.texts(labels[0], labels))) {
            assertTrue(side.length + "_t".length <= 128, "$side is ${side.length} long")
            assertTrue(side.startsWith(longest + "_"))
        }
        assertTrue(graph.objectName("uq", labels).length <= 128)
        assertThrows<IllegalArgumentException> { OracleGraph(longest + "x") }
    }

    @Test
    fun `two objects made for different parts never share a name`() {
        val graph = OracleGraph("drivine")
        assertNotEquals(graph.texts("Doc", listOf("title", "body")), graph.texts("Doc", listOf("titlebody")))
        assertNotEquals(graph.vectors("Doc", "embedding"), graph.vectors("Do", "cembedding"))
        assertEquals("drivine_v_Docembedding_" + Integer.toHexString("Doc\u0000embedding".hashCode()), graph.vectors("Doc", "embedding"))
    }

    @Test
    fun `a graph's name begins with a letter and is letters, digits and underscores, up to the length that fits`() {
        assertThrows<IllegalArgumentException> { OracleGraph("1graph") }
        assertThrows<IllegalArgumentException> { OracleGraph("my-graph") }
        assertThrows<IllegalArgumentException> { OracleGraph("") }
        assertThrows<IllegalArgumentException> { OracleGraph("_graph") }
        OracleGraph("g_1")
        // The longest: a table beside the nodes, `_v__` and an 8-digit hash with nothing readable, and its `_t` trigger fit in 128.
        val longest = "g" + "x".repeat(113)
        assertEquals(114, longest.length)
        assertEquals(longest, OracleGraph(longest).name)
        assertThrows<IllegalArgumentException> { OracleGraph(longest + "y") }
    }

    @Test
    fun `the graph names its tables after itself`() {
        val graph = OracleGraph("drivine")
        assertEquals("drivine_node", graph.nodes)
        assertEquals("drivine_edge", graph.edges)
        assertEquals("drivine_schema", graph.catalog)
    }

    @Test
    fun `an object's name leaves room for what is made beside it, whether the hash is negative or positive`() {
        val graph = OracleGraph("g" + "x".repeat(113))
        val negative = (0..10_000).map { listOf("Label$it", "prop") }.first { it.joinToString("\u0000").hashCode() < 0 }
        val positive = (0..10_000).map { listOf("Label$it", "prop") }.first { it.joinToString("\u0000").hashCode() > 0 }
        for (parts in listOf(negative, positive)) {
            val name = graph.objectName("v", parts)
            assertTrue(name.length <= 128 - 2, "$name is ${name.length} long")
            assertTrue(name.endsWith("_" + Integer.toHexString(parts.joinToString("\u0000").hashCode())))
        }
        assertEquals(8, Integer.toHexString(negative.joinToString("\u0000").hashCode()).length)
    }

    @Test
    fun `what of the parts reads as a name is at most 40 characters, letters and digits only`() {
        val graph = OracleGraph("g")
        val label = "Label".repeat(20)
        val name = graph.objectName("ix", listOf(label, "a-b.c"))
        val readable = name.removePrefix("g_ix_").substringBeforeLast("_")
        assertEquals(40, readable.length)
        assertEquals(label.take(40), readable)
        assertEquals("g_ix_Labelabc", graph.objectName("ix", listOf("Label", "a-b.c")).substringBeforeLast("_"))
    }

    @Test
    fun `parts with nothing that reads as a name give a name of the graph, the kind and the hash alone`() {
        val graph = OracleGraph("g")
        val parts = listOf("---", "***")
        assertEquals("g_v__" + Integer.toHexString(parts.joinToString("\u0000").hashCode()), graph.objectName("v", parts))
        assertTrue(Regex("g_v__[0-9a-f]{1,8}").matches(graph.objectName("v", parts)))
    }

    @Test
    fun `the DDL makes the three tables, the two edge indexes, the label index and the property graph with its doc property, each only when it is not there`() {
        val graph = OracleGraph("g")
        val ddl = graph.ddl
        assertEquals(7, ddl.size)
        assertTrue(ddl[0].startsWith("CREATE TABLE IF NOT EXISTS g_node ("))
        assertTrue(ddl[1].startsWith("CREATE TABLE IF NOT EXISTS g_edge ("))
        assertTrue(ddl[1].contains("REFERENCES g_node (nid) ON DELETE CASCADE"))
        assertTrue(ddl[2].startsWith("CREATE TABLE IF NOT EXISTS g_schema ("))
        assertTrue(ddl[2].contains("PRIMARY KEY (kind, label, properties)"))
        assertEquals("CREATE INDEX IF NOT EXISTS g_edge_src ON g_edge (src, type)", ddl[3])
        assertEquals("CREATE INDEX IF NOT EXISTS g_edge_dst ON g_edge (dst, type)", ddl[4])
        assertEquals("CREATE MULTIVALUE INDEX IF NOT EXISTS g_node_labels ON g_node n (n.labels.string())", ddl[5])
        assertTrue(ddl[6].startsWith("CREATE OR REPLACE PROPERTY GRAPH g"))
        assertTrue(ddl[6].contains("VERTEX TABLES (g_node KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp, ${OracleSql.transform("")} AS doc))"))
        assertTrue(ddl[6].contains("JSON_TRANSFORM(props, SET '$.labels' = labels, SET '$.\"__drivine.stamp\"' = stamp) AS doc"))
        assertTrue(ddl[6].contains("EDGE TABLES (g_edge KEY (eid)"))
        assertTrue(ddl[6].contains("LABEL edge PROPERTIES (eid, src, dst, type, props)"))
    }

    @Test
    fun `the block that makes the store looks for the doc property of the graph first, and runs each DDL tolerant of what another process made`() {
        val graph = OracleGraph("myGraph")
        val ensuring = graph.ensuring
        // An embedded DDL literal has lines at column 0, so the template's own indent is kept: harmless to PL/SQL.
        assertTrue(ensuring.trimStart().startsWith("DECLARE"))
        assertTrue(ensuring.endsWith("END;"))
        assertTrue(ensuring.contains("PRAGMA EXCEPTION_INIT(e_exists, -955);"))
        assertTrue(ensuring.contains("SELECT COUNT(*) INTO v_made FROM user_pg_prop_definitions WHERE graph_name = 'MYGRAPH' AND property_name = 'DOC';"))
        assertTrue(ensuring.contains("IF v_made = 0 THEN"))
        for (statement in graph.ddl) {
            assertTrue(ensuring.contains("BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(statement)}; EXCEPTION WHEN e_exists THEN NULL; END;"), "wraps: $statement")
        }
        assertEquals(graph.ddl.size, Regex("BEGIN EXECUTE IMMEDIATE ").findAll(ensuring).count())
        // Nothing in the block is read as a parameter: the JSON paths are inside literals.
        assertTrue(SqlParameters.positional(ensuring).names.isEmpty())
    }
}
