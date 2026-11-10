package org.drivine.schema.golden

import org.drivine.schema.SchemaStatementTrace
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertContains(difference, "at entry 2 (golden 2, live 3)")
        assertContains(difference, "golden: b")
        assertContains(difference, "live:   c")
        assertContains(assertNotNull(SchemaStatementGolden.difference("k", "NEO4J", listOf("a"), emptyList())), "live:   (none)")
    }

    @Test
    fun `a test is keyed by its unique id without the engine`() {
        assertEquals("[class:a.B]/[method:c()]", SchemaStatementGolden.keyOf("[engine:junit-jupiter]/[class:a.B]/[method:c()]"))
    }
}
