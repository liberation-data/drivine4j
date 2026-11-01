package org.drivine.cypher.spec

import org.drivine.cypher.spec.CypherSpec.Case
import org.drivine.cypher.spec.CypherSpec.Hold
import org.drivine.cypher.spec.CypherSpec.Step
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains

/**
 * The spec runner, with no store: a held step must still fail, by an assertion or as the store fails, and a hold must
 * name a case and steps the file has; `{$float: …}` is NaN or an infinity, told apart from null.
 */
class CypherSpecTest {

    private val nan = mapOf("\$float" to "NaN")
    private val infinities = listOf(mapOf("\$float" to "Infinity"), mapOf("\$float" to "-Infinity"))

    private fun spec(vararg steps: Step) = CypherSpec(file = "test.yaml", cases = listOf(Case("a case", steps = steps.toList())))

    private fun run(spec: CypherSpec, store: String, held: Map<String, List<Hold>> = emptyMap(), rows: (Step) -> List<Any?>) =
        spec.tests(store, before = {}, held = held, run = rows).single().executable.execute()

    private val step = Step(cypher = "RETURN 1", rows = listOf(1L))
    private val heldOnSql = mapOf("a case" to listOf(Hold(1, listOf("sql"), listOf(1))))

    @Test
    fun `a held step that passes fails the case, naming the step and its issue`() {
        val failure = assertThrows<AssertionError> { run(spec(step), "postgres", heldOnSql) { listOf(1L) } }
        assertContains(failure.message!!, "test.yaml, a case: step 1 (#1) held on postgres, and passing")
    }

    @Test
    fun `a held step that gives other rows, or that the store fails, is held`() {
        run(spec(step), "postgres", heldOnSql) { listOf(2L) }
        run(spec(step), "oracle", heldOnSql) { throw IllegalStateException("ORA-00900") }
    }

    @Test
    fun `a held error step that fails otherwise is held`() {
        run(spec(Step(cypher = "RETURN 1", error = "Refused")), "postgres", heldOnSql) { throw IllegalStateException("Other") }
        run(spec(Step(cypher = "RETURN 1", error = "Refused")), "postgres", heldOnSql) { listOf(1L) }
    }

    @Test
    fun `a held step the runner crashes on is no hold`() {
        val unreadable = Step(cypher = "RETURN \$p", parameters = mapOf("p" to mapOf("\$float" to "nan?")), rows = listOf(1L))
        assertThrows<NumberFormatException> { run(spec(unreadable), "postgres", heldOnSql) { listOf(1L) } }
    }

    @Test
    fun `a step is held only on the stores its hold names`() {
        assertThrows<AssertionError> { run(spec(step), "neo4j", heldOnSql) { listOf(2L) } }
    }

    @Test
    fun `a hold naming a step or a case the file has not fails at once`() {
        val past = assertThrows<IllegalArgumentException> {
            spec(step).tests("postgres", before = {}, held = mapOf("a case" to listOf(Hold(1, listOf("sql"), listOf(1, 2))))) { listOf(1L) }
        }
        assertContains(past.message!!, "test.yaml, a case has no step [2] to hold: it has 1")
        val missing = assertThrows<IllegalArgumentException> {
            spec(step).tests("postgres", before = {}, held = mapOf("another case" to listOf(Hold(1, listOf("sql"))))) { listOf(1L) }
        }
        assertContains(missing.message!!, "test.yaml has no case \"another case\" to hold")
    }

    @Test
    fun `the holds here name cases and steps the spec has`() {
        listOf("queries.yaml", "writes.yaml", "temporal.yaml", "refusals.yaml").forEach { file ->
            assertDoesNotThrow { CypherSpec.load(file).tests("postgres", before = {}) { emptyList() } }
        }
    }

    @Test
    fun `{$float} is NaN or an infinity, in parameters and rows, and is not null`() {
        val floats = Step(cypher = "RETURN \$p", parameters = mapOf("p" to listOf(nan) + infinities), rows = listOf(listOf(nan) + infinities))
        var bound: Any? = null
        run(spec(floats), "neo4j") { bound = it.parameters["p"]; listOf(bound) }
        assertContains(listOf(listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)), bound)
        assertThrows<AssertionError> { run(spec(floats), "neo4j") { listOf(listOf(null, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) } }
        assertThrows<AssertionError> { run(spec(floats), "neo4j") { listOf(listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) } }
        assertThrows<AssertionError> { run(spec(floats), "neo4j") { listOf(listOf("NaN", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) } }
    }
}
