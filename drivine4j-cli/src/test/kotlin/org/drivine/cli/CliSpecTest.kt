package org.drivine.cli

import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.spec.CypherSpec.Case
import org.drivine.cypher.spec.CypherSpec.Hold
import org.drivine.cypher.spec.CypherSpec.Step
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** CliSpec's own pieces, with no store: the script it writes, how it cuts the run's output, and how it reads it. */
class CliSpecTest {

    @Test
    fun `cut gives the part before each marker, without empty lines, and passes over what follows the last`() {
        val text = "a\n\nb\nM\nM\nc\nM\nafter\n"
        assertEquals(listOf(listOf("a", "b"), emptyList(), listOf("c")), CliSpec.cut(text, 2) { it == "M" })
    }

    @Test
    fun `cut fails where the run printed more or fewer markers than its steps and the graph's`() {
        assertThrows<AssertionError> { CliSpec.cut("a\nM\n", 1) { it == "M" } }
        assertThrows<AssertionError> { CliSpec.cut("M\nM\nM\n", 1) { it == "M" } }
    }

    @Test
    fun `literal writes a parameter as the Cypher literal of its value`() {
        assertEquals("null", CliSpec.literal(null))
        assertEquals("true", CliSpec.literal(true))
        assertEquals("-9223372036854775808", CliSpec.literal(Long.MIN_VALUE))
        assertEquals("1.5", CliSpec.literal(1.5))
        assertEquals("1.0E20", CliSpec.literal(1e20))
        assertEquals("0.0 / 0.0", CliSpec.literal(Double.NaN))
        assertEquals("1.0 / 0.0", CliSpec.literal(Double.POSITIVE_INFINITY))
        assertEquals("-1.0 / 0.0", CliSpec.literal(Double.NEGATIVE_INFINITY))
        assertEquals("[1, 'a', [null]]", CliSpec.literal(listOf(1L, "a", listOf(null))))
        assertEquals("{`a`: 1, `it's`: 2, `b``c`: 3, ``: 4}", CliSpec.literal(mapOf("a" to 1L, "it's" to 2L, "b`c" to 3L, "" to 4L)))
        assertThrows<IllegalStateException> { CliSpec.literal(Any()) }
    }

    @Test
    fun `literal escapes a quote, a backslash and a line break in a text, as a param is a command of one line`() {
        assertEquals("""'O\'Brien \\1'""", CliSpec.literal("""O'Brien \1"""))
        assertEquals("""'a\nb\r\nc'""", CliSpec.literal("a\nb\r\nc"))
    }

    @Test
    fun `statement ends a step with a semicolon of its own line, past a comment it ends with, and only one`() {
        assertEquals("RETURN 1 // one\n;\n", CliSpec.statement("RETURN 1 // one"))
        assertEquals("RETURN 1\n;\n", CliSpec.statement("RETURN 1;\n"))
        assertEquals("MATCH (n)\n  RETURN n\n;\n", CliSpec.statement("MATCH (n)\n  RETURN n"))
    }

    @Test
    fun `value reads a float, and a node and a relationship as their properties, outside results yaml`() {
        assertEquals(Double.NaN, CliSpec.value(mapOf("\$float" to "NaN"), typed = false))
        val node = mapOf("\$t" to "node", "labels" to listOf("A"), "properties" to mapOf("x" to mapOf("\$float" to "Infinity")))
        assertEquals(mapOf("x" to Double.POSITIVE_INFINITY), CliSpec.value(node, typed = false))
        assertEquals(listOf(mapOf("y" to 1L)), CliSpec.value(listOf(mapOf("\$t" to "relationship", "type" to "R", "properties" to mapOf("y" to 1L))), typed = false))
    }

    @Test
    fun `value keeps a node's tag and a path's but for forward in results yaml`() {
        val node = mapOf("\$t" to "node", "labels" to listOf("A"), "properties" to emptyMap<String, Any?>())
        assertEquals(node, CliSpec.value(node, typed = true))
        val path = mapOf("\$t" to "path", "nodes" to listOf(node), "relationships" to emptyList<Any?>(), "forward" to emptyList<Any?>())
        assertEquals(path - "forward", CliSpec.value(path, typed = true))
    }

