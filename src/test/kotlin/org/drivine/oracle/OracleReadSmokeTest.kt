package org.drivine.oracle

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
import sample.stateless.Claim
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Human
import sample.vector.DocNode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Loads, counts and deletes through `StatelessGraphObjectManager` on Oracle, over a graph written as rows. */
@OracleTests
class OracleReadSmokeTest {

    private val pm = OracleTestDatabase.graphManager("smoke")
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(
            pm,
            """
            CREATE (c1:Claim {id: 'c1', text: 'Ada founded Acme'}), (c2:Claim {id: 'c2', text: 'Bob left', note: 'unverified'})
            CREATE (ada:Human {id: 'ada', name: 'Ada'}), (bob:Human {id: 'bob', name: 'Bob'}), (acme:Company {id: 'acme', name: 'Acme'})
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
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java, "JSON_VALUE(n.props, '$.note') = 'unverified'").map { it.id })
        assertEquals(1, manager.count(ClaimView::class.java, "JSON_VALUE(claim.props, '$.text') LIKE 'Ada%'"))
        // A property written as Cypher writes it is read as that property.
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java, "n.note = 'unverified' AND n.text <> 'n.note'").map { it.id })
        assertEquals(1, manager.count(ClaimView::class.java, "claim.id = 'c1' AND JSON_EXISTS(claim.labels, '$[*]?(@ == \"Claim\")')"))
    }

    @Test
    fun `a raw predicate may name a column of the root's row as SQL allows, in upper case or spaced`() {
        assertEquals(setOf("c1", "c2"), manager.loadAll(Claim::class.java, "N_.NID IS NOT NULL AND N_.LABELS IS NOT NULL").map { it.id }.toSet())
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java, "JSON_VALUE(n_ . props, '$.note') = 'unverified'").map { it.id })
        assertEquals(2, manager.count(Claim::class.java, "N_.PROPS IS NOT NULL"))
        assertEquals(1, manager.count(ClaimView::class.java, "JSON_VALUE(CLAIM_.PROPS, '$.id') = 'c1'"))
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
    fun `a path field holds each node its hops reach, once`() {
        assertEquals(listOf(Company("acme", "Acme")), manager.load("c1", ClaimEmployers::class.java)!!.employers)
        assertEquals(emptyList(), manager.load("c2", ClaimEmployers::class.java)!!.employers)
    }

    @Test
    fun `a field may have a name SQL keeps for its own`() {
        val view = manager.loadAll(Ordered::class.java, "order.id = 'c1' AND JSON_EXISTS(order.labels, '$[*]?(@ == \"Claim\")')").single()

        assertEquals("c1", view.order.id)
        assertEquals(listOf("ada", "bob"), view.group.map { it.id }.sorted())
        assertEquals(1, manager.delete("c1", Ordered::class.java, "order.text = 'Ada founded Acme'"))
    }

    @Test
    fun `a search with no index made for it reads every node of the label, and its own transaction's writes`() {
        OracleTestDatabase.seed(
            pm,
            """
            CREATE (:Doc {id: 'd1', title: 'near', embedding: [1.0, 0.0, 0.0, 0.0]}), (:Doc {id: 'd2', title: 'far', embedding: [0.0, 1.0, 0.0, 0.0]})
            CREATE (:Article {id: 'a1', title: 'Graphs', body: 'graph databases store graph data'}), (:Article {id: 'a2', title: 'Soup', body: 'a recipe'})
            """,
        )

        val nearest = manager.loadNearest(DocNode::class.java, listOf(0.9f, 0.1f, 0.0f, 0.0f), 1)
        assertEquals(listOf("d1"), nearest.map { it.value.id })
        val matching = manager.loadMatching(ArticleNode::class.java, "graph -recipe", 10, 0.0)
        assertEquals(listOf("a1" to 1.0), matching.map { it.value.id to it.score })
    }

    @Test
    fun `a delete takes the node and its relationships, and says how many it took`() {
        assertEquals(1, manager.delete("c1", Claim::class.java))
        assertEquals(0, manager.delete("c1", Claim::class.java))
        assertEquals(listOf("c2"), manager.loadAll(ClaimView::class.java).map { it.claim.id })
        assertEquals(2, manager.deleteAll(Human::class.java))
        assertEquals(emptyList(), manager.load("c2", ClaimView::class.java)!!.people)
    }

    @Test
    fun `a delete takes a predicate as it is written, a quote in it or longer than a literal may be`() {
        manager.save(Claim("c3", "Ada's"))
        assertEquals(1, manager.deleteAll(Claim::class.java, "n.text = 'Ada''s' OR n.note = q'[it's \$id]' /* isn't \$one */"))
        val ids = (1..4000).joinToString(", ") { "'é$it'" }
        assertEquals(1, manager.deleteAll(Claim::class.java, "n.id IN ($ids, 'c1')"))
        assertEquals(listOf("c2"), manager.loadAll(Claim::class.java).map { it.id })
    }
}

/** A view whose fields are named as SQL names a clause. */
@GraphView
data class Ordered(
    @Root val order: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val group: List<Human> = emptyList(),
)
