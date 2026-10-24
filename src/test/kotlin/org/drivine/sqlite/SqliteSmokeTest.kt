package org.drivine.sqlite

import org.drivine.annotation.Direction
import org.drivine.backend.sqlite.SqliteBackend
import org.drivine.manager.NodeRef
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.Ordered
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
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
import sample.vector.DocNode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Loads, counts and deletes through `StatelessGraphObjectManager` on SQLite, over a graph written as rows. */
class SqliteSmokeTest {

    private val pm = SqliteTestDatabase.graphManager("smoke")
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val seed = SqliteSeed(pm)

    @BeforeEach
    fun seed() {
        SqliteTestDatabase.clear(pm)
        with(seed) {
            node("Claim", properties = mapOf("id" to "c1", "text" to "Ada founded Acme"))
            node("Claim", properties = mapOf("id" to "c2", "text" to "Bob left", "note" to "unverified"))
            node("Human", properties = mapOf("id" to "ada", "name" to "Ada", "weight" to 2))
            node("Human", properties = mapOf("id" to "bob", "name" to "Bob", "weight" to 4))
            node("Company", properties = mapOf("id" to "acme", "name" to "Acme"))
            edge("c1", "MENTIONS", "ada")
            edge("c1", "MENTIONS", "acme")
            edge("c1", "MENTIONS", "bob")
            edge("c2", "MENTIONS", "bob")
            edge("ada", "WORKS_AT", "acme")
        }
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
        assertEquals(1, manager.count(ClaimView::class.java, "claim.id = 'c1' AND instr(claim.labels, '\"Claim\"') > 0"))
    }

    @Test
    fun `matches holds the whole value to the expression, whose groups are numbered as it writes them`() {
        val backend = SqliteBackend(pm, Neo4jObjectMapper.instance)
        fun matching(pattern: String): Set<String> {
            val query = GraphQuerySpec(Any()).apply { conditions += PropertyCondition("n.text", ComparisonOperator.MATCHES, pattern) }
            val statement = backend.loadAll(Claim::class.java, query).statement
            return pm.query(QuerySpecification.withStatement(statement.statement).bind(statement.bindings).transform(Map::class.java)).map { it["id"] as String }.toSet()
        }
        assertEquals(emptySet(), matching("Bob|Ada"), "an alternation is of the whole value, not of its start or its end")
        assertEquals(setOf("c1"), matching("(A).*\\1.*"))
        assertEquals(setOf("c2"), matching("(?i)bob.*"))
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
        seed.node("Memo", properties = mapOf("id" to "m1", "text" to "notes"))
        seed.edge("m1", "COVERS", "c1")
        seed.edge("m1", "COVERS", "c2")

        val dossier = manager.load("m1", Dossier::class.java)!!
        assertEquals(listOf("c1", "c2"), dossier.claims.map { it.claim.id }.sorted())
        assertEquals(listOf("ada", "bob"), dossier.claims.single { it.claim.id == "c1" }.people.map { it.id }.sorted())
    }

    @Test
    fun `a relationship that carries properties holds them beside the node it leads to`() {
        seed.edge("c1", "CITES", "ada", mapOf("page" to 12))

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
        val view = manager.loadAll(Ordered::class.java, "order.id = 'c1'").single()

        assertEquals("c1", view.order.id)
        assertEquals(listOf("ada", "bob"), view.group.map { it.id }.sorted())
    }

    @Test
    fun `a delete takes the node and its relationships, and says how many it took`() {
        assertEquals(1, manager.delete("c1", Claim::class.java))
        assertEquals(0, manager.delete("c1", Claim::class.java))
        assertEquals(listOf("c2"), manager.loadAll(ClaimView::class.java).map { it.claim.id })
        assertEquals(2, manager.deleteAll(Human::class.java))
        assertEquals(emptyList(), manager.load("c2", ClaimView::class.java)!!.people)
        assertEquals(listOf<Any>(0L), pm.query(QuerySpecification.withStatement("SELECT COUNT(*) FROM smoke_edge WHERE type = 'MENTIONS'")))
    }

    @Test
    fun `a delete takes a predicate as it is written, a quote or a dollar in it, and through a field SQL keeps for its own`() {
        assertEquals(1, manager.deleteAll(Claim::class.java, "n.text = 'Bob left' OR n.note = 'it''s \$id' /* isn't \$one */"))
        assertEquals(1, manager.delete("c1", Ordered::class.java, "order.text = 'Ada founded Acme'"))
        assertEquals(emptyList(), manager.loadAll(Claim::class.java))
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
    fun `a path gives a node once however many ways lead to it, and each root its own`() {
        SqliteTestDatabase.clear(pm)
        with(seed) {
            listOf("c1" to "one", "c2" to "two", "c3" to "three").forEach { (id, text) -> node("Claim", properties = mapOf("id" to id, "text" to text)) }
            node("Human", properties = mapOf("id" to "ada", "name" to "Ada"))
            node("Human", properties = mapOf("id" to "bob", "name" to "Bob"))
            node("Company", properties = mapOf("id" to "acme", "name" to "Acme"))
            node("Company", properties = mapOf("id" to "initech", "name" to "Initech"))
            edge("c1", "MENTIONS", "ada"); edge("c1", "MENTIONS", "bob"); edge("c2", "MENTIONS", "bob")
            edge("ada", "WORKS_AT", "acme"); edge("bob", "WORKS_AT", "acme"); edge("ada", "WORKS_AT", "initech")
        }

        assertEquals(listOf("acme", "initech"), manager.load("c1", ClaimEmployers::class.java)!!.employers.map { it.id }.sorted())
        assertEquals(
            mapOf("c1" to listOf("acme", "initech"), "c2" to listOf("acme"), "c3" to emptyList()),
            manager.loadAll(ClaimEmployers::class.java).associate { it.claim.id to it.employers.map { e -> e.id }.sorted() },
        )
    }

    @Test
    fun `a search reads every node of the label`() {
        with(seed) {
            node("Doc", properties = mapOf("id" to "d1", "title" to "near", "embedding" to listOf(1.0, 0.0, 0.0, 0.0)))
            node("Doc", properties = mapOf("id" to "d2", "title" to "far", "embedding" to listOf(0.0, 1.0, 0.0, 0.0)))
            node("Doc", properties = mapOf("id" to "d3", "title" to "wrong size", "embedding" to listOf(1.0, 0.0)))
            node("Article", properties = mapOf("id" to "a1", "title" to "Graphs", "body" to "graph databases store graph data"))
            node("Article", properties = mapOf("id" to "a2", "title" to "Soup", "body" to "a recipe"))
        }

        // A node whose embedding is of another size is not measured, rather than refused.
        val nearest = manager.loadNearest(DocNode::class.java, listOf(0.9f, 0.1f, 0.0f, 0.0f), 3)
        assertEquals(listOf("d1", "d2"), nearest.map { it.value.id })
        val matching = manager.loadMatching(ArticleNode::class.java, "graph -recipe", 10, 0.0)
        assertEquals(listOf("a1" to 1.0), matching.map { it.value.id to it.score })
    }
}