    @Test
    fun `value reads a map of the user's own with a tag key as it is, its tag naming no kind`() {
        val own = mapOf("\$t" to "node", "properties" to mapOf("\$float" to "NaN"), "v" to 1L)
        val written = mapOf("\$t" to "map", "v" to own)
        val read = mapOf("\$t" to "node", "properties" to Double.NaN, "v" to 1L)
        assertEquals(read, CliSpec.value(written, typed = false))
        assertEquals(mapOf("\$map" to read), CliSpec.value(written, typed = true))
        // One whose own keys are the wrapper's.
        val wrapperLike = mapOf("\$t" to "map", "v" to mapOf("\$t" to "map", "v" to "x"))
        assertEquals(mapOf("\$t" to "map", "v" to "x"), CliSpec.value(wrapperLike, typed = false))
    }

    private val case = Case(
        name = "c",
        steps = listOf(
            Step(cypher = "1"),
            Step(cypher = "2", only = listOf("postgres")),
            Step(cypher = "3"),
            Step(cypher = "4"),
        ),
    )

    private fun cyphers(holds: List<Hold>, store: String) = CliSpec.runnable(case, holds, store).map { it.cypher }

    @Test
    fun `runnable gives the steps that run on the store, up to one skipped there`() {
        assertEquals(listOf("1", "3", "4"), cyphers(emptyList(), "sqlite"))
        assertEquals(listOf("1", "2", "3", "4"), cyphers(emptyList(), "postgres"))
        assertEquals(listOf("1"), cyphers(listOf(Hold("x#1", on = listOf("sqlite"), steps = listOf(3), skip = true)), "sqlite"))
        assertEquals(emptyList(), cyphers(listOf(Hold("x#1", on = listOf("sql"), skip = true)), "sqlite"))
        // Not on another store, and not a hold that runs the step.
        assertEquals(listOf("1", "3", "4"), cyphers(listOf(Hold("x#1", on = listOf("oracle"), steps = listOf(3), skip = true)), "sqlite"))
        assertEquals(listOf("1", "3", "4"), cyphers(listOf(Hold("x#1", on = listOf("sqlite"), steps = listOf(3))), "sqlite"))
    }

    @Test
    fun `runnable passes over a skip of a step that doesn't run on the store, as CypherSpec's tests do`() {
        // Step 2 runs on Postgres alone: skipped on the SQL stores, it ends Postgres's case there, and not SQLite's.
        val skip = listOf(Hold("x#1", on = listOf("sql"), steps = listOf(2), skip = true))
        assertEquals(listOf("1"), cyphers(skip, "postgres"))
        assertEquals(listOf("1", "3", "4"), cyphers(skip, "sqlite"))
    }

    @Test
    fun `a run that fails fails the case, where its first step is held, and isn't taken for the step still failing`() {
        // In process, on SQLite in memory: no container.
        val spec = CypherSpec(file = "t.yaml", graph = "CREATE (:A", cases = listOf(Case("c", steps = listOf(Step(cypher = "RETURN 1", rows = listOf(1L))))))
        val test = CliSpec("sqlite", "sqlite::memory:", clears = false).tests(spec, mapOf("c" to listOf(Hold("x#1", on = listOf("sqlite"))))).single()
        val failure = assertThrows<AssertionError> { test.executable.execute() }
        assert("the case's graph wasn't written" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

    @Test
    fun `held adds the CLI's holds to the runner's, case by case`() {
        val queries = CypherSpec.load("queries.yaml")
        val held = CliSpec.held(queries)
        assertEquals(queries.held()["collect skips nulls"], held["collect skips nulls"])
        assertEquals(listOf(listOf(13)), held["A key worked out at run time"]?.map { it.steps })
        assertEquals(CypherSpec.load("writes.yaml").held(), CliSpec.held(CypherSpec.load("writes.yaml")))
    }
}
