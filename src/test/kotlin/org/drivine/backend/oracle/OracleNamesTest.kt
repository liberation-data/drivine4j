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
        val longest = "g" + "x".repeat(111)
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
        assertEquals("drivine_v2_Docembedding_" + Integer.toHexString("Doc\u0000embedding".hashCode()), graph.vectors("Doc", "embedding"))
    }

    @Test
    fun `a graph's name begins with a letter and is letters, digits and underscores, up to the length that fits`() {
        assertThrows<IllegalArgumentException> { OracleGraph("1graph") }
        assertThrows<IllegalArgumentException> { OracleGraph("my-graph") }
        assertThrows<IllegalArgumentException> { OracleGraph("") }
        assertThrows<IllegalArgumentException> { OracleGraph("_graph") }
        OracleGraph("g_1")
        // The longest: the longest kind with the layout, `_uq2__`, an 8-digit hash with nothing readable, and the `_t` trigger of
        // a table beside the nodes fit in 128.
        val longest = "g" + "x".repeat(111)
        assertEquals(112, longest.length)
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
        val graph = OracleGraph("g" + "x".repeat(111))
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
        val readable = name.removePrefix("g_ix2_").substringBeforeLast("_")
        assertEquals(40, readable.length)
        assertEquals(label.take(40), readable)
        assertEquals("g_ix2_Labelabc", graph.objectName("ix", listOf("Label", "a-b.c")).substringBeforeLast("_"))
    }

    @Test
    fun `parts with nothing that reads as a name give a name of the graph, the kind with the layout and the hash alone`() {
        val graph = OracleGraph("g")
        val parts = listOf("---", "***")
        assertEquals("g_v2__" + Integer.toHexString(parts.joinToString("\u0000").hashCode()), graph.objectName("v", parts))
        assertTrue(Regex("g_v2__[0-9a-f]{1,8}").matches(graph.objectName("v", parts)))
    }

    @Test
    fun `the DDL makes the three tables, the two edge indexes, the label index, the count store, the lock table and the property graph with its doc property, each only when it is not there`() {
        val graph = OracleGraph("g")
        val ddl = graph.ddl
        assertEquals(12, ddl.size)
        assertTrue(ddl[0].startsWith("CREATE TABLE IF NOT EXISTS g_node ("))
        assertTrue(ddl[1].startsWith("CREATE TABLE IF NOT EXISTS g_edge ("))
        assertTrue(ddl[1].contains("REFERENCES g_node (nid) ON DELETE CASCADE"))
        assertTrue(ddl[2].startsWith("CREATE TABLE IF NOT EXISTS g_schema ("))
        assertTrue(ddl[2].contains("PRIMARY KEY (kind, label, properties)"))
        assertEquals("CREATE INDEX IF NOT EXISTS g_edge_src ON g_edge (src, type)", ddl[3])
        assertEquals("CREATE INDEX IF NOT EXISTS g_edge_dst ON g_edge (dst, type)", ddl[4])
        assertEquals("CREATE MULTIVALUE INDEX IF NOT EXISTS g_node_labels ON g_node n (n.labels.string())", ddl[5])
        // The count store and the lock table carry the store layout in their names.
        assertTrue(ddl[6].startsWith("CREATE TABLE IF NOT EXISTS g_count2 ("), ddl[6])
        assertTrue(ddl[7].startsWith("CREATE TABLE IF NOT EXISTS g_count2_delta ("), ddl[7])
        assertEquals("CREATE INDEX IF NOT EXISTS g_count2_dk ON g_count2_delta (kind, name)", ddl[8])
        assertEquals("CREATE TABLE IF NOT EXISTS g_lock2 (name VARCHAR2(128) PRIMARY KEY)", ddl[9])
        assertTrue(ddl[10].startsWith("MERGE INTO g_lock2 l"), ddl[10])
        val propertyGraph = ddl[11]
        assertTrue(propertyGraph.startsWith("CREATE OR REPLACE PROPERTY GRAPH g"))
        assertTrue(propertyGraph.contains("VERTEX TABLES (g_node KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp, ${OracleSql.transform("")} AS doc))"))
        assertTrue(propertyGraph.contains("JSON_TRANSFORM(props, SET '$.labels' = labels, SET '$.\"__drivine.stamp\"' = stamp) AS doc"))
        assertTrue(propertyGraph.contains("EDGE TABLES (g_edge KEY (eid)"))
        assertTrue(propertyGraph.contains("LABEL edge PROPERTIES (eid, src, dst, type, props)"))
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
        // Another session's lock (ORA-00054) is waited out, and what another session made is taken as made.
        assertTrue(ensuring.contains("PRAGMA EXCEPTION_INIT(e_busy, -54);"))
        assertTrue(ensuring.contains("WHEN e_exists THEN RETURN;"))
        assertTrue(ensuring.contains("DBMS_SESSION.SLEEP(0.1);"))
        for (statement in graph.ddl) {
            assertTrue(ensuring.contains("make(${OracleSql.literal(statement)});"), "wraps: $statement")
        }
        // The count store and the lock table are made again where the tables are there and they are not, and so is the nodes'
        // words column.
        val countStore = graph.ddl.filter { "_count2" in it || "_lock2" in it }
        assertEquals(5, countStore.size)
        assertTrue(ensuring.contains("SELECT COUNT(*) INTO v_made FROM user_tables WHERE table_name = 'MYGRAPH_COUNT2';"), ensuring)
        assertTrue(ensuring.contains("make('ALTER TABLE myGraph_node ADD (words JSON)');"), ensuring)
        assertEquals(graph.ddl.size + countStore.size + 1, Regex("\\bmake\\('").findAll(ensuring).count())
        // The graph's functions are made after the tables, whether or not the tables were there, where they are missing or
        // not valid. Each replaces one another process made, and a user who may not make one still has the store (#270).
        val functionsChecked = ensuring.indexOf("SELECT COUNT(*) INTO v_made FROM user_objects WHERE object_type = 'FUNCTION' AND object_name = 'MYGRAPH_NULLS_EQUAL' AND status = 'VALID';")
        assertTrue(functionsChecked > ensuring.lastIndexOf("make(${OracleSql.literal(graph.ddl.last())});"), ensuring)
        assertTrue(ensuring.contains("PRAGMA EXCEPTION_INIT(e_denied, -1031);"))
        val names = listOf("myGraph_nulls_equal", "myGraph_era", "myGraph_era_date", "myGraph_temporal_key")
        assertEquals(names, graph.functions.map { it.substringAfter("FUNCTION ").substringBefore('(') })
        // Each is looked for, and made, on its own: a store an earlier version made may have one and not the others.
        for ((name, statement) in names.zip(graph.functions)) {
            assertTrue(statement.startsWith("CREATE OR REPLACE FUNCTION "), statement)
            val checked = ensuring.indexOf("SELECT COUNT(*) INTO v_made FROM user_objects WHERE object_type = 'FUNCTION' AND object_name = '${name.uppercase()}' AND status = 'VALID';")
            val wrapped = ensuring.indexOf("BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(statement)}; EXCEPTION WHEN e_denied THEN NULL; END;")
            assertTrue(checked >= functionsChecked && wrapped > checked, "wraps: $statement")
        }
        assertEquals(graph.functions.size, Regex("BEGIN EXECUTE IMMEDIATE ").findAll(ensuring).count())
        // Nothing in the block is read as a parameter: the JSON paths are inside literals.
        assertTrue(SqlParameters.positional(ensuring).names.isEmpty())
    }
}
