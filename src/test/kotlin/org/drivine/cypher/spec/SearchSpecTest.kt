package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.cypher.spec.SearchSpec.Case
import org.drivine.cypher.spec.SearchSpec.Hold
import org.drivine.cypher.spec.SearchSpec.Where
import org.drivine.cypher.spec.SearchSpec.Search
import org.drivine.cypher.spec.SearchSpec.Ranking.Bar
import org.drivine.cypher.spec.SearchSpec.Step
import org.drivine.manager.SearchSpecContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The search spec's runner, with no store: a held step must still fail, by an assertion or as the store fails; a hold
 * must name a case, steps and stores the file has, and HELD hold just what `today` says; a key, store or bar the runner
 * doesn't know fails at once; `ids` is a set and `ranked` an order; and the ranking's measure and bars.
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
    fun `a hold applies only where its where says, and elsewhere the step is checked and must pass`() {
        fun heldWhere(applies: Boolean) = mapOf("a case" to listOf(Hold("liberation-data/drivine4j#1", listOf("sql"), listOf(1), Where("here") { applies })))
        run(spec(step), "postgres", heldWhere(applies = false)) { listOf("a", "b") }
        assertThrows<AssertionError> { run(spec(step), "postgres", heldWhere(applies = false)) { listOf("a") } }
        run(spec(step), "postgres", heldWhere(applies = true)) { listOf("a") }
        val passing = assertThrows<AssertionError> { run(spec(step), "postgres", heldWhere(applies = true)) { listOf("a", "b") } }
        assertContains(passing.message!!, "step 1 (liberation-data/drivine4j#1, here) held on postgres, and passing")
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
        val ids = SearchSpec.corpus.map { it["id"] }.toSet()
        ranking.queries.flatMap { it.neo4j }.forEach { assertContains(ids, it) }
    }

    @Test
    fun `a bar for neo4j holds it, and a bar naming no store, or a second bar for a store, fails at once`() {
        // The spec holds Neo4j to a bar of its own since v0.41.0: left out here, so this one is the only one for neo4j.
        val ranking = SearchSpec.spec.ranking.let { it.copy(bars = it.bars.filterNot { bar -> "neo4j" in bar.stores }) }
        val neo4j = Bar(listOf("neo4j"), 21, 4.95)
        assertSame(neo4j, ranking.copy(bars = ranking.bars + neo4j).bar("neo4j"))
        assertContains(assertThrows<IllegalArgumentException> { Bar(listOf("neptune"), 1, 1.0) }.message!!, "[neptune]")
        val twice = assertThrows<IllegalArgumentException> { ranking.copy(bars = ranking.bars + Bar(listOf("sql"), 1, 1.0)) }
        assertContains(twice.message!!, "more than one bar")
    }

    @Test
    fun `a key the runner doesn't know, or a store or group no step can run on, fails at once`() {
        assertThrows<UnrecognizedPropertyException> {
            SearchSpec.yaml.readValue<Step>("{search: {label: Doc, properties: [body], query: kiwi, topK: 50}, ids: [], unordered: true}")
        }
        assertContains(assertThrows<IllegalArgumentException> { step.copy(only = listOf("oracletext")) }.message!!, "[oracletext]")
        assertContains(assertThrows<IllegalArgumentException> { step.copy(except = listOf("neptune")) }.message!!, "[neptune]")
    }

    /** Each store a step's `today` names, by case and step, and the issue, as `owner/repo#n`. */
    private fun held(case: Case, index: Int, stores: List<String>, issue: String) =
        SearchSpec.STORES.filter { SearchSpec.named(stores, it) && case.steps[index].runsOn(it) }
            .map { listOf(case.name, index + 1, it, Regex("""[\w-]+/[\w-]+#\d+""").find(issue)?.value ?: issue) }

    @Test
    fun `HELD holds each step on each store its today names, for the same issue, and no other`() {
        val cases = SearchSpec.spec.cases.associateBy { it.name }
        val today = cases.values.flatMap { case ->
            case.steps.withIndex().flatMap { (index, step) ->
                (step.today as List<*>?).orEmpty().map { it as Map<*, *> }.flatMap {
                    @Suppress("UNCHECKED_CAST")
                    held(case, index, it["stores"] as List<String>, (it["issue"] as String?) ?: "unfiled")
                }
            }
        }
        val holds = SearchSpec.HELD.flatMap { (name, holds) ->
            val case = cases.getValue(name)
            holds.flatMap { hold -> (hold.steps ?: (1..case.steps.size).toList()).flatMap { held(case, it - 1, hold.on, hold.issue) } }
        }
        assertEquals(today.sortedBy { it.toString() }, holds.sortedBy { it.toString() })
    }

    @Test
    fun `each search of the file has a model class to load`() {
        val spec = SearchSpec.spec
        (spec.cases.flatMap { case -> case.steps.map { it.search.label to it.search.properties } } + (spec.ranking.search.label to spec.ranking.search.properties))
            .forEach { assertContains(SearchSpecContract.MODELS.keys, it) }
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
