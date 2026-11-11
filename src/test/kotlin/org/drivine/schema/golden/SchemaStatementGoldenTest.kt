package org.drivine.schema.golden

import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.SchemaStatementTrace
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the schema-statement golden normalises and compares what a test sent. The golden itself is checked by
 * [SchemaStatementGoldenExtension] in the classes it was recorded in: see src/test/resources/schema-golden/README.md.
 */
class SchemaStatementGoldenTest {

    private fun raw(statement: String, parameters: Map<String, Any?> = emptyMap(), concurrent: Boolean = false, outcome: String = "ran") =
        SchemaStatementGolden.Raw("NEO4J", "schema-connection", "statement", statement, parameters, outcome, concurrent)

    @Test
    fun `no observer is set unless the golden is on`() {
        if (SchemaStatementGolden.output == null) assertNull(SchemaStatementTrace.observer)
        else assertNotNull(SchemaStatementTrace.observer)
    }

    @Test
    fun `UUIDs are numbered by first appearance, and the clock is a placeholder`() {
        val id = "0b6d3c1e-8f7a-4d2b-9c1e-2a3b4c5d6e7f"
        val lines = SchemaStatementGolden.normalise(
            "[class:X]/[method:t()]",
            listOf(
                raw("MATCH (n {id: '$id'}) RETURN n", mapOf("now" to 1_762_000_000_000L, "at" to "2026-11-11T10:00:00.123Z")),
                raw("MERGE (m {id: \$id})", mapOf("id" to id, "appliedAt" to 5L, "timeout" to 60L, "batch" to 10_000)),
            ),
        ).getValue("NEO4J")
        assertEquals(
            listOf(
                """{"test":"[class:X]/[method:t()]","n":1,"path":"schema-connection","kind":"statement","statement":"MATCH (n {id: '<uuid-1>'}) RETURN n","parameters":{"at":"<now>","now":"<now>"},"outcome":"ran"}""",
                """{"test":"[class:X]/[method:t()]","n":2,"path":"schema-connection","kind":"statement","statement":"MERGE (m {id: ${'$'}id})","parameters":{"appliedAt":"<now>","batch":10000,"id":"<uuid-1>","timeout":60},"outcome":"ran"}""",
            ),
            lines,
        )
    }

    @Test
    fun `statements of other threads are kept once each, sorted, after the test's own`() {
        val lines = SchemaStatementGolden.normalise(
            "k",
            listOf(raw("B", concurrent = true), raw("A"), raw("C", concurrent = true), raw("B", concurrent = true)),
        ).getValue("NEO4J")
        assertEquals(listOf("A", "B", "C"), lines.map { Regex("\"statement\":\"(.)\"").find(it)!!.groupValues[1] })
        assertEquals(listOf(true, false, false), lines.map { "\"n\":1" in it })
    }

    @Test
    fun `a random graph is numbered, an outcome of another thread is left out, and a long statement is given by its hash`() {
        val long = "SELECT 1 FROM ddl8kfvy4_node WHERE " + "x = 1 AND ".repeat(40) + "TRUE"
        val lines = SchemaStatementGolden.normalise(
            "k",
            listOf(raw("SELECT * FROM DDL8KFVY4_SCHEMA"), raw(long), raw("DROP TABLE ddlw8a38j_edge", concurrent = true, outcome = "failed: X")),
        ).getValue("NEO4J")
        assertContains(lines[0], "\"statement\":\"SELECT * FROM <graph-1>_SCHEMA\"")
        assertContains(lines[1], "\"statement\":\"sha256:")
        assertContains(lines[2], "\"statement\":\"DROP TABLE <graph-2>_edge\"")
        assertContains(lines[2], "\"outcome\":null")
    }

    @Test
    fun `the first difference is named`() {
        assertNull(SchemaStatementGolden.difference("k", "NEO4J", listOf("a", "b"), listOf("a", "b")))
        val difference = assertNotNull(SchemaStatementGolden.difference("k", "NEO4J", listOf("a", "b"), listOf("a", "c", "d")))
        assertContains(difference, "at write 2 (golden 2, live 3)")
        assertContains(difference, "golden: b")
        assertContains(difference, "live:   c")
        assertContains(assertNotNull(SchemaStatementGolden.difference("k", "NEO4J", listOf("a"), emptyList())), "live:   (none)")
    }

