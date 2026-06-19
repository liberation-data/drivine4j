package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import sample.proposition.count
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `limit` / `skip` in the query DSL, verified on Neo4j, FalkorDB, and Memgraph. Proves the
 * **GraphView root-cardinality** decision: `limit(n)` bounds *root entities* (each with its to-many
 * relationship fully populated), not post-expansion rows; that pagination is disjoint; the edge
 * cases; and that `count` ignores `limit`. SKIP/LIMIT are bound as `$_skip`/`$_limit`.
 *
 * Data: p1..p5 with level 1..5; each has one mention, p5 has two.
 */
private fun verify(gom: GraphObjectManager) {
    fun load(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit) =
        gom.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec)
    fun ids(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit) = load(spec).map { it.proposition.id }

    // top-N by level desc
    assertEquals(listOf("p5", "p4"), ids { orderBy { query.proposition.level.desc() }; limit(2) })

    // GraphView cardinality: the single limited root keeps ALL its relationships (p5 has 2 mentions).
    val top = load { orderBy { query.proposition.level.desc() }; limit(1) }.single()
    assertEquals("p5", top.proposition.id)
    assertEquals(2, top.mentions.size, "limited view must keep its full collection")

    // pagination: disjoint pages covering the top of the order
    val page1 = ids { orderBy { query.proposition.level.desc() }; limit(2) }
    val page2 = ids { orderBy { query.proposition.level.desc() }; skip(2); limit(2) }
    assertEquals(listOf("p5", "p4"), page1)
    assertEquals(listOf("p3", "p2"), page2)
    assertTrue((page1.toSet() intersect page2.toSet()).isEmpty())

    // edge cases
    assertTrue(load { limit(0) }.isEmpty(), "limit(0) -> empty")
    assertTrue(load { skip(100) }.isEmpty(), "skip past the end -> empty")
    assertEquals(3, load { limit(3) }.size, "limit without orderBy -> <= n")

    // count ignores limit/skip in the same spec
    assertEquals(5L, gom.count(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) { limit(2) })

    // reified count<T>() resolves and delegates to the ::class.java form
    assertEquals(gom.count(PropositionView::class.java), gom.count<PropositionView>())

    // generated-form count<T> { } injects INSTANCE and delegates correctly
    assertEquals(5L, gom.count<PropositionView> { })
    assertEquals(1L, gom.count<PropositionView> { where { query.proposition.id eq "p3" } })
}

private const val SEED = """
    CREATE (p1:Proposition {id: 'p1', contextId: 'c', status: 'active', level: 1})
    CREATE (p2:Proposition {id: 'p2', contextId: 'c', status: 'active', level: 2})
    CREATE (p3:Proposition {id: 'p3', contextId: 'c', status: 'active', level: 3})
    CREATE (p4:Proposition {id: 'p4', contextId: 'c', status: 'active', level: 4})
    CREATE (p5:Proposition {id: 'p5', contextId: 'c', status: 'active', level: 5})
    CREATE (m1:Mention {id: 'm1', resolvedId: 'e1', role: 'S'})
    CREATE (m2:Mention {id: 'm2', resolvedId: 'e2', role: 'S'})
    CREATE (m3:Mention {id: 'm3', resolvedId: 'e3', role: 'S'})
    CREATE (m4:Mention {id: 'm4', resolvedId: 'e4', role: 'S'})
    CREATE (m5a:Mention {id: 'm5a', resolvedId: 'e5a', role: 'S'})
    CREATE (m5b:Mention {id: 'm5b', resolvedId: 'e5b', role: 'O'})
    CREATE (p1)-[:HAS_MENTION]->(m1)
    CREATE (p2)-[:HAS_MENTION]->(m2)
    CREATE (p3)-[:HAS_MENTION]->(m3)
    CREATE (p4)-[:HAS_MENTION]->(m4)
    CREATE (p5)-[:HAS_MENTION]->(m5a)
    CREATE (p5)-[:HAS_MENTION]->(m5b)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class LimitSkipNeo4jTest {
    companion object {
        private const val PASSWORD = "limitskiptest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-limit", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
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
    fun `limit and skip on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()))
}

@Testcontainers
class LimitSkipFalkorDbTest {
    companion object {
        private const val GRAPH = "limittest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-limit", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
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
    fun `limit and skip on FalkorDB`() = verify(buildGom(pm, SubtypeRegistry()))
}

@Testcontainers
class LimitSkipMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-limit", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
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
    fun `limit and skip on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()))
}
