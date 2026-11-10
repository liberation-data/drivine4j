package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.FullTextIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import sample.fulltext.ArticleNode
import kotlin.test.assertTrue

/**
 * How closely a SQL store's full-text ranking ([StatelessGraphObjectManager.loadMatching]) agrees with Neo4j's, whose
 * full-text index is Lucene's and scores by BM25 (drivine4j#604). Over a fixed corpus, the chunks of Drivine's own docs at
 * 3a8b0b12 (the Cypher spec's `search/docs-corpus.json`, synced into `cypher/search/`, each section a node), each query's top hit is compared with Neo4j's, and the top
 * five as sets. Scored by raw term counts, a store let common words outweigh the word a query is about, and long texts win
 * by holding more of everything.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FullTextRankingAgreementContract {

    /** The manager of the store whose ranking is measured, over a graph of its own. */
    abstract val pm: NonTransactionalPersistenceManager

    /** Whether the store's full-text index is made before the search: on Postgres, it narrows what is read, and the score is the same. */
    open val indexed = false

    /** Writes the corpus to the store: through compiled Cypher on a SQL store. */
    abstract fun write(cypher: String, parameters: Map<String, Any?>)

    /** Each query's top [TOP] hits on the store, by id, best first. */
    private fun ranked(manager: StatelessGraphObjectManager): Map<String, List<String>> =
        QUERIES.associateWith { query -> manager.loadMatching(ArticleNode::class.java, query, TOP, 0.0).map { it.value.id } }

    @Test
    fun `the store's top hits agree with Neo4j's as BM25 ranks them`() {
        write("UNWIND \$rows AS r CREATE (:Article {id: r.id, title: r.title, body: r.body})", mapOf("rows" to CORPUS))
        if (indexed) pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
        val store = ranked(StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()))
        val neo4j = Neo4jRanking.hits
        val asked = QUERIES.filter { neo4j.getValue(it).isNotEmpty() }
        val sameTop = asked.count { store.getValue(it).firstOrNull() == neo4j.getValue(it).first() }
        val overlap = asked.map { (store.getValue(it).toSet() intersect neo4j.getValue(it).toSet()).size }.average()
        println("${this::class.simpleName}: same top hit $sameTop/${asked.size}, mean overlap of the top $TOP ${"%.2f".format(overlap)} of $TOP")
        asked.forEach { println("  $it\n    neo4j: ${neo4j.getValue(it)}\n    store: ${store.getValue(it)}") }
        assertTrue(sameTop >= asked.size * 3 / 4, "Same top hit as Neo4j for $sameTop of ${asked.size} queries")
        assertTrue(overlap >= 3.5, "A mean of ${"%.2f".format(overlap)} of the top $TOP in common with Neo4j")
    }

    companion object {
        const val TOP = 5

        /** The corpus: a section of Drivine's docs to a node, `{id, title, body}`. */
        val CORPUS: List<Map<String, Any?>> by lazy {
            @Suppress("UNCHECKED_CAST")
            Neo4jObjectMapper.instance.readValue(
                FullTextRankingAgreementContract::class.java.getResource("/cypher/search/docs-corpus.json")!!, List::class.java,
            ) as List<Map<String, Any?>>
        }

        /** Queries a reader of the docs might ask, from a rare exact term to a few common words; and #604's own. */
        val QUERIES = listOf(
            "GraphObjectManager", "agent process persistence", "Jinja", "full-text search", "vector index", "transaction rollback",
            "optimistic locking stamp", "schema marker", "cascade delete", "Cypher on a relational database", "polymorphic subtype",
            "connection provider", "Spring Boot configuration", "query DSL", "Memgraph", "pgvector", "keyset pagination",
            "property bag", "relationship direction", "Oracle Text", "test containers", "save all atomically",
        )
    }
}

/** Each query's top hits on Neo4j, over the same corpus, through its full-text index: the ranking the stores are held to. */
private object Neo4jRanking {
    val hits: Map<String, List<String>> by lazy {
        SharedNeo4j.forClass()
        SharedNeo4j.withManager { pm ->
            pm.execute(QuerySpecification.withStatement("MATCH (n:Article) DETACH DELETE n"))
            pm.execute(
                QuerySpecification.withStatement("UNWIND \$rows AS r CREATE (:Article {id: r.id, title: r.title, body: r.body})")
                    .bind(mapOf("rows" to FullTextRankingAgreementContract.CORPUS)),
            )
            pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
            val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
            FullTextRankingAgreementContract.QUERIES.associateWith { query ->
                manager.loadMatching(ArticleNode::class.java, query, FullTextRankingAgreementContract.TOP, 0.0).map { it.value.id }
            }
        }
    }
}

@PostgresTests
class PostgresFullTextRankingAgreementTest : FullTextRankingAgreementContract() {
    override val pm by lazy { PostgresTestDatabase.graphManager("ftrank").also(PostgresTestDatabase::clear) }
    override fun write(cypher: String, parameters: Map<String, Any?>) {
        PostgresTestDatabase.fixtures(pm).query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    }
}

@PostgresTests
class PostgresIndexedFullTextRankingAgreementTest : FullTextRankingAgreementContract() {
    override val indexed = true
    override val pm by lazy { PostgresTestDatabase.graphManager("ftranki").also(PostgresTestDatabase::clear) }
    override fun write(cypher: String, parameters: Map<String, Any?>) {
        PostgresTestDatabase.fixtures(pm).query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    }
}

class SqliteFullTextRankingAgreementTest : FullTextRankingAgreementContract() {
    override val pm by lazy { SqliteTestDatabase.graphManager("ftrank").also { org.drivine.test.SqlCypherFixtures.clear(it) } }
    override fun write(cypher: String, parameters: Map<String, Any?>) {
        SqliteTestDatabase.fixtures(pm).query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    }
}

/** Oracle without a text index: Oracle Text, once one is made, scores by its own measure. */
@OracleTests
class OracleFullTextRankingAgreementTest : FullTextRankingAgreementContract() {
    override val pm by lazy { OracleTestDatabase.graphManager("ftrank").also(OracleTestDatabase::clear) }
    override fun write(cypher: String, parameters: Map<String, Any?>) {
        OracleTestDatabase.fixtures(pm).query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    }
}
