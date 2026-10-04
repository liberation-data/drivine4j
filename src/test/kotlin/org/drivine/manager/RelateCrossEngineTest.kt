package org.drivine.manager

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.drivine.annotation.Direction
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
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
import sample.nodelabels.MemberNode
import sample.nodelabels.Role
import sample.nodelabels.ThingNode

/**
 * [EdgeOperations.relate] and [EdgeOperations.loadRelated], verified on Neo4j, FalkorDB and
 * Memgraph: a relationship of a type known only at runtime joins two stored nodes that are matched
 * and never created, is made once or every time as asked, carries properties, and is read back in
 * the direction asked for.
 */
private fun verify(gom: GraphObjectManager, pm: PersistenceManager) {
    fun count(cypher: String): Long = pm.getOne(QuerySpecification.withStatement(cypher).transform(Long::class.java))

    gom.save(ThingNode("lyre", "Lyre", setOf("Instrument")))
    gom.save(MemberNode("ada", "Ada", setOf(Role.Author)))
    val lyre = nodeRef<ThingNode>("lyre")
    val ada = nodeRef<MemberNode>("ada")

    // ----- MERGE: once, however often; the direction given; properties set, then updated -----
    assertTrue(gom.edges.relate(lyre, ada, "OWNED BY", mapOf("since" to 1990, "source-ref" to "kb", "note" to null)))
    assertTrue(gom.edges.relate(lyre, ada, "OWNED BY", mapOf("since" to 1991)))
    assertEquals(1L, count("MATCH (:Thing {id: 'lyre'})-[r:`OWNED BY`]->(:Member {id: 'ada'}) RETURN count(r)"))
    assertEquals(0L, count("MATCH (:Member {id: 'ada'})-[r:`OWNED BY`]->(:Thing {id: 'lyre'}) RETURN count(r)"))
    assertEquals(1L, count("MATCH ()-[r:`OWNED BY`]->() WHERE r.since = 1991 AND r.`source-ref` = 'kb' RETURN count(r)"))

    // ----- CREATE: another each time -----
    gom.edges.relate(lyre, ada, "PLAYED_BY", mode = RelateMode.CREATE)
    gom.edges.relate(lyre, ada, "PLAYED_BY", mode = RelateMode.CREATE)
    assertEquals(2L, count("MATCH (:Thing {id: 'lyre'})-[r:PLAYED_BY]->(:Member {id: 'ada'}) RETURN count(r)"))

    // ----- An absent node is not created, and nothing is joined -----
    assertFalse(gom.edges.relate(lyre, nodeRef<MemberNode>("nobody"), "LENT_TO"))
    assertEquals(0L, count("MATCH (n {id: 'nobody'}) RETURN count(n)"))
    assertEquals(0L, count("MATCH ()-[r:LENT_TO]->() RETURN count(r)"))

    // ----- A node lacking a label the reference names is not that node -----
    assertFalse(gom.edges.relate(nodeRef<ThingNode>("lyre", "Weapon"), ada, "LENT_TO"))
    assertTrue(gom.edges.relate(nodeRef<ThingNode>("lyre", "Instrument"), ada, "LENT_TO"))
    assertEquals(1L, count("MATCH ()-[r:LENT_TO]->() RETURN count(r)"))

    // ----- Neither end's properties or labels are touched -----
    val reloaded = gom.load("lyre", ThingNode::class.java)!!
    assertEquals("Lyre", reloaded.name)
    assertEquals(setOf("Thing", "Instrument"), reloaded.labels)

    // ----- Read back, by type and direction, as the target fragment with its labels -----
    assertEquals(listOf("ada"), gom.edges.loadRelated<MemberNode>(lyre, "OWNED BY").map { it.id })
    assertEquals(setOf(Role.Author), gom.edges.loadRelated<MemberNode>(lyre, "OWNED BY").single().roles)
    assertTrue(gom.edges.loadRelated<MemberNode>(lyre, "OWNED BY", Direction.INCOMING).isEmpty())
    assertEquals(listOf("lyre"), gom.edges.loadRelated<ThingNode>(ada, "OWNED BY", Direction.INCOMING).map { it.id })
    assertEquals(listOf("lyre"), gom.edges.loadRelated<ThingNode>(ada, "OWNED BY", Direction.UNDIRECTED).map { it.id })
    assertEquals(setOf("Thing", "Instrument"), gom.edges.loadRelated<ThingNode>(ada, "OWNED BY", Direction.INCOMING).single().labels)
    assertTrue(gom.edges.loadRelated<MemberNode>(lyre, "NEVER_USED").isEmpty())
    assertTrue(gom.edges.loadRelated<MemberNode>(nodeRef<ThingNode>("nothing"), "OWNED BY").isEmpty())

    // ----- A node joined by two such relationships is returned once -----
    assertEquals(listOf("ada"), gom.edges.loadRelated<MemberNode>(lyre, "PLAYED_BY").map { it.id })
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class RelateNeo4jTest {
    companion object {
        private const val PASSWORD = "relatetest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-relate", type = DatabaseType.NEO4J,
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
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `relationships of a runtime type are written and read on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()), pm)
}

@Testcontainers
class RelateFalkorDbTest {
    companion object {
        private const val GRAPH = "relatetest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-relate", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `relationships of a runtime type are written and read on FalkorDb`() = verify(buildGom(pm, SubtypeRegistry()), pm)
}

@Testcontainers
class RelateMemgraphTest {
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
                name = "memgraph-relate", type = DatabaseType.MEMGRAPH,
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
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `relationships of a runtime type are written and read on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()), pm)
}

