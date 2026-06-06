package org.drivine.query

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared `@GraphPath` assertions exercised against Memgraph and FalkorDB via raw providers —
 * the CALL-subquery path projection (dedup + null-guard) and required-path filtering must hold
 * on every engine, not just Neo4j.
 */
private fun verifyPaths(gom: GraphObjectManager) {
    // a1: two movies, one director → dedup to [Wachowski]
    val a1 = gom.load("a1", ActorDirectors::class.java)!!
    assertEquals(listOf("Wachowski"), a1.directors.map { it.name })
    // a2: movie with no director → empty
    assertTrue(gom.load("a2", ActorDirectors::class.java)!!.directors.isEmpty())
    // required path filters a2 out
    val ids = gom.loadAll(ActorRequiredDirector::class.java).map { it.actor.id }.toSet()
    assertEquals(setOf("a1"), ids)
}

private fun verifyAggregates(gom: GraphObjectManager) {
    // a1 acted in m1+m2 (count 2); RATED both with scores 1.0 + 3.0 → avg 2.0, sum 4.0
    val stats = gom.load("a1", ActorStats::class.java)!!
    assertEquals(2L, stats.movieCount)
    assertEquals(2.0, stats.avgScore)
    assertEquals(4.0, stats.totalScore)
}

private const val SEED = """
    CREATE (a1:PActor {id: 'a1', name: 'Keanu'})
    CREATE (m1:PMovie {id: 'm1', title: 'Matrix', score: 1.0})
    CREATE (m2:PMovie {id: 'm2', title: 'Reloaded', score: 3.0})
    CREATE (d1:PDirector {id: 'd1', name: 'Wachowski'})
    CREATE (a1)-[:ACTED_IN]->(m1) CREATE (a1)-[:ACTED_IN]->(m2)
    CREATE (a1)-[:RATED]->(m1) CREATE (a1)-[:RATED]->(m2)
    CREATE (m1)-[:DIRECTED_BY]->(d1) CREATE (m2)-[:DIRECTED_BY]->(d1)
    CREATE (a2:PActor {id: 'a2', name: 'Nobody'})
    CREATE (m3:PMovie {id: 'm3', title: 'Indie'})
    CREATE (a2)-[:ACTED_IN]->(m3)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class GraphPathMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: GraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-path", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
    }

    @Test
    fun `paths and aggregates work on Memgraph`() { verifyPaths(gom); verifyAggregates(gom) }
}

@Testcontainers
class GraphPathFalkorDbTest {
    companion object {
        private const val GRAPH = "pathtest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: GraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-path", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
    }

    @Test
    fun `paths and aggregates work on FalkorDB`() { verifyPaths(gom); verifyAggregates(gom) }
}