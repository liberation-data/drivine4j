package org.drivine.migration

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.load
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
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
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimView
import sample.stateless.HumanClaims
import sample.stateless.HumanFollowers
import sample.stateless.HumanMentions

/**
 * [RelationshipDirectionRepair] and [PathRelationshipReport] on Neo4j, FalkorDB and Memgraph.
 *
 * Before 0.1.0 a view save wrote a relationship field declared `INCOMING` as outgoing. The graphs
 * here hold such relationships, written with Cypher as the old save wrote them: from the view's root
 * to the target, where the field reads them from the target to the root.
 */
abstract class RelationshipDirectionRepairContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val repair get() = RelationshipDirectionRepair(pm)
    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun relationships(): List<String> = pm.query(
        QuerySpecification.withStatement(
            "MATCH (a)-[r]->(b) RETURN a.id + ' -' + type(r) + coalesce(' ' + toString(r.page), '') + '-> ' + b.id"
        ).transform(String::class.java)
    ).sorted()

    @BeforeEach
    fun seed() {
        run("MATCH (n) DETACH DELETE n")
        run("CREATE (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'}), (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'}), (:Claim {id: 'c3', text: 'three'})")
        // As the old save wrote them: from the root (a person) to the claim.
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (h)-[:MENTIONS {page: 7}]->(c)")
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c2'}) CREATE (h)-[:MENTIONS]->(c)")
        // As the field reads them.
        run("MATCH (h:Human {id: 'bob'}), (c:Claim {id: 'c3'}) CREATE (c)-[:MENTIONS]->(h)")
    }

    @Test
    fun `the report counts the relationships of an incoming field that point away from the root`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(HumanClaims::class.java, finding.view)
        assertEquals("claims", finding.field)
        assertEquals("MENTIONS", finding.type)
        assertEquals(listOf("Human"), finding.rootLabels)
        assertEquals(listOf("Claim"), finding.targetLabels)
        assertEquals(2, finding.wrongWay)
        assertEquals(1, finding.rightWay)
        assertNull(finding.ambiguity)
    }

    @Test
    fun `the report changes nothing`() {
        val before = relationships()

        repair.report(HumanClaims::class.java)

        assertEquals(before, relationships())
    }

    @Test
    fun `a view with no writable incoming field has nothing to report`() {
        assertEquals(emptyList(), repair.report(ClaimView::class.java))
    }

    @Test
    fun `repair turns the relationships round, keeps their properties, and the view loads them`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(2, repair.repair(finding))

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
        val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        assertEquals(setOf("c1", "c2"), assertNotNull(stateless.load<HumanClaims>("ada")).claims.map { it.id }.toSet())
        assertEquals(0, repair.report(HumanClaims::class.java).single().wrongWay)
    }

    @Test
    fun `repair in small batches turns every relationship round once`() {
        val finding = repair.report(HumanClaims::class.java).single()

        assertEquals(2, repair.repair(finding, batchSize = 1))

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `repairing twice changes nothing the second time`() {
        val finding = repair.report(HumanClaims::class.java).single()
        repair.repair(finding)
        val after = relationships()

        assertEquals(0, repair.repair(repair.report(HumanClaims::class.java).single()))

        assertEquals(after, relationships())
    }

    @Test
    fun `a relationship that already points the right way is not duplicated`() {
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(h)")

        repair.repair(repair.report(HumanClaims::class.java).single())

        assertEquals(listOf("c1 -MENTIONS 7-> ada", "c2 -MENTIONS-> ada", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a field is ambiguous when another view declares the same relationship pointing away from the root`() {
        val finding = repair.report(HumanClaims::class.java, HumanMentions::class.java).single()

        assertNotNull(finding.ambiguity, "HumanMentions says a person mentions claims, so those relationships may be meant")
        assertFailsWith<IllegalStateException> { repair.repair(finding) }
        assertEquals(2, repair.report(HumanClaims::class.java).single().wrongWay, "a refused repair changes nothing")

        assertEquals(2, repair.repair(finding, force = true))
    }

    // ----- A path field written as a direct relationship -----

    private fun seedPath() {
        run("CREATE (:Company {id: 'acme', name: 'Acme'}), (:Company {id: 'initech', name: 'Initech'})")
        run("MATCH (c:Claim {id: 'c3'}), (h:Human {id: 'bob'}), (o:Company {id: 'acme'}) CREATE (h)-[:WORKS_AT]->(o)")
        // As the old save wrote the path field: straight from the claim to the company.
        run("MATCH (c:Claim {id: 'c3'}), (o:Company {id: 'acme'}) CREATE (c)-[:MENTIONS]->(o)")
    }

    @Test
    fun `the path report counts direct relationships of the first hop's type to the path's end`() {
        seedPath()
        val before = relationships()

        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java).single()

        assertEquals("employers", finding.field)
        assertEquals("MENTIONS", finding.type)
        assertEquals(listOf("Claim"), finding.rootLabels)
        assertEquals(listOf("Company"), finding.targetLabels)
        assertEquals(1, finding.direct)
        assertNull(finding.ambiguity)
        assertEquals(before, relationships(), "the report changes nothing")
    }

    @Test
    fun `the path report's statement removes what it counted and nothing else`() {
        seedPath()
        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java).single()

        run(finding.removalStatement)

        assertEquals(0, PathRelationshipReport(pm).report(ClaimEmployers::class.java).single().direct)
        assertEquals(listOf("ada -MENTIONS 7-> c1", "ada -MENTIONS-> c2", "bob -WORKS_AT-> acme", "c3 -MENTIONS-> bob"), relationships())
    }

    @Test
    fun `a path finding is ambiguous when a view declares that relationship directly`() {
        seedPath()

        val finding = PathRelationshipReport(pm).report(ClaimEmployers::class.java, ClaimView::class.java).single()

        assertEquals(1, finding.direct)
        assertNotNull(finding.ambiguity, "ClaimView.companies says a claim mentions companies")
    }

    @Test
    fun `a field between two nodes of one label cannot be repaired`() {
        run("MATCH (a:Human {id: 'ada'}), (b:Human {id: 'bob'}) CREATE (a)-[:FOLLOWS]->(b)")
        val before = relationships()

        val finding = repair.report(HumanFollowers::class.java).single()

        assertNotNull(finding.ambiguity)
        assertFailsWith<IllegalStateException> { repair.repair(finding, force = true) }
        assertEquals(before, relationships())
    }
}

@Testcontainers
class RelationshipDirectionRepairNeo4jTest : RelationshipDirectionRepairContract() {
    companion object {
        private const val PASSWORD = "directionrepair"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-direction-repair", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@Testcontainers
class RelationshipDirectionRepairFalkorDbTest : RelationshipDirectionRepairContract() {
    companion object {
        private const val GRAPH = "directionrepair"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-direction-repair", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@Testcontainers
class RelationshipDirectionRepairMemgraphTest : RelationshipDirectionRepairContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-direction-repair", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}
