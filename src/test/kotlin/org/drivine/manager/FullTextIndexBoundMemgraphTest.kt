package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
import org.drivine.session.SessionManager
import org.drivine.test.SharedMemgraph
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import sample.fulltext.ArticleNode
import kotlin.test.assertEquals

/**
 * Memgraph's `text_search` yields 1,000 hits unless its `CALL` is given a limit, so the index limit a
 * search asks for has to reach the `CALL` there, not only the cut after it.
 */
class FullTextIndexBoundMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var gom: GraphObjectManager

        private const val MATCHING = 1_100

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-fulltext-bound", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            val mapper = Neo4jObjectMapper.instance
            gom = GraphObjectManager(pm, SessionManager(mapper), mapper, registry)

            pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
            pm.execute(QuerySpecification.withStatement("MATCH (n:Article) DETACH DELETE n"))
            pm.execute(
                QuerySpecification.withStatement(
                    "UNWIND range(1, \$n) AS i CREATE (:Article {id: 'z' + toString(i), title: 't', body: 'zebra ' + toString(i)})"
                ).bind(mapOf("n" to MATCHING))
            )
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `a search asking for more than a thousand hits gets them`() {
        assertEquals(MATCHING, gom.loadMatching<ArticleNode>("zebra", topK = MATCHING).size)
    }
}
