package org.drivine.cli

import org.drivine.query.GraphNode
import org.drivine.query.GraphPath
import org.drivine.query.GraphRelationship
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

class RenderTest {

    private fun json(result: Result) = StringBuilder().also { JsonRenderer.render(result, it) }.toString()

    private fun plain(result: Result) = StringBuilder().also { PlainRenderer.render(result, it) }.toString()

    private fun json(value: Any?) = StringBuilder().also { JsonRenderer.write(value, it) }.toString()

    private fun plain(value: Any?) = StringBuilder().also { PlainRenderer.write(value, it) }.toString()

    @Test
    fun `json is one compact line per statement`() {
        assertEquals("{\"columns\":[\"n\"],\"rows\":[[1]]}\n", json(Result(listOf("n"), listOf(listOf(1L)))))
        assertEquals("{\"columns\":[\"x\"],\"rows\":[[1],[2]]}\n", json(Result(listOf("x"), listOf(listOf(1L), listOf(2L)))))
        assertEquals("{\"columns\":[\"n\"],\"rows\":[]}\n", json(Result(listOf("n"), emptyList())))
        assertEquals("{\"columns\":[],\"rows\":[]}\n", json(Result(emptyList(), emptyList())))
    }

    @Test
    fun `json writes a float as Double's toString does, and NaN and the infinities as the spec does`() {
        assertEquals("2.0", json(2.0))
        assertEquals("1.0E10", json(1.0E10))
        assertEquals("1.0E-4", json(0.0001))
        assertEquals("12345.678", json(12345.678))
        assertEquals("-0.0", json(-0.0))
        assertEquals("1.5", json(1.5f))
        assertEquals("{\"\$float\":\"NaN\"}", json(Double.NaN))
        assertEquals("{\"\$float\":\"Infinity\"}", json(Double.POSITIVE_INFINITY))
        assertEquals("{\"\$float\":\"-Infinity\"}", json(Double.NEGATIVE_INFINITY))
        assertEquals("{\"\$float\":\"NaN\"}", json(Float.NaN))
    }

    @Test
    fun `json writes a whole number exactly, past 2^53 too`() {
        assertEquals("9007199254740993", json(9007199254740993L))
        assertEquals("-1", json(-1))
        assertEquals("123456789012345678901234567890", json(BigInteger("123456789012345678901234567890")))
    }

    @Test
    fun `json escapes a text only where JSON needs it`() {
        assertEquals("\"é😀\"", json("é😀"))
        assertEquals("\"a\\\"b\\\\c\"", json("a\"b\\c"))
        assertEquals("\"\\b\\f\\n\\r\\t\"", json("\b\u000C\n\r\t"))
        assertEquals("\"\\u0000\\u001f\"", json("\u0000\u001F"))
        assertEquals("\"/\u007f\"", json("/\u007F"))
    }

    @Test
    fun `json writes lists, maps in their order, booleans, null and temporal values`() {
        assertEquals("[1.5,true,null]", json(listOf(1.5, true, null)))
        assertEquals("{\"b\":1,\"a\":[\"x\"]}", json(linkedMapOf("b" to 1L, "a" to listOf("x"))))
        assertEquals("\"2024-01-01\"", json(LocalDate.of(2024, 1, 1)))
        assertEquals("\"2024-01-01T10:00Z\"", json(OffsetDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)))
    }

    @Test
    fun `plain prints the columns, then a line per row, and nothing for no rows`() {
        assertEquals("n, s\n1, \"a\"\n2, NULL\n", plain(Result(listOf("n", "s"), listOf(listOf(1L, "a"), listOf(2L, null)))))
        assertEquals("", plain(Result(listOf("n"), emptyList())))
    }

    @Test
    fun `plain prints each value as cypher-shell does`() {
        assertEquals("NULL", plain(null))
        assertEquals("TRUE", plain(true))
        assertEquals("FALSE", plain(false))
        assertEquals("2.0", plain(2.0))
        assertEquals("1.0E10", plain(1.0E10))
        assertEquals("1.0E-4", plain(0.0001))
        assertEquals("-0.0", plain(-0.0))
        assertEquals("NaN", plain(Double.NaN))
        assertEquals("9007199254740993", plain(9007199254740993L))
        assertEquals("[1, \"b\"]", plain(listOf(1L, "b")))
        assertEquals("{k: 2, j: [TRUE]}", plain(linkedMapOf("k" to 2L, "j" to listOf(true))))
        assertEquals("2024-01-01", plain(LocalDate.of(2024, 1, 1)))
    }

    @Test
    fun `plain escapes a quote in a text, and prints a backslash and a line break as they are`() {
        assertEquals("\"a\\\"b\\c\"", plain("a\"b\\c"))
        assertEquals("\"a\n:exit\nb\"", plain("a\n:exit\nb"))
        assertEquals("\"é😀\"", plain("é😀"))
    }

    private val a = GraphNode(listOf("B", "A"), linkedMapOf("name" to "x", "n" to 1L))
    private val b = GraphNode(listOf("B"), emptyMap())
    private val c = GraphNode(listOf("C"), emptyMap())
    private val r = GraphRelationship("R", mapOf("w" to 1L))
    private val s = GraphRelationship("S", emptyMap())

    @Test
    fun `plain prints a node and a relationship as cypher-shell does, and no braces for no properties`() {
        assertEquals("(:A:B {name: \"x\", n: 1})", plain(a))
        assertEquals("(:B)", plain(b))
        assertEquals("()", plain(GraphNode(emptyList(), emptyMap())))
        assertEquals("({k: [1, {m: 2}]})", plain(GraphNode(emptyList(), mapOf("k" to listOf(1L, mapOf("m" to 2L))))))
        assertEquals("[:R {w: 1}]", plain(r))
        assertEquals("[:S]", plain(s))
        assertEquals("[(:B), {k: [:S]}]", plain(listOf(b, mapOf("k" to s))))
    }

    @Test
    fun `plain prints a path along and against each relationship's direction`() {
        assertEquals("(:B)-[:R {w: 1}]->(:C)<-[:S]-(:B)", plain(GraphPath(listOf(b, c, b), listOf(r, s), listOf(true, false))))
        assertEquals("(:B)<-[:S]-(:C)", plain(GraphPath(listOf(b, c), listOf(s), listOf(false))))
        assertEquals("(:B)", plain(GraphPath(listOf(b), emptyList(), emptyList())))
    }

    @Test
    fun `json writes a node, a relationship and a path as objects, with no ids`() {
        assertEquals("{\"labels\":[\"A\",\"B\"],\"properties\":{\"name\":\"x\",\"n\":1}}", json(a))
        assertEquals("{\"type\":\"S\",\"properties\":{}}", json(s))
        assertEquals(
            "{\"nodes\":[{\"labels\":[\"B\"],\"properties\":{}},{\"labels\":[\"C\"],\"properties\":{}}]," +
                "\"relationships\":[{\"type\":\"S\",\"properties\":{}}],\"forward\":[false]}",
            json(GraphPath(listOf(b, c), listOf(s), listOf(false))),
        )
        assertEquals("{\"columns\":[\"n\"],\"rows\":[[{\"labels\":[\"B\"],\"properties\":{}}]]}\n", json(Result(listOf("n"), listOf(listOf(b)))))
    }
}
