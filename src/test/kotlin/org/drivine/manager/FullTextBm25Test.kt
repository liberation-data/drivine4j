package org.drivine.manager

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import sample.fulltext.ArticleNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-text search scores by BM25 on every store (drivine4j#604): Lucene's, on Neo4j, and [org.drivine.backend.sql.Bm25] on the
 * SQL stores without a text index of their own. A rare term outweighs a common one, a short text outranks a long one that
 * holds a term as often, and a term's count adds less each time it is counted again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FullTextBm25Contract {

    abstract val pm: NonTransactionalPersistenceManager

    /** Whether the full-text index is made: on Postgres, it narrows the nodes counted, and the scores are the same. */
    open val indexed = false

    /** Writes [cypher] to the store. */
    open fun write(cypher: String) {
        pm.execute(QuerySpecification.withStatement(cypher))
    }

    /** Waits for the index to hold what was written, where it fills after the write. */
    open fun settle() {}

    private val manager by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    @BeforeAll
    fun seed() {
        write(COMMON)
        write(SEED)
        write(PAPERS)
        if (indexed) {
            pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
            pm.indexes.ensure(FullTextIndexSpec("Paper", listOf("title", "body")))
        }
        settle()
    }

    private fun search(text: String) = manager.loadMatching(ArticleNode::class.java, text, 50, 0.0)

    @Test
    fun `a rare term outweighs a common one counted more often`() {
        val hits = search("graph soup")
        assertEquals("rare", hits.first().value.id, hits.toString())
        assertEquals(1.0, hits.first().score, 1e-9)
        assertTrue(hits.all { it.score in 0.0..1.0 }, hits.toString())
    }

    @Test
    fun `a short text outranks a long one that holds the term as often`() {
        val hits = search("zebra")
        assertEquals(listOf("short", "long"), hits.map { it.value.id })
        // Counted alone, the two tied.
        assertTrue(hits[0].score > hits[1].score, hits.toString())
    }

    @Test
    fun `a term counted again adds less each time`() {
        val hits = search("kiwi").associate { it.value.id to it.score }
        assertEquals(1.0, hits.getValue("often"), 1e-9)
        // Ten times the count, as long again as the other's text: not ten times the score.
        assertTrue(hits.getValue("once") > 0.4, hits.toString())
    }

    @Test
    fun `a star inside quotes is text, as Lucene's parser reads it, so a phrase ending in one is the phrase without it`() {
        // drivine4j#622: "of sou*" is the phrase "of sou", which "a graph of soup" doesn't hold, as on Neo4j.
        assertEquals(emptyList(), search("\"of sou*\"").map { it.value.id })
        assertEquals(listOf("rare"), search("\"of soup*\"").map { it.value.id })
    }

    @Test
    fun `a term every node but one holds still finds the nodes, and required and excluded terms keep their meaning`() {
        assertEquals(22, search("graph").size)
        assertEquals(listOf("rare"), search("+soup graph").map { it.value.id })
        assertEquals(setOf("short"), search("zebra -longer").map { it.value.id }.toSet())
        assertEquals(listOf("short", "long"), search("+zebra").map { it.value.id })
    }

    @Test
    fun `a node that lacks one of several searched properties is measured by the rest, and scores`() {
        val hits = manager.loadMatching(PaperNode::class.java, "kiwi", 50, 0.0)
        assertEquals(setOf("untitled", "unbodied", "both"), hits.map { it.value.id }.toSet(), hits.toString())
        assertTrue(hits.all { it.score > 0.0 }, hits.toString())
    }

    companion object {
        /** With [COMMON], "graph" is in most texts, so it says little of any; "soup" is in one. */
        val SEED = """
            CREATE (:Article {id: 'many', title: 'm', body: 'graph graph graph graph and more of the graph'})
            CREATE (:Article {id: 'rare', title: 'r', body: 'a graph of soup'})
            CREATE (:Article {id: 'short', title: 's', body: 'zebra crossing'})
            CREATE (:Article {id: 'long', title: 'l', body: 'zebra and a great many other words that make this text far longer than the one before it is'})
            CREATE (:Article {id: 'once', title: 'o', body: 'kiwi bird'})
            CREATE (:Article {id: 'often', title: 'f', body: 'kiwi kiwi kiwi kiwi kiwi kiwi kiwi kiwi kiwi kiwi'})
            CREATE (:Article {id: 'none', title: 'n', body: 'nothing to see'})
        """.trimIndent()

        /** Texts over two properties, one of them missing in some: a missing one is no text, and its length none. */
        val PAPERS = """
            CREATE (:Paper {id: 'untitled', body: 'kiwi pie'})
            CREATE (:Paper {id: 'unbodied', title: 'kiwi'})
            CREATE (:Paper {id: 'both', title: 'kiwi', body: 'kiwi kiwi'})
            CREATE (:Paper {id: 'other', title: 'nothing', body: 'to see'})
        """.trimIndent()

        /** Twenty texts that hold "graph", written apart from [SEED]: an `UNWIND` before it would make each of its nodes twenty times. */
        const val COMMON = "UNWIND range(1, 20) AS i CREATE (:Article {id: 'common' + toString(i), title: 'c', body: 'a note on a graph and its nodes'})"
    }
}

