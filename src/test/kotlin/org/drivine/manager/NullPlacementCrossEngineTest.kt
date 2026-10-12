package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.nullorder.RankedNode
import sample.nullorder.RankedNodeQueryDsl
import sample.nullorder.RankedView
import sample.nullorder.RankedViewQueryDsl
import kotlin.test.assertEquals

/**
 * `nullsLast()` / `nullsFirst()` on an order, verified on Neo4j, FalkorDB, Memgraph and Oracle: the
 * rows with no value for the ordered property go where the order says, in one statement, whichever
 * way the values run and wherever the database would have put them.
 *
 * Data: r1..r3 with score 1.0..3.0, and n1, n2 with none. The id breaks the tie among the null rows.
 */
private fun verify(gom: StatelessGraphObjectManager) {
    fun fragments(spec: GraphQuerySpec<RankedNodeQueryDsl>.() -> Unit) =
        gom.loadAll(RankedNode::class.java, RankedNodeQueryDsl.INSTANCE, spec).map { it.id }
    fun views(spec: GraphQuerySpec<RankedViewQueryDsl>.() -> Unit) =
        gom.loadAll(RankedView::class.java, RankedViewQueryDsl.INSTANCE, spec).map { it.ranked.id }

    val descending = listOf("r3", "r2", "r1")
    val ascending = descending.reversed()
    val nulls = listOf("n1", "n2")

    // A fragment: the order is on the node.
    assertEquals(descending + nulls, fragments { orderBy { query.score.desc().nullsLast(); query.id.asc() } })
    assertEquals(nulls + descending, fragments { orderBy { query.score.desc().nullsFirst(); query.id.asc() } })
    assertEquals(ascending + nulls, fragments { orderBy { query.score.asc().nullsLast(); query.id.asc() } })
    assertEquals(nulls + ascending, fragments { orderBy { query.score.asc().nullsFirst(); query.id.asc() } })

    // The root of a view: the order follows the projection, and is on the projected map.
    assertEquals(descending + nulls, views { orderBy { query.ranked.score.desc().nullsLast(); query.ranked.id.asc() } })
    assertEquals(nulls + descending, views { orderBy { query.ranked.score.desc().nullsFirst(); query.ranked.id.asc() } })
    assertEquals(ascending + nulls, views { orderBy { query.ranked.score.asc().nullsLast(); query.ranked.id.asc() } })
    assertEquals(nulls + ascending, views { orderBy { query.ranked.score.asc().nullsFirst(); query.ranked.id.asc() } })

    // What the placement is for: the best by score, and the null rows only for what the limit leaves.
    assertEquals(listOf("r3", "r2"), views { orderBy { query.ranked.score.desc().nullsLast() }; limit(2) })
    assertEquals(listOf("r3", "r2", "r1", "n1"), views { orderBy { query.ranked.score.desc().nullsLast(); query.ranked.id.asc() }; limit(4) })
    assertEquals(listOf("r1", "n1", "n2"), views { orderBy { query.ranked.score.desc().nullsLast(); query.ranked.id.asc() }; skip(2); limit(5) })

    // Refused where it could not be kept: the order of a relationship collection, and under a seek.
    assertThrows<IllegalArgumentException> { views { orderBy { query.tags.weight.desc().nullsLast() } } }
    assertThrows<IllegalArgumentException> {
        views { orderBy { query.ranked.score.desc().nullsLast() }; seek { query.ranked.score after 2.0 } }
    }
}

private const val SEED = """
    CREATE (r1:Ranked {id: 'r1', score: 1.0})
    CREATE (r2:Ranked {id: 'r2', score: 2.0})
    CREATE (r3:Ranked {id: 'r3', score: 3.0})
    CREATE (n1:Ranked {id: 'n1'})
    CREATE (n2:Ranked {id: 'n2'})
    CREATE (t1:RankedTag {id: 't1', weight: 1})
    CREATE (t2:RankedTag {id: 't2'})
    CREATE (r3)-[:TAGGED]->(t1)
    CREATE (r3)-[:TAGGED]->(t2)
    CREATE (n1)-[:TAGGED]->(t1)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

@Testcontainers
class NullPlacementNeo4jTest {
    companion object {
        private const val PASSWORD = "nullplacementtest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-nulls", type = DatabaseType.NEO4J,
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
    fun `nulls go where the order says on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()))
}

@Testcontainers
class NullPlacementFalkorDbTest {
    companion object {
        private const val GRAPH = "nullplacementtest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-nulls", host = container.host, port = container.getMappedPort(6379),
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
    fun `nulls go where the order says on FalkorDB`() = verify(buildGom(pm, SubtypeRegistry()))
}

@Testcontainers
class NullPlacementMemgraphTest {
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
                name = "memgraph-nulls", type = DatabaseType.MEMGRAPH,
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
    fun `nulls go where the order says on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()))
}

@OracleTests
class NullPlacementOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `nulls go where the order says on Oracle`() = verify(buildGom(pm, registry))
}
