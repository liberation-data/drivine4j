package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DynamicTest
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Cypher spec's `cypher/search.yaml`: the nodes a full-text search (`loadMatching`) finds on each store, and how closely
 * each store ranks a corpus's nodes as Neo4j's full-text index does. The three Drivine libraries run the same file; the
 * README's "Full-text search" describes it. A step's `today` and `note`, and a bar's `target`, `measured` and `note`, are
 * not read: what `today` records is held here, in [HELD].
 */
data class SearchSpec(
    /** The Cypher that writes the graph each case searches. */
    val graph: String,
    /** The full-text indexes made before the graph is written, on every store but `oracle`. */
    val indexes: List<Index>,
    val cases: List<Case>,
    val ranking: Ranking,
) {
    data class Index(val kind: String, val label: String, val properties: List<String>) {
        init {
            require(kind == "FULLTEXT_INDEX") { "search.yaml makes full-text indexes alone, not $kind" }
        }
    }

    /** A search: a label, the properties of its full-text index, a query as a user writes it, and the most hits it gives. */
    data class Search(val label: String, val properties: List<String>, val query: String, val topK: Int)

    data class Case(val name: String, val description: String? = null, val note: String? = null, val steps: List<Step>)

    data class Step(
        val search: Search,
        /** The hits, by `id`, in any order. */
        val ids: List<String>? = null,
        /** The hits, by `id`, best first. */
        val ranked: List<String>? = null,
        /** The message the search is refused with. */
        val error: String? = null,
        /** The stores or groups the step is run on, or that it is not. */
        val only: List<String>? = null,
        val except: List<String> = emptyList(),
        /** Not read: what a store gives today, which [HELD] holds. */
        val today: Any? = null,
        val note: String? = null,
    ) {
        init {
            require(listOfNotNull(ids, ranked, error).size == 1) { "A step of search.yaml gives one of ids, ranked and error: $search" }
            requireKnown(only.orEmpty() + except) { "A step of search.yaml names $it, no store or group: $search" }
        }

        fun runsOn(store: String) = (only == null || named(only, store)) && !named(except, store)
    }

    /** Neo4j's own ranking of a corpus, and the bars each other store's must reach. */
    data class Ranking(
        /** The corpus, beside search.yaml: a node each, `{id, title, body}`. */
        val corpus: String,
        /** The Cypher that writes the corpus, bound as `$rows`. */
        val write: String,
        val index: Index,
        val search: Searched,
        /** The hits each query asks for. */
        val top: Int,
        /** Not read: how the `neo4j` lists were recorded. */
        val recorded: Any? = null,
        val bars: List<Bar>,
        val queries: List<Query>,
    ) {
        data class Searched(val label: String, val properties: List<String>)

        /**
         * Over the queries Neo4j gives any hit for, at least [sameTop] of them have Neo4j's top hit first, and the mean
         * of how many of the top five are in Neo4j's top five, as sets, is at least [overlap].
         */
        data class Bar(
            val stores: List<String>,
            val sameTop: Int,
            val overlap: Double,
            val target: Any? = null,
            val measured: Any? = null,
            val note: String? = null,
        ) {
            init {
                requireKnown(stores) { "A bar of search.yaml names $it, no store or group" }
            }
        }

        init {
            STORES.forEach { store ->
                require(bars.count { named(it.stores, store) } <= 1) { "search.yaml holds $store's ranking to more than one bar" }
            }
        }

        data class Query(val query: String, val neo4j: List<String>)

        /** The bar [store] is held to, if one is: `neo4j` too, where the file gives it one. */
        fun bar(store: String): Bar? = bars.singleOrNull { bar -> named(bar.stores, store) }

        /** How closely [ranked], each query's best [top] hits on a store, agrees with Neo4j's. */
        fun agreement(ranked: Map<String, List<String>>): Agreement {
            val asked = queries.filter { it.neo4j.isNotEmpty() }
            val sameTop = asked.count { ranked.getValue(it.query).firstOrNull() == it.neo4j.first() }
            val overlap = asked.map { (ranked.getValue(it.query).take(top).toSet() intersect it.neo4j.toSet()).size }.average()
            return Agreement(sameTop, overlap, asked.size)
        }
    }

    data class Agreement(val sameTop: Int, val overlap: Double, val asked: Int) {
        override fun toString() = "the same top hit as Neo4j for $sameTop of $asked queries, and a mean of ${"%.3f".format(overlap)} of the top five in common"
    }

    /**
     * Steps held for [issue] on the stores or groups [on]: [steps] (from 1), or every step of the case where null. Each is
     * run, and must fail as the store fails today; one that passes fails its case, so that the hold is taken out when the
     * issue's fix lands.
     */
    internal data class Hold(val issue: String, val on: List<String>, val steps: List<Int>? = null, val where: Where? = null)

    /**
     * Where a hold applies, when a store fails a step on one platform only: elsewhere the step is checked as any other is,
     * and must pass. [described] says where, for the reader of [HELD].
     */
    internal data class Where(val described: String, val applies: () -> Boolean)

    /**
     * A test for each case, run on [store]: [before] readies the graph, and [run] gives a search's hits, by id, best first,
     * or fails as the store fails. A step not run on [store] is passed over; one held there ([held], by case name) is
     * checked to fail, by an assertion or as the store fails, not as the runner crashes. A hold naming a case, a step or a
     * store the file does not have fails at once, so that a typo or a renamed case holds nothing unseen.
     */
    internal fun tests(
        store: String,
        before: () -> Unit,
        held: Map<String, List<Hold>> = HELD,
        run: (Search) -> List<String>,
    ): List<DynamicTest> {
        require(store in STORES) { "search.yaml has no store $store: it has $STORES" }
        checkHolds(held)
        return cases.map { case -> test(case, store, before, held[case.name].orEmpty(), run) }
    }

    /** Each hold names a case and steps the file has, and stores or groups it runs on. */
    internal fun checkHolds(held: Map<String, List<Hold>>) {
        held.forEach { (name, holds) ->
            val case = requireNotNull(cases.find { it.name == name }) { "search.yaml has no case \"$name\" to hold" }
            holds.flatMap { it.steps.orEmpty() }.filter { it !in 1..case.steps.size }.let { missing ->
                require(missing.isEmpty()) { "search.yaml, $name has no step $missing to hold: it has ${case.steps.size}" }
            }
            holds.flatMap { it.on }.filter { it !in STORES && it !in GROUPS }.let { unknown ->
                require(unknown.isEmpty()) { "search.yaml, $name: a hold names $unknown, no store or group of $STORES or ${GROUPS.keys}" }
            }
        }
    }

    private fun test(case: Case, store: String, before: () -> Unit, holds: List<Hold>, run: (Search) -> List<String>) =
        DynamicTest.dynamicTest(case.name) {
            before()
            val passing = mutableListOf<String>()
            for ((index, step) in case.steps.withIndex()) {
                if (!step.runsOn(store)) continue
                val hold = holds.firstOrNull { hold ->
                    named(hold.on, store) && (hold.steps == null || index + 1 in hold.steps) && hold.where?.applies?.invoke() != false
                }
                if (hold == null) check(case, index, step, store, run)
                else if (!stillFails(case, index, step, store, run)) passing += "step ${index + 1} (${hold.issue})"
            }
            if (passing.isNotEmpty()) {
                fail("search.yaml, ${case.name}: ${passing.joinToString()} held on $store, and passing: take them out of HELD.")
            }
        }

    /**
     * Whether a held step still fails: by an assertion (hits or a message not as the spec says), or as the store fails in
     * [run]. Anything else the runner throws is thrown on, so a hold does not hide the runner's own crash.
     */
    private fun stillFails(case: Case, index: Int, step: Step, store: String, run: (Search) -> List<String>): Boolean {
        var storeFailed = false
        val failure = runCatching {
            check(case, index, step, store) { runCatching { run(it) }.onFailure { storeFailed = true }.getOrThrow() }
        }.exceptionOrNull() ?: return false
        if (failure is AssertionError || storeFailed) return true
        throw failure
    }

    private fun check(case: Case, index: Int, step: Step, store: String, run: (Search) -> List<String>) {
        val where = "${case.name}, step ${index + 1}, on $store: ${step.search}"
        if (step.error != null) {
            val failure = runCatching { run(step.search) }.fold({ hits -> fail("Expected a failure, but gave $hits: $where") }, { it })
            assertEquals(step.error, failure.message, where)
            return
        }
        val hits = run(step.search)
        if (step.ranked != null) assertEquals(step.ranked, hits, where)
        else assertEquals(step.ids!!.sorted(), hits.sorted(), where)
    }

    companion object {
        /** The stores a search runs on: `oracle` is Oracle with no full-text index made, `oracle-text` with it made. */
        val STORES = setOf("neo4j", "memgraph", "falkordb", "postgres", "oracle", "oracle-text", "sqlite")

        /** The groups a step may name for its stores: the graph engines (Neptune has no full-text search), and the SQL stores. */
        val GROUPS = mapOf(
            "graph" to setOf("neo4j", "memgraph", "falkordb"),
            "sql" to setOf("postgres", "oracle", "oracle-text", "sqlite"),
        )

        internal fun named(names: List<String>, store: String) = names.any { it == store || GROUPS[it]?.contains(store) == true }

        /** Fails with [message] where [names] has one that is no store or group, so that a typo or a new store is not passed over. */
        private fun requireKnown(names: List<String>, message: (List<String>) -> String) {
            val unknown = names.filter { it !in STORES && it !in GROUPS }
            require(unknown.isEmpty()) { message(unknown) }
        }

        private const val GRAPH_ENGINES = "liberation-data/drivine-cypher-spec#100: the graph engines are given the query as it is"
        private const val FALKORDB = "liberation-data/drivine4j#666"
        private const val MEMGRAPH = "liberation-data/drivine4j#762"
        /** Where Docker runs arm64 builds of the stores' images: the JVM's own architecture, as Docker runs the host's. */
        private val ARM64 = Where("on arm64") { System.getProperty("os.arch") in setOf("aarch64", "arm64") }
        private const val ORACLE_TEXT = "liberation-data/drivine4j#763"

        /**
         * The steps search.yaml's `today` says a store fails, by case: the steps (from 1), the stores or groups they are held
         * on, and the issue that makes them pass. The runner doesn't read `today`; SearchSpecTest checks that these hold
         * just the steps and stores it names, for its issues.
         */
        internal val HELD: Map<String, List<Hold>> = mapOf(
            "Words are ORed" to listOf(Hold(FALKORDB, listOf("falkordb"), listOf(1, 2))),
            "OR" to listOf(Hold(FALKORDB, listOf("falkordb"), listOf(1, 2))),
            "A star inside quotes" to listOf(Hold(FALKORDB, listOf("falkordb"), listOf(1))),
            "A required word" to listOf(Hold(MEMGRAPH, listOf("memgraph"), listOf(1)), Hold(FALKORDB, listOf("falkordb"), listOf(1))),
            "NOT" to listOf(Hold(FALKORDB, listOf("falkordb"), listOf(1, 2)), Hold(MEMGRAPH, listOf("memgraph"), listOf(2))),
            "AND" to listOf(
                Hold("liberation-data/drivine4j#760", listOf("sql"), listOf(1, 2, 3)),
                Hold(FALKORDB, listOf("falkordb"), listOf(1, 2)),
                Hold(MEMGRAPH, listOf("memgraph"), listOf(3)),
            ),
            "A prefix" to listOf(Hold(MEMGRAPH, listOf("memgraph"), listOf(1, 2))),
            "A word inside a longer word" to listOf(
                Hold(FALKORDB, listOf("falkordb"), listOf(1)),
                Hold(MEMGRAPH, listOf("memgraph"), listOf(2)),
                Hold(ORACLE_TEXT, listOf("oracle-text"), listOf(2)),
            ),
            "Unicode letters and digits" to listOf(Hold(ORACLE_TEXT, listOf("oracle-text"), listOf(2))),
            "Punctuation between words" to listOf(Hold(FALKORDB, listOf("falkordb"), listOf(1, 2))),
            "A field" to listOf(
                Hold(MEMGRAPH, listOf("memgraph"), listOf(1, 2, 3, 4)),
                Hold(FALKORDB, listOf("falkordb"), listOf(1, 2, 3, 4)),
                Hold("liberation-data/drivine4j#761", listOf("sql"), listOf(2, 3, 4)),
            ),
            // Memgraph 3.14.0's arm64 build searches only the first property of an index over several; its amd64 build, CI's,
            // searches them all, as Neo4j does (#762). The box and the laptop run arm64, where Docker runs the host's build.
            "Several properties" to listOf(Hold(MEMGRAPH, listOf("memgraph"), listOf(1, 2, 3), ARM64), Hold(FALKORDB, listOf("falkordb"), listOf(3))),
            "Escapes" to listOf(
                Hold(FALKORDB, listOf("falkordb"), listOf(1, 2, 3, 5, 6)),
                Hold("liberation-data/drivine4j#765", listOf("sql", "memgraph"), listOf(6)),
            ),
            "No word to look for" to listOf(Hold(GRAPH_ENGINES, listOf("graph"))),
            "An empty query" to listOf(Hold(GRAPH_ENGINES, listOf("graph"))),
        )

        /** Fails on a key the runner doesn't know, so that a spec tag's new key is read, not passed over. */
        internal val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)

        val spec: SearchSpec by lazy {
            yaml.readValue(requireNotNull(SearchSpec::class.java.getResource("/cypher/search.yaml")) { "No spec file cypher/search.yaml" })
        }

        /** The ranking's corpus: a section of Drivine's docs to a node, `{id, title, body}`. */
        val corpus: List<Map<String, Any?>> by lazy {
            val file = "/cypher/${spec.ranking.corpus}"
            ObjectMapper().readValue(requireNotNull(SearchSpec::class.java.getResource(file)) { "No corpus $file" })
        }
    }
}