/** A text searched over two properties, either of which a node may lack. */
@FullTextIndex(properties = ["title", "body"])
@NodeFragment(labels = ["Paper"])
data class PaperNode(@NodeId val id: String, val title: String?, val body: String?)

class Neo4jFullTextBm25Test : FullTextBm25Contract() {
    private val provider by lazy {
        val container = SharedNeo4j.forClass()
        Neo4jConnectionProvider(
            name = "neo-bm25", type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
            user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j", config = emptyMap(),
            subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.NEO4J_5,
        )
    }
    override val pm by lazy { NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, SubtypeRegistry()) }
    override val indexed = true

    /** Neo4j's full-text index fills after the write. */
    override fun settle() {
        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
    }

    @AfterAll
    fun end() = provider.end()
}

@PostgresTests
class PostgresFullTextBm25Test : FullTextBm25Contract() {
    override val pm by lazy { PostgresTestDatabase.graphManager("ftbm25").also(PostgresTestDatabase::clear) }
    override fun write(cypher: String) = PostgresTestDatabase.seed(pm, cypher)
}

@PostgresTests
class PostgresIndexedFullTextBm25Test : FullTextBm25Contract() {
    override val indexed = true
    override val pm by lazy { PostgresTestDatabase.graphManager("ftbm25i").also(PostgresTestDatabase::clear) }
    override fun write(cypher: String) = PostgresTestDatabase.seed(pm, cypher)

    @Test
    fun `narrowed by the index, each node scores as it does without one`() {
        val plain = PostgresTestDatabase.graphManager("ftbm25cmp").also(PostgresTestDatabase::clear)
        PostgresTestDatabase.seed(plain, COMMON)
        PostgresTestDatabase.seed(plain, SEED)
        val bare = StatelessGraphObjectManager(plain, Neo4jObjectMapper.instance, SubtypeRegistry())
        val with = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        for (query in listOf("graph soup", "zebra", "kiwi", "graph", "+soup graph", "\"zebra crossing\" kiwi", "zebra -longer", "+\"of soup\" graph")) {
            val a = bare.loadMatching(ArticleNode::class.java, query, 50, 0.0).associate { it.value.id to it.score }
            val b = with.loadMatching(ArticleNode::class.java, query, 50, 0.0).associate { it.value.id to it.score }
            assertEquals(a.keys, b.keys, query)
            a.forEach { (id, score) -> assertEquals(score, b.getValue(id), 1e-9, "$query: $id") }
        }
    }
}

class SqliteFullTextBm25Test : FullTextBm25Contract() {
    override val pm by lazy { SqliteTestDatabase.graphManager("ftbm25").also { org.drivine.test.SqlCypherFixtures.clear(it) } }
    override fun write(cypher: String) = SqliteTestDatabase.seed(pm, cypher)
}

/** Oracle without a text index: Oracle Text, once one is made, scores by its own measure. */
@OracleTests
class OracleFullTextBm25Test : FullTextBm25Contract() {
    override val pm by lazy { OracleTestDatabase.graphManager("ftbm25").also(OracleTestDatabase::clear) }
    override fun write(cypher: String) = OracleTestDatabase.seed(OracleTestDatabase.fixtures(pm), cypher)
}
