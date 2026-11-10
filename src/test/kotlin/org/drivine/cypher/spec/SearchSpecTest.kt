package org.drivine.cypher.spec

import org.drivine.cypher.spec.SearchSpec.Case
import org.drivine.cypher.spec.SearchSpec.Hold
import org.drivine.cypher.spec.SearchSpec.Search
import org.drivine.cypher.spec.SearchSpec.Step
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The search spec's runner, with no store: a held step must still fail, by an assertion or as the store fails; a hold
 * must name a case, steps and stores the file has; `ids` is a set and `ranked` an order; and the ranking's measure.
 */
class SearchSpecTest {

    private val search = Search("Doc", listOf("body"), "kiwi", 50)

    private fun spec(vararg steps: Step) = SearchSpec.spec.copy(cases = listOf(Case("a case", steps = steps.toList())))

    private fun run(spec: SearchSpec, store: String, held: Map<String, List<Hold>> = emptyMap(), hits: (Search) -> List<String>) =
        spec.tests(store, before = {}, held = held, run = hits).single().executable.execute()

    private val step = Step(search, ids = listOf("a", "b"))
    private val heldOnSql = mapOf("a case" to listOf(Hold("liberation-data/drivine4j#1", listOf("sql"), listOf(1))))

    @Test
    fun `ids are a set, and ranked an order`() {
        run(spec(step), "neo4j") { listOf("b", "a") }
        assertThrows<AssertionError> { run(spec(step), "neo4j") { listOf("a") } }
        val ranked = Step(search, ranked = listOf("a", "b"))
        run(spec(ranked), "neo4j") { listOf("a", "b") }
        assertThrows<AssertionError> { run(spec(ranked), "neo4j") { listOf("b", "a") } }
    }

    @Test
    fun `an error step must fail with its message`() {
        val refused = Step(search, error = "Refused")
        run(spec(refused), "neo4j") { throw IllegalArgumentException("Refused") }
        assertThrows<AssertionError> { run(spec(refused), "neo4j") { throw IllegalArgumentException("Other") } }
        assertThrows<AssertionError> { run(spec(refused), "neo4j") { emptyList() } }
    }

    @Test
    fun `a held step that passes fails the case, naming the step and its issue`() {
        val failure = assertThrows<AssertionError> { run(spec(step), "oracle-text", heldOnSql) { listOf("a", "b") } }
        assertContains(failure.message!!, "a case: step 1 (liberation-data/drivine4j#1) held on oracle-text, and passing")
    }

    @Test
    fun `a held step that gives other hits, or that the store fails, is held`() {
        run(spec(step), "postgres", heldOnSql) { listOf("a") }
        run(spec(step), "oracle", heldOnSql) { throw IllegalStateException("ORA-29902") }
        run(spec(Step(search, error = "Refused")), "sqlite", heldOnSql) { listOf("a") }
    }

    @Test
    fun `a step is held only on the stores its hold names, and runs only on those only and except leave`() {
        assertThrows<AssertionError> { run(spec(step), "neo4j", heldOnSql) { listOf("a") } }
        run(spec(step.copy(except = listOf("graph"))), "memgraph") { error("not run") }
        run(spec(step.copy(only = listOf("oracle-text"))), "oracle") { error("not run") }
    }

    @Test
    fun `a hold naming a step, a case or a store the file has not fails at once`() {
        val past = assertThrows<IllegalArgumentException> {
            spec(step).tests("postgres", before = {}, held = mapOf("a case" to listOf(Hold("#1", listOf("sql"), listOf(2))))) { emptyList() }
        }
        assertContains(past.message!!, "a case has no step [2] to hold: it has 1")
        val missing = assertThrows<IllegalArgumentException> {
            spec(step).tests("postgres", before = {}, held = mapOf("another case" to listOf(Hold("#1", listOf("sql"))))) { emptyList() }
        }
        assertContains(missing.message!!, "search.yaml has no case \"another case\" to hold")
        val store = assertThrows<IllegalArgumentException> {
            spec(step).tests("postgres", before = {}, held = mapOf("a case" to listOf(Hold("#1", listOf("oracletext"))))) { emptyList() }
        }
        assertContains(store.message!!, "a hold names [oracletext]")
    }

    @Test
    fun `a step gives one of ids, ranked and error`() {
        assertThrows<IllegalArgumentException> { Step(search) }
        assertThrows<IllegalArgumentException> { Step(search, ids = emptyList(), error = "Refused") }
    }

    @Test
    fun `the holds here name cases, steps and stores the spec has`() {
        assertDoesNotThrow { SearchSpec.spec.checkHolds(SearchSpec.HELD) }
    }

    @Test
    fun `each store a search runs on but neo4j has one bar, and the corpus has a node for each id the ranking names`() {
        val ranking = SearchSpec.spec.ranking
        (SearchSpec.STORES - "neo4j").forEach { assertNotNull(ranking.bar(it), it) }
        assertNull(ranking.bar("neo4j"))
        val ids = SearchSpec.corpus.map { it["id"] }.toSet()
        ranking.queries.flatMap { it.neo4j }.forEach { assertContains(ids, it) }
    }

    @Test
    fun `the ranking counts the same top hit and the mean overlap over the queries Neo4j gives hits for`() {
        val neo4j = SearchSpec.spec.ranking.queries.associate { it.query to it.neo4j }
        val hits = neo4j.values.filter { it.isNotEmpty() }
        val asked = hits.size
        // Neo4j's own ranking agrees with itself: every top hit, and each query's every hit, of five or fewer.
        assertEquals(SearchSpec.Agreement(asked, hits.map { it.size }.average(), asked), SearchSpec.spec.ranking.agreement(neo4j))
        val reversed = SearchSpec.spec.ranking.agreement(neo4j.mapValues { it.value.reversed() })
        assertEquals(0, reversed.sameTop)
        val none = SearchSpec.spec.ranking.agreement(neo4j.mapValues { emptyList() })
        assertEquals(SearchSpec.Agreement(0, 0.0, asked), none)
    }
}
