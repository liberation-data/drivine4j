package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.FullTextIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import sample.fulltext.ArticleNode
import kotlin.random.Random

/**
 * What a full-text search costs over a label of 10,000 and of 100,000 nodes, on each SQL store (drivine4j#604): BM25 reads
 * every node of the label once more, for its count of nodes and the texts' average length. Synthetic texts of 20 to 200
 * words, drawn from 5,000 words by Zipf's law, so a few words are in most texts and most in few. Run by hand, with
 * `FULLTEXT_COST_TESTS=true`: it prints the times, and asserts nothing of them.
 */
@EnabledIfEnvironmentVariable(named = "FULLTEXT_COST_TESTS", matches = "true")
class FullTextCostTest {

    private val words = (1..5000).map { "w$it" }
    private val weights = words.indices.map { 1.0 / (it + 1) }.let { w -> val total = w.sum(); w.map { it / total } }
    private val cumulative = weights.runningReduce(Double::plus)

    private fun word(random: Random): String = words[cumulative.binarySearch(random.nextDouble()).let { if (it < 0) -it - 1 else it }.coerceAtMost(words.lastIndex)]

    private fun rows(count: Int): List<Map<String, Any?>> {
        val random = Random(604)
        return (1..count).map { i -> mapOf("id" to "a$i", "title" to "t$i", "body" to (1..random.nextInt(20, 201)).joinToString(" ") { word(random) }) }
    }

    /** A common word, a rare one, both, and a phrase of two words of the middle. */
    private val queries = listOf("w1", "w3000", "w1 w3000", "w2 w40 w900", "\"w10 w11\"")

    private fun measure(store: String, pm: NonTransactionalPersistenceManager, write: (String, Map<String, Any?>) -> Unit, count: Int, indexed: Boolean) {
        rows(count).chunked(2000).forEach { write("UNWIND \$rows AS r CREATE (:Article {id: r.id, title: r.title, body: r.body})", mapOf("rows" to it)) }
        if (indexed) pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
        val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        for (query in queries) {
            manager.loadMatching(ArticleNode::class.java, query, 10, 0.0)
            val ms = (1..3).map { val start = System.nanoTime(); manager.loadMatching(ArticleNode::class.java, query, 10, 0.0); (System.nanoTime() - start) / 1e6 }.sorted()[1]
            println("COST $store${if (indexed) " indexed" else ""} n=$count q=$query: ${"%.0f".format(ms)} ms")
        }
    }

    private fun cypher(pm: NonTransactionalPersistenceManager): (String, Map<String, Any?>) -> Unit = { statement, parameters ->
        org.drivine.test.SqlCypherFixtures.over(pm).query(QuerySpecification.withStatement(CypherStatement(statement)).bind(parameters))
    }

    @Test
    fun postgres() {
        for (count in listOf(10_000, 100_000)) for (indexed in listOf(false, true)) {
            val pm = PostgresTestDatabase.graphManager("ftcost").also(PostgresTestDatabase::clear)
            measure("postgres", pm, cypher(pm), count, indexed)
        }
    }

    @Test
    fun sqlite() {
        for (count in listOf(10_000, 100_000)) {
            val pm = SqliteTestDatabase.graphManager("ftcost").also { org.drivine.test.SqlCypherFixtures.clear(it) }
            measure("sqlite", pm, cypher(pm), count, indexed = false)
        }
    }

    @Test
    fun oracle() {
        if (System.getenv("ORACLE_TESTS") != "true") return
        for (count in listOf(10_000, 100_000)) {
            val pm = OracleTestDatabase.graphManager("ftcost").also(OracleTestDatabase::clear)
            measure("oracle", pm, cypher(pm), count, indexed = false)
        }
    }

    /**
     * Where a rare word's search goes on Postgres at 100,000 nodes: whether a regular expression's literal is found without
     * running it, and what the label's count and average length cost read on their own. Run by hand, with
     * `PROBE_EXPLAIN=true`: it prints the plans.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "PROBE_EXPLAIN", matches = "true")
    fun `explain a rare word on postgres`() {
        val pm = PostgresTestDatabase.graphManager("ftcost").also(PostgresTestDatabase::clear)
        rows(100_000).chunked(2000).forEach { cypher(pm)("UNWIND \$rows AS r CREATE (:Article {id: r.id, title: r.title, body: r.body})", mapOf("rows" to it)) }
        PostgresTestDatabase.run("ANALYZE ftcost_node")
        val text = org.drivine.backend.postgres.PostgresSql.searchedText("c", listOf("body"))
        // The word's expression, as the shared module's TextQuery writes a term.
        val pattern = "(^|[^[:alnum:]_])w3000(\$|[^[:alnum:]_])"
        val probes = mapOf(
            "count each" to "SELECT count(*) FROM ftcost_node c WHERE (SELECT count(*) FROM regexp_matches($text, '$pattern', 'g')) > 0",
            "holds" to "SELECT count(*) FROM ftcost_node c WHERE $text ~ '$pattern'",
            "figures" to "SELECT count(*), avg(length($text)) FROM ftcost_node c",
            "figures unlowered" to "SELECT count(*), avg(length(c.props->>'body')) FROM ftcost_node c",
        )
        for ((name, sql) in probes) {
            val plan = PostgresTestDatabase.pm.query(QuerySpecification.withStatement("EXPLAIN (ANALYZE, BUFFERS) $sql"))
            println("PROBE $name\n" + plan.joinToString("\n") { "  $it" })
        }
    }
}
