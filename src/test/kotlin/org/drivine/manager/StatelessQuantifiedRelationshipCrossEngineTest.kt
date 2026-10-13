package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.any
import org.drivine.query.dsl.none
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import kotlin.test.assertEquals
import org.drivine.oracle.OracleTestDatabase
import org.junit.jupiter.api.BeforeEach
import org.drivine.oracle.OracleTests

/**
 * Feature 1 end-to-end — `any{}` / `none{}` quantified predicates over the to-many `HAS_MENTION`
 * relationship, verified to return the correct rows against Neo4j, FalkorDB, and Memgraph. This
 * exercises each engine's existence rendering (Neo4j `EXISTS{}`, Memgraph `size([...])>0`, FalkorDB
 * CALL-subquery count) and its `NOT (...)` negation for `none`.
 */
private fun verify(gom: StatelessGraphObjectManager) {
    fun ids(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): Set<String> =
        gom.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec)
            .map { it.proposition.id }.toSet()

    // any: at least one mention matches
    assertEquals(setOf("p1"), ids { where { query.mentions.any { resolvedId eq "ent-1" } } })
    assertEquals(setOf("p2"), ids { where { query.mentions.any { resolvedId eq "ent-3" } } })
    assertEquals(setOf("p1", "p2"), ids { where { query.mentions.any { resolvedId inList listOf("ent-1", "ent-3") } } })

    // any correlates several conditions to ONE mention: ent-1 is on m1 (SUBJECT), so ent-1+OBJECT matches nothing.
    assertEquals(setOf("p1"), ids { where { query.mentions.any { resolvedId eq "ent-1"; role eq "SUBJECT" } } })
    assertEquals(emptySet(), ids { where { query.mentions.any { resolvedId eq "ent-1"; role eq "OBJECT" } } })

    // none: no mention matches — includes propositions with no mentions at all (p3)
    assertEquals(setOf("p2", "p3"), ids { where { query.mentions.none { resolvedId eq "ent-1" } } })
    assertEquals(setOf("p1", "p2", "p3"), ids { where { query.mentions.none { resolvedId eq "absent" } } })
}

/**
 * `deleteAll` by a predicate on the relationship takes the propositions the same predicate loads, and
 * leaves what they mention. On Oracle such a predicate is a `GRAPH_TABLE`, which a block takes a
 * parameter in only as a statement of its own.
 */
private fun verifyDelete(gom: StatelessGraphObjectManager) {
    fun delete(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): Int = gom.deleteAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec)
    fun left(): Set<String> = gom.loadAll(PropositionView::class.java).map { it.proposition.id }.toSet()

    assertEquals(0, delete { where { query.mentions.any { resolvedId eq "absent" } } })
    assertEquals(1, delete { where { query.mentions.any { resolvedId eq "ent-1"; role eq "SUBJECT" } } })
    assertEquals(setOf("p2", "p3"), left())

    // A predicate on the root beside one on the relationship: each value is bound where the statement names it.
    assertEquals(0, delete { where { query.proposition.id eq "p3"; query.mentions.any { resolvedId eq "ent-3" } } })
    assertEquals(1, delete { where { query.proposition.id eq "p2"; query.mentions.none { resolvedId eq "ent-1" } } })
    assertEquals(setOf("p3"), left())
}

private const val SEED = """
    CREATE (p1:Proposition {id: 'p1', contextId: 'c', status: 'active', level: 0})
    CREATE (p2:Proposition {id: 'p2', contextId: 'c', status: 'active', level: 0})
    CREATE (p3:Proposition {id: 'p3', contextId: 'c', status: 'active', level: 0})
    CREATE (m1:Mention {id: 'm1', resolvedId: 'ent-1', role: 'SUBJECT'})
    CREATE (m2:Mention {id: 'm2', resolvedId: 'ent-2', role: 'OBJECT'})
    CREATE (m3:Mention {id: 'm3', resolvedId: 'ent-3', role: 'SUBJECT'})
    CREATE (p1)-[:HAS_MENTION]->(m1)
    CREATE (p1)-[:HAS_MENTION]->(m2)
    CREATE (p2)-[:HAS_MENTION]->(m3)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): StatelessGraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return StatelessGraphObjectManager(pm, mapper, registry)
}

@Testcontainers
class StatelessQuantifiedRelationshipNeo4jTest {
    companion object {
        private const val PASSWORD = "quantifiedtest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-quant", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
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
    fun `any and none over a to-many relationship on Neo4j`() = verify(gom)

    @Test
    fun `a delete of all by a predicate on a to-many relationship on Neo4j`() = verifyDelete(gom)
}

@Testcontainers
class StatelessQuantifiedRelationshipFalkorDbTest {
    companion object {
        private const val GRAPH = "quanttest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-quant", host = container.host, port = container.getMappedPort(6379),
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
    fun `any and none over a to-many relationship on FalkorDB`() = verify(gom)

    @Test
    fun `a delete of all by a predicate on a to-many relationship on FalkorDB`() = verifyDelete(gom)
}

@Testcontainers
class StatelessQuantifiedRelationshipMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-quant", type = DatabaseType.MEMGRAPH,
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
    fun `any and none over a to-many relationship on Memgraph`() = verify(gom)

    @Test
    fun `a delete of all by a predicate on a to-many relationship on Memgraph`() = verifyDelete(gom)
}

@OracleTests
class StatelessQuantifiedRelationshipOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `any and none over a to-many relationship on Oracle`() = verify(buildGom(pm, registry))

    @Test
    fun `a delete of all by a predicate on a to-many relationship on Oracle`() = verifyDelete(buildGom(pm, registry))
}