    private fun line(statement: String, n: Int, kind: String = "statement", outcome: String = "ran") =
        """{"test":"k","n":$n,"path":"schema-connection","kind":"$kind","statement":"$statement","parameters":{},"outcome":"$outcome"}"""

    private fun check(store: String, golden: List<String>, live: List<String>, texts: Map<String, String> = emptyMap()) =
        SchemaStatementGolden.difference("k", store, SchemaStatementGolden.writes(store, golden, texts), SchemaStatementGolden.writes(store, live, texts))

    @Test
    fun `a read the live run leaves out, or makes again, passes, and the writes keep their order without their places`() {
        val golden = listOf(
            line("SHOW INDEXES YIELD name", 1),
            line("CREATE RANGE INDEX a IF NOT EXISTS FOR (n:A) ON (n.k)", 2),
            line("CALL db.awaitIndex(${'$'}name, ${'$'}timeout)", 3),
            line("SHOW INDEXES YIELD name", 4),
            line("index build", 5, kind = "poll", outcome = "built"),
            line("MATCH (m:`_DrivineSchema` {scope: ${'$'}scope}) RETURN m.items", 6),
            line("MERGE (m:`_DrivineSchema` {scope: ${'$'}scope}) SET m.version = ${'$'}version", 7),
        )
        val live = listOf(
            line("CREATE RANGE INDEX a IF NOT EXISTS FOR (n:A) ON (n.k)", 1),
            line("CALL db.awaitIndex(${'$'}name, ${'$'}timeout)", 2),
            line("MATCH (n:A) WHERE n.k IS NOT NULL WITH n.k AS k, count(*) AS c WHERE c > 1 RETURN k", 3),
            line("MERGE (m:`_DrivineSchema` {scope: ${'$'}scope}) SET m.version = ${'$'}version", 4),
        )
        assertNull(check("NEO4J", golden, live))
        assertEquals(3, SchemaStatementGolden.writes("NEO4J", golden, emptyMap()).size)
        assertTrue(SchemaStatementGolden.writes("NEO4J", golden, emptyMap()).none { "\"n\":" in it })
    }

    @Test
    fun `a write the live run leaves out, adds, changes or moves fails`() {
        val create = line("CREATE RANGE INDEX a IF NOT EXISTS FOR (n:A) ON (n.k)", 1)
        val merge = line("MERGE (m:`_DrivineSchema` {scope: ${'$'}scope}) SET m.version = ${'$'}version", 2)
        val fill = line("MATCH (n:A) WHERE NOT coalesce(n.l = toLower(n.k), false) SET n.l = toLower(n.k)", 3)
        assertNotNull(check("NEO4J", listOf(create, merge), listOf(create)))
        assertNotNull(check("NEO4J", listOf(create), listOf(create, fill)))
        assertNotNull(check("NEO4J", listOf(merge, create), listOf(create, merge)))
        assertNotNull(check("NEO4J", listOf(create), listOf(line("CREATE RANGE INDEX b IF NOT EXISTS FOR (n:A) ON (n.k)", 1))))
        assertNotNull(check("NEO4J", listOf(create), listOf(line("CREATE RANGE INDEX a IF NOT EXISTS FOR (n:A) ON (n.k)", 1, outcome = "failed: X"))))
        assertNotNull(check("FALKORDB", listOf(line("GRAPH.CONSTRAINT CREATE {graphName} UNIQUE NODE A PROPERTIES 1 k", 1, kind = "native")), emptyList()))
        val difference = assertNotNull(check("NEO4J", listOf(line("SHOW INDEXES", 1), create, merge), listOf(create)))
        assertContains(difference, "at write 2 (golden 2, live 1)")
    }

