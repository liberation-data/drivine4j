package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.FullTextSearchLimits
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
import org.drivine.session.SessionManager
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import sample.fulltext.ArticleNode
import sample.fulltext.ArticleNodeQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full-text index call yields no more hits than the search's limit, on a real Neo4j.
 *
 * Thirty articles match `zebra`; the one under test is by far the longest, so BM25 ranks it last. A
 * filtered search for it wanting one row asks the index for [FullTextSearchLimits.FILTERED_OVER_FETCH]
 * hits, which do not reach it; wanting three asks for thirty, which do. That is the bound and the
 * trade it makes, observed through the rows rather than read off the Cypher.
 */
class FullTextIndexBoundNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var gom: GraphObjectManager

        private const val MATCHING = 30
        private const val LAST = "z-last"

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-fulltext-bound", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            val mapper = Neo4jObjectMapper.instance
            gom = GraphObjectManager(pm, SessionManager(mapper), mapper, registry)

            pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
            pm.execute(QuerySpecification.withStatement("MATCH (n:Article) DETACH DELETE n"))
            pm.execute(
                QuerySpecification.withStatement(
                    """
                    UNWIND range(1, ${'$'}short) AS i
                    CREATE (:Article {id: 'z' + i, title: 'short', body: 'zebra ' + reduce(s = '', j IN range(1, i) | s + 'grass ')})
                    CREATE (:Article {id: ${'$'}last, title: 'long', body: 'zebra ' + reduce(s = '', j IN range(1, 400) | s + 'savannah ')})
                    """.trimIndent()
                ).bind(mapOf("short" to MATCHING - 1, "last" to LAST))
            )
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private fun onlyTheLast(topK: Int) =
        gom.loadMatching(ArticleNode::class.java, ArticleNodeQueryDsl.INSTANCE, "zebra", topK) {
            where { query.id eq LAST }
        }

    @Test
    fun `a filtered search sees only the best topK times the over-fetch`() {
        assertTrue(FullTextSearchLimits.FILTERED_OVER_FETCH < MATCHING - 1)

        assertEquals(emptyList(), onlyTheLast(topK = 1).map { it.value.id })
    }

    @Test
    fun `a filtered search whose over-fetch reaches the hit finds it`() {
        assertEquals(listOf(LAST), onlyTheLast(topK = 3).map { it.value.id })
    }

    @Test
    fun `an unfiltered search returns the best topK in score order`() {
        val found = gom.loadMatching<ArticleNode>("zebra", topK = 5)

        assertEquals(5, found.size)
        assertEquals(1.0, found.first().score, 1e-9)
        assertEquals(found.map { it.score }, found.map { it.score }.sortedDescending())
        assertTrue(LAST !in found.map { it.value.id })
    }
}
