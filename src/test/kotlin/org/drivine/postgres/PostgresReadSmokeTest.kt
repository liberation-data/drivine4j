package org.drivine.postgres

import org.drivine.annotation.Direction
import org.drivine.manager.NodeRef
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.Ordered
import org.drivine.query.MIXED_SEED
import org.drivine.query.ORDERED_SEED
import org.drivine.query.assertMixedOrderedPaths
import org.drivine.query.assertOrderedPaths
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimStats
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Dossier
import sample.stateless.Human
import sample.stateless.UndeclaredAggregate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loads and counts through `StatelessGraphObjectManager` on Postgres, over a graph written as rows. */
@PostgresTests
class PostgresReadSmokeTest {

    private val pm = PostgresTestDatabase.graphManager("smoke")
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    @BeforeEach
    fun seed() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(
            pm,
            """
            CREATE (c1:Claim {id: 'c1', text: 'Ada founded Acme'}), (c2:Claim {id: 'c2', text: 'Bob left', note: 'unverified'})
            CREATE (ada:Human {id: 'ada', name: 'Ada', weight: 2}), (bob:Human {id: 'bob', name: 'Bob', weight: 4}), (acme:Company {id: 'acme', name: 'Acme'})
            CREATE (c1)-[:MENTIONS]->(ada), (c1)-[:MENTIONS]->(acme), (c1)-[:MENTIONS]->(bob), (c2)-[:MENTIONS]->(bob)
            CREATE (ada)-[:WORKS_AT]->(acme)
            """,
        )
    }

    @Test
    fun `a fragment is loaded by id, with the others, and counted`() {
        assertEquals(Claim("c1", "Ada founded Acme"), manager.load("c1", Claim::class.java))
        assertNull(manager.load("nope", Claim::class.java))
        assertEquals(setOf("c1", "c2"), manager.loadAll(Claim::class.java).map { it.id }.toSet())
        assertEquals(2, manager.count(Claim::class.java))
        assertEquals(3, manager.count(Human::class.java) + manager.count(Company::class.java))
    }

    @Test
    fun `a raw predicate is one in the store's language, over the alias a Cypher one uses`() {
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java, "n_.props ->> 'note' = 'unverified'").map { it.id })
        assertEquals(1, manager.count(ClaimView::class.java, "claim_.props ->> 'text' LIKE 'Ada%'"))
        // A property written as Cypher writes it is read as that property.
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java, "n.note = 'unverified' AND n.text <> 'n.note'").map { it.id })
        assertEquals(1, manager.count(ClaimView::class.java, "claim.id = 'c1' AND claim.labels @> '[\"Claim\"]'"))
    }

    @Test
    fun `a view holds its root and each relationship field, told apart by the label of the target`() {
        val view = manager.load("c1", ClaimView::class.java)!!

        assertEquals(Claim("c1", "Ada founded Acme"), view.claim)
        assertEquals(listOf(Human("ada", "Ada"), Human("bob", "Bob")), view.people.sortedBy { it.id })
        assertEquals(listOf(Company("acme", "Acme")), view.companies)
        assertEquals(emptyList(), manager.load("c2", ClaimView::class.java)!!.companies)
    }

    @Test
    fun `a view of views holds each nested view whole`() {
        PostgresTestDatabase.seed(pm, "CREATE (m:Memo {id: 'm1', text: 'notes'}) WITH m MATCH (c:Claim) CREATE (m)-[:COVERS]->(c)")

        val dossier = manager.load("m1", Dossier::class.java)!!
        assertEquals(listOf("c1", "c2"), dossier.claims.map { it.claim.id }.sorted())
        assertEquals(listOf("ada", "bob"), dossier.claims.single { it.claim.id == "c1" }.people.map { it.id }.sorted())
    }

    @Test
    fun `a relationship that carries properties holds them beside the node it leads to`() {
        PostgresTestDatabase.seed(pm, "MATCH (c:Claim {id: 'c1'}), (h:Human {id: 'ada'}) CREATE (c)-[:CITES {page: 12}]->(h)")

        assertEquals(listOf(Citation(12, Human("ada", "Ada"))), manager.load("c1", ClaimCitations::class.java)!!.cited)
        assertEquals(emptyList(), manager.load("c2", ClaimCitations::class.java)!!.cited)
    }

    @Test
    fun `a count reads the relationships of each root, and an aggregate the nodes they lead to`() {
        assertEquals(mapOf("c1" to 3L, "c2" to 1L), manager.loadAll(ClaimStats::class.java).associate { it.claim.id to it.mentionCount })
        assertEquals(3.0, manager.load("c1", UndeclaredAggregate::class.java)!!.averageWeight)
    }

    @Test
    fun `a path field holds each node its hops reach, once`() {
        assertEquals(listOf(Company("acme", "Acme")), manager.load("c1", ClaimEmployers::class.java)!!.employers)
        assertEquals(emptyList(), manager.load("c2", ClaimEmployers::class.java)!!.employers)
    }

    @Test
    fun `a field may have a name SQL keeps for its own`() {
        val view = manager.loadAll(Ordered::class.java, "order.id = 'c1' AND order.labels @> '[\"Claim\"]'").single()

        assertEquals("c1", view.order.id)
        assertEquals(listOf("ada", "bob"), view.group.map { it.id }.sorted())
    }

    @Test
    fun `the nodes related to one are loaded once each, in any direction`() {
        val bob = NodeRef(Human::class.java, "bob")
        assertEquals(setOf("c1", "c2"), manager.edges.loadRelated(bob, "MENTIONS", Direction.INCOMING, Claim::class.java).map { it.id }.toSet())
        assertEquals(setOf("c1", "c2"), manager.edges.loadRelated(bob, "MENTIONS", Direction.UNDIRECTED, Claim::class.java).map { it.id }.toSet())
        assertEquals(emptyList(), manager.edges.loadRelated(bob, "MENTIONS", Direction.OUTGOING, Claim::class.java))
        assertEquals(listOf(Company("acme", "Acme")), manager.edges.loadRelated(NodeRef(Human::class.java, "ada"), "WORKS_AT", Direction.OUTGOING, Company::class.java))
    }

    @Test
    fun `a path ordered by a hop reaches its targets in that order, and a single one is the first`() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(pm, ORDERED_SEED)

        assertOrderedPaths { id, view -> manager.load(id, view) }
    }

    @Test
    fun `a path ordered by a property that holds texts and numbers reads every text before every number, as Neo4j does`() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(pm, MIXED_SEED)

        assertMixedOrderedPaths { id, view -> manager.load(id, view) }
    }

    @Test
    fun `a path gives a node once however many ways lead to it, and each root its own`() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(
            pm,
            """
            CREATE (c1:Claim {id: 'c1', text: 'one'}), (c2:Claim {id: 'c2', text: 'two'}), (c3:Claim {id: 'c3', text: 'three'})
            CREATE (ada:Human {id: 'ada', name: 'Ada'}), (bob:Human {id: 'bob', name: 'Bob'})
            CREATE (acme:Company {id: 'acme', name: 'Acme'}), (initech:Company {id: 'initech', name: 'Initech'})
            CREATE (c1)-[:MENTIONS]->(ada), (c1)-[:MENTIONS]->(bob), (c2)-[:MENTIONS]->(bob)
            CREATE (ada)-[:WORKS_AT]->(acme), (bob)-[:WORKS_AT]->(acme), (ada)-[:WORKS_AT]->(initech)
            """,
        )

        assertEquals(listOf("acme", "initech"), manager.load("c1", ClaimEmployers::class.java)!!.employers.map { it.id }.sorted())
        assertEquals(
            mapOf("c1" to listOf("acme", "initech"), "c2" to listOf("acme"), "c3" to emptyList()),
            manager.loadAll(ClaimEmployers::class.java).associate { it.claim.id to it.employers.map { e -> e.id }.sorted() },
        )
    }

    @Test
    fun `what is not built yet is refused, saying so`() {
        val failure = assertFailsWith<UnsupportedOperationException> { manager.delete("c1", Claim::class.java) }
        assertTrue("Postgres" in failure.message.orEmpty() && "not yet" in failure.message.orEmpty(), failure.message)
    }
}