    @Test
    fun `what is a write`() {
        val writes = listOf(
            "CALL db.awaitIndex(${'$'}name, ${'$'}timeout)",
            "MATCH (m:`_DrivineSchema` {scope: ${'$'}scope}) DELETE m",
            "MATCH (s:DrivineStore {key: 'singleton'}) SET s.`__drivine.lock` = true",
            "WITH found AS (SELECT m.nid FROM g_node m) , written AS (UPDATE g_node n SET props = n.props) INSERT INTO g_node (labels, props) SELECT 1",
            "SELECT drivine_lower_copies('g', 'A', 'email', 1)",
            "DECLARE v_labelled VARCHAR2(128); BEGIN SELECT MAX(x) INTO v_labelled FROM g_schema; END;",
            "DO ${'$'}forget${'$'} BEGIN DELETE FROM g_schema; END ${'$'}forget${'$'}",
            "INSERT INTO g_schema (kind) VALUES ('RANGE_INDEX')",
            "/* drivine */ DROP INDEX a",
        )
        val reads = listOf(
            "SHOW CONSTRAINTS YIELD name",
            "CALL db.indexes() YIELD label",
            "CALL vector_search.show_index_info() YIELD index_name",
            "SELECT jsonb_build_object('kind', kind) FROM g_schema",
            "MATCH (m:`_DrivineSchema`) RETURN DISTINCT m.scope",
            "MATCH (s:DrivineStore) UNWIND coalesce(s.lowered, []) AS e RETURN DISTINCT e",
            "/* drivine */ SELECT 1",
        )
        writes.forEach { assertTrue(SchemaStatementGolden.isWrite(it), it) }
        reads.forEach { assertFalse(SchemaStatementGolden.isWrite(it), it) }
    }

    @Test
    fun `the prepare block is the module's statement for the graph it names, numbered or not, and nothing else is`() {
        assertTrue(SchemaStatementGolden.isPrepare("POSTGRES", PostgresDdl("contracts").ensuring))
        assertTrue(SchemaStatementGolden.isPrepare("ORACLE", OracleDdl("contracts").ensuring.replace("\n", "\n  ")))
        assertTrue(SchemaStatementGolden.isPrepare("SQLITE", SqliteDdl("contracts").ddl.first()))
        assertTrue(SchemaStatementGolden.isPrepare("POSTGRES", PostgresDdl("ddlzzgraph").ensuring.replace("ddlzzgraph", "<graph-1>", ignoreCase = true)))
        // The rest of the SQL graph's tables, and an Oracle vector index's side table, are writes like any other.
        assertFalse(SchemaStatementGolden.isPrepare("SQLITE", SqliteDdl("contracts").ddl[1]))
        assertFalse(SchemaStatementGolden.isPrepare("ORACLE", "CREATE TABLE IF NOT EXISTS contracts_v_Docembedding_f151d66f (nid NUMBER)"))
        assertFalse(SchemaStatementGolden.isPrepare("POSTGRES", PostgresDdl("contracts").ensuring.replace("contracts_node", "other_node")))
        assertFalse(SchemaStatementGolden.isPrepare("NEO4J", PostgresDdl("contracts").ensuring))
        // A prepare given by its hash is found by its text, and left out.
        val prepare = line("sha256:0123456789abcdef", 1)
        assertEquals(emptyList(), SchemaStatementGolden.writes("POSTGRES", listOf(prepare), mapOf("sha256:0123456789abcdef" to PostgresDdl("g").ensuring)))
    }

    @Test
    fun `the tests rewritten or added since the golden was recorded are named, each with its reason, and only the recorded classes are checked`() {
        assertEquals(3, SchemaStatementGolden.UNCHECKED.values.count { it.startsWith("rewritten: ") })
        assertEquals(2, SchemaStatementGolden.UNCHECKED.values.count { it.startsWith("added after the recording: ") })
        val recorded = java.io.File(SchemaStatementGolden.DEFAULT_GOLDEN, SchemaStatementGolden.CLASSES).readLines().filter { it.isNotBlank() }.toSet()
        SchemaStatementGolden.UNCHECKED.keys.forEach { key ->
            assertTrue(key.substringAfter("[class:").substringBefore("]") in recorded, key)
            assertFalse(SchemaStatementGolden.checks(key, recorded), key)
        }
        assertTrue(SchemaStatementGolden.checks("[class:org.drivine.schema.SchemaManagerIntegrationTest]/[method:t()]", recorded))
        assertTrue(SchemaStatementGolden.checks("[class:org.drivine.schema.SchemaManagerIntegrationTest]", recorded))
        assertFalse(SchemaStatementGolden.checks("[class:org.drivine.cypher.CypherWritePostgresTest]/[test-factory:spec()]/[dynamic-test:#61]", recorded))
    }

    @Test
    fun `a test is keyed by its unique id without the engine`() {
        assertEquals("[class:a.B]/[method:c()]", SchemaStatementGolden.keyOf("[engine:junit-jupiter]/[class:a.B]/[method:c()]"))
    }
}
