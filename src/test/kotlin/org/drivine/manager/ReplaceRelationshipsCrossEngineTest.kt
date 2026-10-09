package org.drivine.manager

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
import sample.stateless.Claim
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Human
import sample.stateless.MemoView

/**
 * What [Replace] removes, verified on Neo4j, FalkorDB and Memgraph.
 *
 * The graph before each test:
 *
 * ```
 * (c1:Claim)-[:MENTIONS]->(ada:Human)-[:WORKS_AT]->(initech:Company)
 * (c1:Claim)-[:MENTIONS]->(bob:Human)
 * (c1:Claim)-[:MENTIONS]->(acme:Company)
 * (m1:Memo)-[:MENTIONS]->(ada:Human)
 * (m1:Memo)-[:MENTIONS]->(bob:Human)
 * (c1:Claim)-[:REVIEWED_BY]->(bob:Human)
 * ```
 */
abstract class ReplaceRelationshipsContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** The ids of the nodes labelled [label] that the node [from] mentions. */
    private fun mentioned(from: String, label: String): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH ({id: \$from})-[:MENTIONS]->(t:$label) RETURN t.id")
            .bind(mapOf("from" to from)).transform(String::class.java)
    ).toSet()

    private fun nodes(label: String): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH (n:$label) RETURN n.id").transform(String::class.java)
    ).toSet()

    @BeforeEach
    fun seed() {
        run("MATCH (n) DETACH DELETE n")
        run(
            """
            CREATE (c:Claim {id: 'c1', text: 'Ada and Bob met at Acme', `__drivine.stamp`: 'seeded'})
            CREATE (m:Memo {id: 'm1', text: 'Call Ada and Bob'})
            CREATE (ada:Human {id: 'ada', name: 'Ada'})
            CREATE (bob:Human {id: 'bob', name: 'Bob'})
            CREATE (acme:Company {id: 'acme', name: 'Acme'})
            CREATE (initech:Company {id: 'initech', name: 'Initech'})
            CREATE (c)-[:MENTIONS]->(ada)
            CREATE (c)-[:MENTIONS]->(bob)
            CREATE (c)-[:MENTIONS]->(acme)
            CREATE (m)-[:MENTIONS]->(ada)
            CREATE (m)-[:MENTIONS]->(bob)
            CREATE (ada)-[:WORKS_AT]->(initech)
            CREATE (c)-[:REVIEWED_BY]->(bob)
            """.trimIndent()
        )
    }

    // ----- Two fields share a relationship type -----

    @Test
    fun `replacing a field leaves another field of the same relationship type alone`() {
        val view = assertNotNull(stateless.load<ClaimView>("c1"))
        assertEquals(setOf("ada", "bob"), view.people.map { it.id }.toSet())
        assertEquals(setOf("acme"), view.companies.map { it.id }.toSet())

        stateless.save(view.copy(people = view.people.filter { it.id == "ada" }), Replace(ClaimView::people))

        assertEquals(setOf("ada"), mentioned("c1", "Human"), "Bob is no longer in the list, so his relationship goes")
        assertEquals(setOf("acme"), mentioned("c1", "Company"), "the companies field was not named, and people does not load companies")
        assertEquals(setOf("ada", "bob"), nodes("Human"), "removing a relationship keeps its target")
    }

    @Test
    fun `a field that is not named is add-only`() {
        val view = assertNotNull(stateless.load<ClaimView>("c1"))

        stateless.save(view.copy(people = emptyList(), companies = emptyList()), Replace(ClaimView::people))

        assertEquals(emptySet(), mentioned("c1", "Human"), "people is the whole list, and it is empty")
        assertEquals(setOf("acme"), mentioned("c1", "Company"), "companies was not named, so its empty list removes nothing")
    }

    // ----- A path field -----

    @Test
    fun `a path field cannot be replaced`() {
        val view = assertNotNull(stateless.load<ClaimEmployers>("c1"))
        assertEquals(setOf("initech"), view.employers.map { it.id }.toSet())

        assertFailsWith<IllegalArgumentException> {
            stateless.save(view.copy(employers = emptyList()), Replace(ClaimEmployers::employers))
        }

        assertEquals(setOf("ada", "bob"), mentioned("c1", "Human"), "a refused save writes nothing")
        assertEquals(setOf("initech"), nodes("Company") - "acme")
    }

    @Test
    fun `replacing every field leaves a path field alone`() {
        val view = assertNotNull(stateless.load<ClaimEmployers>("c1"))

        stateless.save(view.copy(people = view.people.filter { it.id == "ada" }, employers = emptyList()), Replace.all())

        assertEquals(setOf("ada"), mentioned("c1", "Human"))
        assertEquals(setOf("acme"), mentioned("c1", "Company"), "the path's first hop is MENTIONS, and the claim's own mention of Acme is not the path's to remove")
        assertEquals(
            listOf("initech"),
            pm.query(
                QuerySpecification.withStatement("MATCH ({id: 'ada'})-[:WORKS_AT]->(c:Company) RETURN c.id")
                    .transform(String::class.java)
            ),
            "nothing along the path is removed",
        )
    }

    @Test
    fun `a stateless save writes no relationship for a path field`() {
        val view = ClaimEmployers(Claim("c1", "Ada and Bob met at Acme"), employers = listOf(Company("initech", "Initech")))

        stateless.save(view)

        assertEquals(setOf("acme"), mentioned("c1", "Company"), "a path is read, never written")
    }

    /** The same case through the manager that exists today, saving an object it has not loaded. */
    @Test
    fun `a GraphObjectManager save writes no relationship for a path field`() {
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        val view = ClaimEmployers(Claim("c1", "Ada and Bob met at Acme"), employers = listOf(Company("initech", "Initech")))

        gom.save(view)

        assertEquals(setOf("acme"), mentioned("c1", "Company"), "a path is read, never written")
    }

    // ----- A relationship field declared read-only -----

    private fun reviewers(): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH ({id: 'c1'})-[:REVIEWED_BY]->(h:Human) RETURN h.id")
            .transform(String::class.java)
    ).toSet()

    private fun nameOf(id: String): String? = pm.query(
        QuerySpecification.withStatement("MATCH (h:Human {id: \$id}) RETURN h.name")
            .bind(mapOf("id" to id)).transform(String::class.java)
    ).firstOrNull()

    @Test
    fun `a read-only relationship field is loaded`() {
        val view = assertNotNull(stateless.load<ClaimReviewers>("c1"))

        assertEquals(listOf(Human("bob", "Bob")), view.reviewers)
    }

    @Test
    fun `a stateless save writes nothing for a read-only relationship field`() {
        val view = assertNotNull(stateless.load<ClaimReviewers>("c1"))

        stateless.save(view.copy(reviewers = listOf(Human("bob", "Robert"), Human("cy", "Cy"))))

        assertEquals(setOf("bob"), reviewers(), "no relationship is written for the field")
        assertEquals("Bob", nameOf("bob"), "the nodes the field holds are not saved")
        assertEquals(setOf("ada", "bob"), nodes("Human"), "and none is created")
    }

    /** The same case through the manager that exists today. */
    @Test
    fun `a GraphObjectManager save writes nothing for a read-only relationship field`() {
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        val view = ClaimReviewers(
            Claim("c1", "Ada and Bob met at Acme"),
            reviewers = listOf(Human("bob", "Robert"), Human("cy", "Cy")),
        )

        gom.save(view)

        assertEquals(setOf("bob"), reviewers())
        assertEquals("Bob", nameOf("bob"))
        assertEquals(setOf("ada", "bob"), nodes("Human"))
    }

    @Test
    fun `a read-only relationship field cannot be replaced`() {
        val view = assertNotNull(stateless.load<ClaimReviewers>("c1"))

        assertFailsWith<IllegalArgumentException> {
            stateless.save(view.copy(reviewers = emptyList()), Replace(ClaimReviewers::reviewers))
        }

        assertEquals(setOf("bob"), reviewers(), "a refused save writes nothing")
    }

    @Test
    fun `replacing every field leaves a read-only relationship field alone`() {
        val view = assertNotNull(stateless.load<ClaimReviewers>("c1"))

        stateless.save(view.copy(people = emptyList(), reviewers = emptyList()), Replace.all())

        assertEquals(emptySet(), mentioned("c1", "Human"))
        assertEquals(setOf("bob"), reviewers())
    }

    // ----- Replace.all() -----

    @Test
    fun `replacing every field applies each relationship field of a loaded object`() {
        val view = assertNotNull(stateless.load<ClaimView>("c1"))

        stateless.save(view.copy(people = view.people.filter { it.id == "ada" }, companies = emptyList()), Replace.all())

        assertEquals(setOf("ada"), mentioned("c1", "Human"))
        assertEquals(emptySet(), mentioned("c1", "Company"))
        assertEquals(setOf("acme", "initech"), nodes("Company"), "removed targets are kept by default")
    }

    @Test
    fun `replacing every field is refused for an object built from scratch`() {
        // Its lists are the declared defaults, not what the store holds.
        val view = ClaimView(Claim("c1", "Ada and Bob met at Acme"))

        assertFailsWith<IllegalArgumentException> { stateless.save(view, Replace.all()) }

        assertEquals(setOf("ada", "bob"), mentioned("c1", "Human"))
        assertEquals(setOf("acme"), mentioned("c1", "Company"))
    }

    @Test
    fun `replacing every field is refused for a type with no stamp field`() {
        val view = assertNotNull(stateless.load<MemoView>("m1"))

        assertFailsWith<IllegalArgumentException> { stateless.save(view.copy(people = emptyList()), Replace.all()) }

        assertEquals(setOf("ada", "bob"), mentioned("m1", "Human"))
    }

    @Test
    fun `a type with no stamp field can still replace a named field`() {
        val view = assertNotNull(stateless.load<MemoView>("m1"))

        stateless.save(view.copy(people = view.people.filter { it.id == "bob" }), Replace(MemoView::people))

        assertEquals(setOf("bob"), mentioned("m1", "Human"))
    }

    // ----- An object loaded by a custom query -----

    /**
     * Not a rule but a limit, recorded so it is not mistaken for a bug: the object carries a stamp,
     * so nothing tells Drivine that the query cut its list short. The list is taken as the whole list.
     */
    @Test
    fun `a list cut short by a custom query is taken as the whole list`() {
        val view = pm.query(
            QuerySpecification.withStatement(
                """
                MATCH (c:Claim {id: 'c1'})-[:MENTIONS]->(h:Human {id: 'ada'})
                WITH c, collect({id: h.id, name: h.name}) AS people
                RETURN {
                    claim: {id: c.id, text: c.text, stamp: c.`__drivine.stamp`},
                    people: people,
                    companies: []
                } AS view
                """.trimIndent()
            ).transform(ClaimView::class.java)
        ).single()
        assertEquals("seeded", view.claim.stamp)
        assertEquals(listOf(Human("ada", "Ada")), view.people)

        stateless.save(view, Replace.all())

        assertEquals(setOf("ada"), mentioned("c1", "Human"), "Bob was in the store and not in the query's list")
        assertEquals(emptySet(), mentioned("c1", "Company"))
    }
}

@Testcontainers
class ReplaceRelationshipsNeo4jTest : ReplaceRelationshipsContract() {
    companion object {
        private const val PASSWORD = "replacerelationships"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-replace", type = DatabaseType.NEO4J,
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
class ReplaceRelationshipsFalkorDbTest : ReplaceRelationshipsContract() {
    companion object {
        private const val GRAPH = "replacerelationships"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-replace", host = container.host, port = container.getMappedPort(6379),
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
class ReplaceRelationshipsMemgraphTest : ReplaceRelationshipsContract() {
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
                name = "memgraph-replace", type = DatabaseType.MEMGRAPH,
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
