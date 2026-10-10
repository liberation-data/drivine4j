package org.drivine.manager

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.drivine.StaleObjectException
import org.drivine.annotation.Direction
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.stateless.CitedBy
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimTickets
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.HumanCitations
import sample.stateless.Pals
import sample.stateless.Ticket
import sample.stateless.Widget
import sample.stateless.WidgetView

/**
 * What `GraphObjectManager` and `edges` write for a relationship, verified on Neo4j, FalkorDB and
 * Memgraph: the nodes at its ends get a new relationship token when a relationship is made, removed
 * or given other properties, and keep the one they have when a save finds it as it writes it; a
 * target is matched by its id as it is stored; and the root is never deleted as its own orphan.
 */
abstract class LegacyRelationshipMarksContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    /** A manager that has loaded nothing: every item of a view it saves is new to it. */
    private fun fresh(): GraphObjectManager {
        val mapper = Neo4jObjectMapper.instance
        return GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
    }

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun strings(cypher: String): List<String> =
        pm.query(QuerySpecification.withStatement(cypher).transform(String::class.java)).sorted()

    /** The stamp of the node whose [idProperty] is [id]. */
    private fun stamp(id: String, idProperty: String = "id"): String = pm.query(
        QuerySpecification.withStatement("MATCH (n) WHERE n[\$idProperty] = \$id RETURN coalesce(n[\$key], '')")
            .bind(mapOf("id" to id, "key" to Stamps.PROPERTY, "idProperty" to idProperty)).transform(String::class.java)
    ).single()

    private fun links(id: String): String = stamp(id).substringAfter(':')

    private fun node(id: String): String = stamp(id).substringBefore(':')

    /** Every relationship of [type], as `from->to`. */
    private fun edges(type: String): List<String> = pm.query(
        QuerySpecification.withStatement("MATCH (a)-[r]->(b) WHERE type(r) = \$type RETURN toString(coalesce(a.id, a.widget_key)) + '->' + toString(b.id)")
            .bind(mapOf("type" to type)).transform(String::class.java)
    ).sorted()

    private fun hasOrphanDelete() = assumeTrue(pm.type != DatabaseType.MEMGRAPH, "Memgraph has no DELETE_ORPHAN")

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- A save that finds a relationship as it writes it -----

    @Test
    fun `a view saved again with nothing changed gives neither end a new token, and a Replace loaded before is applied`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val mine = assertNotNull(stateless.load("c1", ClaimView::class.java))
        val before = stamp("c1") to stamp("ada")

        fresh().save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        assertEquals(before, stamp("c1") to stamp("ada"))
        stateless.save(mine, Replace(ClaimView::people))
    }

    @Test
    fun `a view that adds a relationship gives both ends a new relationship token and neither a new node token`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        stateless.save(Human("bob", "Bob"))
        val mine = assertNotNull(stateless.load("c1", ClaimView::class.java))
        val nodes = listOf(node("c1"), node("ada"), node("bob"))
        val before = Triple(links("c1"), links("ada"), links("bob"))

        fresh().save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        assertNotEquals(before.first, links("c1"), "the claim gained a relationship")
        assertNotEquals(before.third, links("bob"), "so did Bob")
        assertEquals(before.second, links("ada"), "the relationship to Ada was there")
        assertEquals(nodes, listOf(node("c1"), node("ada"), node("bob")))
        assertFailsWith<StaleObjectException> { stateless.save(mine, Replace(ClaimView::people)) }
    }

    @Test
    fun `a relationship with properties is marked when a property changes and not when it is as stored`() {
        stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(7, Human("ada", "Ada")))))
        val before = links("c1") to links("ada")

        fresh().save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(7, Human("ada", "Ada")))))
        assertEquals(before, links("c1") to links("ada"), "page 7 was stored")

        fresh().save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(8, Human("ada", "Ada")))))
        assertEquals(listOf("8"), strings("MATCH (:Claim)-[r:CITES]->(:Human) RETURN toString(r.page)"))
        assertNotEquals(before.first, links("c1"))
        assertNotEquals(before.second, links("ada"))
    }

    @Test
    fun `a relationship stored towards the root of an undirected field is found as it is`() {
        stateless.save(Human("ada", "Ada"))
        stateless.save(Human("bob", "Bob"))
        run("MATCH (a:Human {id: 'ada'}), (b:Human {id: 'bob'}) CREATE (b)-[:KNOWS]->(a)")
        val before = stamp("ada") to stamp("bob")

        fresh().save(Pals(Human("ada", "Ada"), pals = listOf(Human("bob", "Bob"))))

        assertEquals(listOf("bob->ada"), edges("KNOWS"))
        assertEquals(before, stamp("ada") to stamp("bob"))
    }

    // ----- A relationship removed -----

    @Test
    fun `a relationship dropped from a loaded view gives both ends a new relationship token`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        val before = Triple(links("c1"), links("ada"), links("bob"))
        val gom = fresh()
        val loaded = assertNotNull(gom.load("c1", ClaimView::class.java))

        gom.save(loaded.copy(people = loaded.people.filter { it.id == "ada" }))

        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
        assertNotEquals(before.first, links("c1"))
        assertNotEquals(before.third, links("bob"))
        assertEquals(before.second, links("ada"), "the relationship to Ada stays")
    }

    @Test
    fun `a relationship dropped through an undirected field is removed whichever way it is stored`() {
        stateless.save(Human("ada", "Ada"))
        stateless.save(Human("bob", "Bob"))
        stateless.save(Human("cy", "Cy"))
        run("MATCH (a:Human {id: 'ada'}), (b:Human {id: 'bob'}), (c:Human {id: 'cy'}) CREATE (b)-[:KNOWS]->(a), (a)-[:KNOWS]->(c)")
        val before = Triple(links("ada"), links("bob"), links("cy"))
        val gom = fresh()
        val loaded = assertNotNull(gom.load("ada", Pals::class.java))
        assertEquals(setOf("bob", "cy"), loaded.pals.map { it.id }.toSet())

        gom.save(loaded.copy(pals = emptyList()))

        assertEquals(emptyList(), edges("KNOWS"))
        assertNotEquals(before.first, links("ada"))
        assertNotEquals(before.second, links("bob"))
        assertNotEquals(before.third, links("cy"))
    }

    @Test
    fun `DELETE_ORPHAN gives a new relationship token to the root and to a target it drops and keeps`() {
        hasOrphanDelete()
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("bob", "Bob"))))
        val before = Triple(links("c1"), links("ada"), links("bob"))

        fresh().save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))), CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("c1->ada", "c2->bob"), edges("MENTIONS"), "Bob is still mentioned by the other claim")
        assertNotEquals(before.first, links("c1"))
        assertNotEquals(before.third, links("bob"))
        assertEquals(before.second, links("ada"))
    }

    // ----- A target matched by its id as it is stored -----

    @Test
    fun `a target with a numeric id dropped from a loaded view loses its relationship`() {
        val gom = fresh()
        gom.save(ClaimTickets(Claim("c1", "one"), tickets = listOf(Ticket(1, "a"), Ticket(2, "b"))))
        val loaded = assertNotNull(gom.load("c1", ClaimTickets::class.java))

        gom.save(loaded.copy(tickets = loaded.tickets.filter { it.id == 1L }))

        assertEquals(listOf("c1->1"), edges("TRACKS"))
        assertEquals(listOf("1", "2"), strings("MATCH (t:Ticket) RETURN toString(t.id)"), "the node stays")
    }

    @Test
    fun `DELETE_ORPHAN keeps the targets with a numeric id that the object still holds`() {
        hasOrphanDelete()
        fresh().save(ClaimTickets(Claim("c1", "one"), tickets = listOf(Ticket(1, "a"), Ticket(2, "b"), Ticket(3, "c"))))
        run("MATCH (t:Ticket {id: 1}) SET t.extra = 'kept'")
        val gom = fresh()
        val loaded = assertNotNull(gom.load("c1", ClaimTickets::class.java))

        gom.save(loaded.copy(tickets = loaded.tickets.filter { it.id != 3L }), CascadeType.DELETE_ORPHAN)

        assertEquals(
            listOf("1/kept", "2/"),
            strings("MATCH (:Claim {id: 'c1'})-[:TRACKS]->(t:Ticket) RETURN toString(t.id) + '/' + coalesce(t.extra, '')"),
            "a ticket the object holds is not deleted and made again",
        )
        assertEquals(listOf("1", "2"), strings("MATCH (t:Ticket) RETURN toString(t.id)"), "the dropped one is an orphan")
    }

    @Test
    fun `relationships are written and removed by the property the id is stored under`() {
        val gom = fresh()
        gom.save(WidgetView(Widget("w1", "one"), users = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        assertEquals(listOf("w1->ada", "w1->bob"), edges("USED_BY"))
        val loaded = assertNotNull(gom.load("w1", WidgetView::class.java))

        gom.save(loaded.copy(users = loaded.users.filter { it.id == "ada" }))

        assertEquals(listOf("w1->ada"), edges("USED_BY"))
    }

    @Test
    fun `DELETE_ORPHAN finds its root by the property the id is stored under`() {
        hasOrphanDelete()
        fresh().save(WidgetView(Widget("w1", "one"), users = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        fresh().save(WidgetView(Widget("w1", "one"), users = listOf(Human("ada", "Ada"))), CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("w1->ada"), edges("USED_BY"))
        assertEquals(listOf("ada"), strings("MATCH (h:Human) RETURN h.id"))
    }

    // ----- An incoming field with properties -----

    @Test
    fun `an incoming relationship with properties is written towards the root and loads back`() {
        val gom = fresh()

        gom.save(HumanCitations(Human("ada", "Ada"), citedBy = listOf(CitedBy(7, Claim("c1", "one")))))

        assertEquals(listOf("c1->ada/7"), strings("MATCH (a)-[r:CITES]->(b) RETURN a.id + '->' + b.id + '/' + toString(r.page)"))
        assertEquals(listOf(7), fresh().load("ada", HumanCitations::class.java)?.citedBy?.map { it.page })
    }

    // ----- The root is not its own orphan -----

    @Test
    fun `DELETE_ORPHAN removes a relationship from the root to itself and keeps the root`() {
        hasOrphanDelete()
        run("CREATE (a:Human {id: 'ada', name: 'Ada'}) CREATE (a)-[:KNOWS]->(a)")

        fresh().save(Pals(Human("ada", "Ada"), pals = emptyList()), CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("ada"), strings("MATCH (h:Human) RETURN h.id"))
        assertEquals(emptyList(), edges("KNOWS"))
    }

    // ----- edges -----

    @Test
    fun `relate marks both nodes when it rewrites one of several relationships between them`() {
        stateless.save(Claim("c1", "one"))
        stateless.save(Human("ada", "Ada"))
        val edges = stateless.edges
        edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 1), RelateMode.CREATE)
        edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 2), RelateMode.CREATE)
        val before = links("c1") to links("ada")

        edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 1))

        assertEquals(listOf("1", "1"), strings("MATCH (:Claim)-[r:CITES]->(:Human) RETURN toString(r.page)"), "both carry the page now")
        assertNotEquals(before.first, links("c1"))
        assertNotEquals(before.second, links("ada"))

        val settled = links("c1") to links("ada")
        edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 1))
        assertEquals(settled, links("c1") to links("ada"), "every one of them was as written")
    }

    @Test
    fun `relate that makes another relationship marks both nodes and neither's own data`() {
        stateless.save(Claim("c1", "one"))
        stateless.save(Human("ada", "Ada"))
        stateless.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 1))
        val before = stamp("c1") to stamp("ada")

        stateless.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "CITES", mapOf("page" to 1), RelateMode.CREATE)

        assertEquals(2, strings("MATCH (:Claim)-[r:CITES]->(:Human) RETURN toString(r.page)").size)
        assertNotEquals(before.first.substringAfter(':'), links("c1"))
        assertNotEquals(before.second.substringAfter(':'), links("ada"))
        assertEquals(before.first.substringBefore(':') to before.second.substringBefore(':'), node("c1") to node("ada"))
    }

    @Test
    fun `unrelate marks the node the relationship went to as well`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        val before = Triple(stamp("c1"), stamp("ada"), stamp("bob"))

        assertEquals(1, stateless.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS"))

        assertNotEquals(before.first.substringAfter(':'), links("c1"))
        assertNotEquals(before.third.substringAfter(':'), links("bob"))
        assertEquals(before.third.substringBefore(':'), node("bob"), "Bob's own data is as it was")
        assertEquals(before.second, stamp("ada"), "Ada's relationship stays")
    }

    @Test
    fun `unrelateAll marks the node and each node it was joined to, and a Replace loaded before is refused`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(Human("cy", "Cy"))
        val mine = assertNotNull(stateless.load("c1", ClaimView::class.java))
        val before = listOf("c1", "ada", "bob", "cy").associateWith { stamp(it) }

        assertEquals(2, stateless.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS"))

        listOf("c1", "ada", "bob").forEach { id ->
            assertNotEquals(before.getValue(id).substringAfter(':'), links(id), "$id lost a relationship")
            assertEquals(before.getValue(id).substringBefore(':'), node(id), "$id's own data is as it was")
        }
        assertEquals(before.getValue("cy"), stamp("cy"), "Cy had none")
        assertFailsWith<StaleObjectException> { stateless.save(mine, Replace(ClaimView::people)) }
        assertTrue(edges("MENTIONS").isEmpty())
    }

    @Test
    fun `unrelateAll through an incoming direction marks the nodes at the far end`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("ada", "Ada"))))
        val before = Triple(links("ada"), links("c1"), links("c2"))

        assertEquals(2, stateless.edges.unrelateAll(nodeRef<Human>("ada"), "MENTIONS", Direction.INCOMING))

        assertNotEquals(before.first, links("ada"))
        assertNotEquals(before.second, links("c1"))
        assertNotEquals(before.third, links("c2"))
    }
}

@Testcontainers
class LegacyRelationshipMarksNeo4jTest : LegacyRelationshipMarksContract() {
    companion object {
        private const val PASSWORD = "legacymarks"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-legacy-marks", type = DatabaseType.NEO4J,
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
class LegacyRelationshipMarksFalkorDbTest : LegacyRelationshipMarksContract() {
    companion object {
        private const val GRAPH = "legacymarks"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-legacy-marks", host = container.host, port = container.getMappedPort(6379),
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
class LegacyRelationshipMarksMemgraphTest : LegacyRelationshipMarksContract() {
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
                name = "memgraph-legacy-marks", type = DatabaseType.MEMGRAPH,
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
