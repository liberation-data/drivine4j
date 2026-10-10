package org.drivine.manager

import kotlin.test.assertEquals
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
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimView
import sample.stateless.Dossier
import sample.stateless.Human
import sample.stateless.Memo

/**
 * [StatelessGraphObjectManager] against [GraphObjectManager]: the same scenario is run through each
 * from the same starting graph, and the graphs they leave are compared. Verified on Neo4j, FalkorDB
 * and Memgraph.
 *
 * - A stateless `save` leaves what a `GraphObjectManager` save of an object it is not tracking leaves.
 * - A stateless `update` leaves what a `GraphObjectManager` load, change and save leaves.
 * - `Replace` leaves what a tracked save leaves, except that it also removes a relationship another
 *   writer added: the object's list is the whole list.
 *
 * Each holds for flat targets, relationships with properties and nested views, and whether another
 * writer adds or removes a relationship between the load and the save.
 */
abstract class ManagerComparisonContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val mapper = Neo4jObjectMapper.instance
    private fun stateless() = StatelessGraphObjectManager(pm, mapper, SubtypeRegistry())
    private fun gom() = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** One kind of view, the graph it starts from, and what is done to it. */
    private class Shape<V : Any>(
        val name: String,
        val type: Class<V>,
        val rootId: String,
        val seed: List<String>,
        val fromScratch: V,
        val rootOnly: V,
        val change: (V) -> V,
        val otherWriters: Map<String, String?>,
    )

    private val flat = Shape(
        name = "flat targets", type = ClaimView::class.java, rootId = "c1",
        seed = listOf(
            "CREATE (:Claim {id: 'c1', text: 'old', note: 'kept'}), (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'}), (:Human {id: 'dan', name: 'Dan'}), (:Company {id: 'acme', name: 'Acme'})",
            "MATCH (c:Claim {id: 'c1'}), (t) WHERE t.id IN ['ada', 'bob', 'acme'] CREATE (c)-[:MENTIONS]->(t)",
        ),
        fromScratch = ClaimView(Claim("c1", "scratch"), people = listOf(Human("ada", "Ada II"), Human("eve", "Eve"))),
        rootOnly = ClaimView(Claim("c1", "root only")),
        change = { view ->
            view.copy(
                claim = view.claim.copy(text = "new"),
                people = view.people.filter { it.id != "ada" }.map { if (it.id == "bob") it.copy(name = "Robert") else it } + Human("cy", "Cy"),
            )
        },
        otherWriters = mapOf(
            "no other writer" to null,
            "another writer adds a relationship" to "MATCH (c:Claim {id: 'c1'}), (d:Human {id: 'dan'}) CREATE (c)-[:MENTIONS]->(d)",
            "another writer removes a relationship" to "MATCH (:Claim {id: 'c1'})-[r:MENTIONS]->(:Human {id: 'bob'}) DELETE r",
        ),
    )

    private val withProperties = Shape(
        name = "relationships with properties", type = ClaimCitations::class.java, rootId = "c1",
        seed = listOf(
            "CREATE (:Claim {id: 'c1', text: 'old', note: 'kept'}), (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'}), (:Human {id: 'dan', name: 'Dan'})",
            "MATCH (c:Claim {id: 'c1'}), (t:Human {id: 'ada'}) CREATE (c)-[:CITES {page: 1}]->(t)",
            "MATCH (c:Claim {id: 'c1'}), (t:Human {id: 'bob'}) CREATE (c)-[:CITES {page: 2}]->(t)",
        ),
        fromScratch = ClaimCitations(Claim("c1", "scratch"), cited = listOf(Citation(5, Human("ada", "Ada II")), Citation(6, Human("eve", "Eve")))),
        rootOnly = ClaimCitations(Claim("c1", "root only")),
        change = { view ->
            view.copy(
                claim = view.claim.copy(text = "new"),
                cited = view.cited.filter { it.target.id != "ada" }.map { if (it.target.id == "bob") it.copy(page = 20) else it } + Citation(3, Human("cy", "Cy")),
            )
        },
        otherWriters = mapOf(
            "no other writer" to null,
            "another writer adds a relationship" to "MATCH (c:Claim {id: 'c1'}), (d:Human {id: 'dan'}) CREATE (c)-[:CITES {page: 9}]->(d)",
            "another writer removes a relationship" to "MATCH (:Claim {id: 'c1'})-[r:CITES]->(:Human {id: 'bob'}) DELETE r",
        ),
    )

    private val nested = Shape(
        name = "nested views", type = Dossier::class.java, rootId = "m1",
        seed = listOf(
            "CREATE (:Memo {id: 'm1', text: 'old'}), (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'}), (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'}), (:Human {id: 'dan', name: 'Dan'})",
            "MATCH (m:Memo {id: 'm1'}), (c:Claim) CREATE (m)-[:COVERS]->(c)",
            "MATCH (c:Claim {id: 'c1'}), (t:Human) WHERE t.id IN ['ada', 'bob'] CREATE (c)-[:MENTIONS]->(t)",
            "MATCH (c:Claim {id: 'c2'}), (t:Human {id: 'ada'}) CREATE (c)-[:MENTIONS]->(t)",
        ),
        fromScratch = Dossier(Memo("m1", "scratch"), claims = listOf(ClaimView(Claim("c1", "scratch"), people = listOf(Human("eve", "Eve"))))),
        rootOnly = Dossier(Memo("m1", "root only")),
        change = { dossier ->
            dossier.copy(
                memo = dossier.memo.copy(text = "new"),
                claims = dossier.claims.filter { it.claim.id != "c2" }.map { claim ->
                    claim.copy(people = claim.people.filter { it.id != "ada" }.map { if (it.id == "bob") it.copy(name = "Robert") else it })
                } + ClaimView(Claim("c3", "three"), people = listOf(Human("cy", "Cy"))),
            )
        },
        otherWriters = mapOf(
            "no other writer" to null,
            "another writer adds a relationship" to "MATCH (c:Claim {id: 'c1'}), (d:Human {id: 'dan'}) CREATE (c)-[:MENTIONS]->(d)",
            "another writer removes a relationship" to "MATCH (:Claim {id: 'c1'})-[r:MENTIONS]->(:Human {id: 'bob'}) DELETE r",
        ),
    )

    private val shapes: List<Shape<out Any>> = listOf(flat, withProperties, nested)

    /** The graph as sorted lines: each node with its values, each relationship with its ends. Stamps are left out. */
    private fun graph(): List<String> {
        val nodes = pm.query(
            QuerySpecification.withStatement(
                "MATCH (n) RETURN labels(n)[0] + ' ' + n.id + ' [' + coalesce(n.text, n.name, '') + '] [' + coalesce(n.note, '') + ']'"
            ).transform(String::class.java)
        )
        val relationships = pm.query(
            QuerySpecification.withStatement(
                "MATCH (a)-[r]->(b) RETURN a.id + ' -' + type(r) + coalesce(' ' + toString(r.page), '') + '-> ' + b.id"
            ).transform(String::class.java)
        )
        return (nodes + relationships).sorted()
    }

    private fun <V : Any> after(shape: Shape<V>, seeded: Boolean, scenario: () -> Unit): List<String> {
        run("MATCH (n) DETACH DELETE n")
        if (seeded) shape.seed.forEach(::run)
        scenario()
        return graph().also { check(it.any { line -> "->" in line } || !seeded) { "${shape.name}: the graph has no relationships" } }
    }

    private fun difference(cell: String, expected: List<String>, actual: List<String>): String? =
        if (expected == actual) null else """
            |$cell
            |  only with GraphObjectManager:          ${expected - actual.toSet()}
            |  only with StatelessGraphObjectManager: ${actual - expected.toSet()}
        """.trimMargin()

    private fun assertNone(differences: List<String>) =
        assertEquals(emptyList(), differences, "\n" + differences.joinToString("\n") + "\n")

    @Test
    fun `a stateless save leaves what an untracked GraphObjectManager save leaves`() {
        assertNone(shapes.flatMap { untrackedSaves(it) })
    }

    private fun <V : Any> untrackedSaves(shape: Shape<V>): List<String> {
        val differences = mutableListOf<String?>()
        // An object built from scratch, into an empty graph and over an existing one, and a root with empty lists.
        for ((label, obj, seeded) in listOf(
            Triple("built from scratch, empty graph", shape.fromScratch, false),
            Triple("built from scratch, existing graph", shape.fromScratch, true),
            Triple("root with empty lists, existing graph", shape.rootOnly, true),
        )) {
            val expected = after(shape, seeded) { gom().save(obj, CascadeType.NONE) }
            val actual = after(shape, seeded) { stateless().save(obj) }
            differences += difference("${shape.name}: $label", expected, actual)
        }
        // An object loaded, changed, and saved by a manager that did not load it.
        for ((writer, cypher) in shape.otherWriters) {
            val expected = after(shape, true) {
                val changed = shape.change(assertNotNull(gom().load(shape.rootId, shape.type)))
                cypher?.let(::run)
                gom().save(changed, CascadeType.NONE)
            }
            val actual = after(shape, true) {
                val changed = shape.change(assertNotNull(stateless().load(shape.rootId, shape.type)))
                cypher?.let(::run)
                stateless().save(changed)
            }
            differences += difference("${shape.name}: loaded then changed, $writer", expected, actual)
        }
        return differences.filterNotNull()
    }

    @Test
    fun `a stateless update leaves what a tracked GraphObjectManager load, change and save leaves`() {
        assertNone(shapes.flatMap { trackedSaves(it) })
    }

    private fun <V : Any> trackedSaves(shape: Shape<V>): List<String> = shape.otherWriters.mapNotNull { (writer, cypher) ->
        val expected = after(shape, true) {
            val tracking = gom()
            val changed = shape.change(assertNotNull(tracking.load(shape.rootId, shape.type)))
            cypher?.let(::run)
            tracking.save(changed, CascadeType.NONE)
        }
        val actual = after(shape, true) {
            // The other writer acts between update's load and its save.
            stateless().update(shape.rootId, shape.type) { loaded -> shape.change(loaded).also { cypher?.let(::run) } }
        }
        difference("${shape.name}: $writer", expected, actual)
    }

    @Test
    fun `Replace leaves what a tracked save leaves when no other writer acted`() {
        val expected = after(flat, true) {
            val tracking = gom()
            tracking.save(flat.change(assertNotNull(tracking.load("c1", ClaimView::class.java))), CascadeType.NONE)
        }
        val actual = after(flat, true) {
            val manager = stateless()
            manager.save(flat.change(assertNotNull(manager.load<ClaimView>("c1"))), Replace(ClaimView::people))
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `Replace removes a relationship another writer added, where a tracked save keeps it`() {
        val otherWriter = flat.otherWriters.getValue("another writer adds a relationship")!!
        val tracked = after(flat, true) {
            val tracking = gom()
            val changed = flat.change(assertNotNull(tracking.load("c1", ClaimView::class.java)))
            run(otherWriter)
            tracking.save(changed, CascadeType.NONE)
        }
        val replaced = after(flat, true) {
            val manager = stateless()
            val changed = flat.change(assertNotNull(manager.load<ClaimView>("c1")))
            run(otherWriter)
            manager.save(changed, Replace(ClaimView::people))
        }

        assertEquals(listOf("c1 -MENTIONS-> dan"), tracked - replaced.toSet(), "the list given to Replace is the whole list")
        assertEquals(emptyList(), replaced - tracked.toSet())
    }
}

@Testcontainers
class ManagerComparisonNeo4jTest : ManagerComparisonContract() {
    companion object {
        private const val PASSWORD = "managercomparison"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-manager-comparison", type = DatabaseType.NEO4J,
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
class ManagerComparisonFalkorDbTest : ManagerComparisonContract() {
    companion object {
        private const val GRAPH = "managercomparison"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-manager-comparison", host = container.host, port = container.getMappedPort(6379),
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
class ManagerComparisonMemgraphTest : ManagerComparisonContract() {
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
                name = "memgraph-manager-comparison", type = DatabaseType.MEMGRAPH,
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
