package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.instanceOf
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
import sample.polytree.ChunkNode
import sample.polytree.ChunkQueryDsl
import sample.polytree.ContentElementNode
import sample.polytree.ContentElementNodeQueryDsl
import sample.polytree.DocumentNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated bare-`@NodeFragment` query DSL behaviour, verified cross-engine (Neo4j / FalkorDB /
 * Memgraph): `loadAll` / `count` / `deleteAll` bound to `<Fragment>QueryDsl.INSTANCE`, a `where`
 * targeting node properties directly (parameterized, incl. a `@GraphProperty` on-disk name),
 * `instanceOf()` filtering a sealed fragment to one subtype, and polymorphic per-node dispatch.
 */
private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager) {
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:ContentElement:Document {id:'d1', title:'Doc'})
            CREATE (:ContentElement:Chunk {id:'c1', text:'A', container_section_id:'sec-1'})
            CREATE (:ContentElement:Chunk {id:'c2', text:'B', container_section_id:'sec-2'})
            """.trimIndent()
        )
    )

    // loadAll over the sealed base dispatches each node to its concrete subtype.
    val all = gom.loadAll(ContentElementNode::class.java, ContentElementNodeQueryDsl.INSTANCE) {
        where { query.id neq "none" }
    }
    assertEquals(3, all.size)
    assertTrue(all.any { it is DocumentNode } && all.count { it is ChunkNode } == 2)

    // instanceOf filters the sealed base to one subtype (renders `n:ContentElement:Chunk`).
    val chunks = gom.loadAll(ContentElementNode::class.java, ContentElementNodeQueryDsl.INSTANCE) {
        where { query.instanceOf<ChunkNode>() }
    }
    assertEquals(setOf("c1", "c2"), chunks.map { it.id }.toSet())
    assertTrue(chunks.all { it is ChunkNode })

    // A concrete-subtype DSL filters by its own property (a @GraphProperty on-disk name).
    val bySection = gom.loadAll(ChunkNode::class.java, ChunkQueryDsl.INSTANCE) {
        where { query.containerSectionId eq "sec-1" }
    }
    assertEquals(listOf("c1"), bySection.map { it.id })
    assertEquals("sec-1", (bySection.single()).containerSectionId)

    // count binds through the DSL.
    assertEquals(2, gom.count(ChunkNode::class.java, ChunkQueryDsl.INSTANCE) { where { query.id neq "none" } })

    // deleteAll binds through the DSL and removes only matching nodes.
    assertEquals(1, gom.deleteAll(ChunkNode::class.java, ChunkQueryDsl.INSTANCE) { where { query.containerSectionId eq "sec-2" } })
    assertEquals(setOf("c1"), gom.loadAll(ChunkNode::class.java, ChunkQueryDsl.INSTANCE) { where { query.id neq "none" } }.map { it.id }.toSet())
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class FragmentDslNeo4jTest {
    companion object {
        private const val PW = "fragdsltest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PW) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-fragdsl", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `bare fragment DSL on Neo4j`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class FragmentDslFalkorDbTest {
    companion object {
        private const val GRAPH = "fragdsltest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-fragdsl", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `bare fragment DSL on FalkorDB`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class FragmentDslMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-fragdsl", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `bare fragment DSL on Memgraph`() = verify(buildGom(pm, registry), pm)
}
