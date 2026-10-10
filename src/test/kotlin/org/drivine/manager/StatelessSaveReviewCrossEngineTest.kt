package org.drivine.manager

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.StaleObjectException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
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
import sample.stateless.Board
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimPair
import sample.stateless.ClaimStaff
import sample.stateless.ClaimSupports
import sample.stateless.ClaimTags
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.InheritedClaimView
import sample.stateless.JavaStampedRecord
import sample.stateless.Manager
import sample.stateless.Memo
import sample.stateless.Odd
import sample.stateless.Tagged
import sample.stateless.Worker

/**
 * What a stateless save hands back and leaves alone where an object holds a node more than once, was
 * built from scratch, or shares a batch with another that holds its root; verified on Neo4j, FalkorDB
 * and Memgraph.
 */
abstract class StatelessSaveReviewContract {

    abstract val pm: NonTransactionalPersistenceManager

    /** Whether a batch that fails is rolled back: FalkorDB has no transactions. */
    open val atomicBatch: Boolean = true

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun strings(cypher: String): List<String> =
        pm.query(QuerySpecification.withStatement(cypher).transform(String::class.java)).sorted()

    /** The stamp the node with [id] carries. */
    private fun stamp(id: String): String = strings("MATCH (n {id: '$id'}) RETURN n.`${Stamps.PROPERTY}`").single()

    private fun edges(type: String): List<String> =
        strings("MATCH (a)-[r]->(b) WHERE type(r) = '$type' RETURN toString(a.id) + '->' + toString(b.id)")

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- A stamped node an object holds more than once -----

    @Test
    fun `a root held in its own list is handed the stamp the node is left with, and can be saved again`() {
        stateless.save(Claim("c1", "one"))
        val loaded = assertNotNull(stateless.load("c1", ClaimSupports::class.java))
        val changed = loaded.claim.copy(text = "two")

        val saved = stateless.save(ClaimSupports(changed, supports = listOf(changed)))

        assertEquals(stamp("c1"), saved.claim.stamp)
        assertEquals(stamp("c1"), saved.supports.single().stamp)
        stateless.save(saved.copy(claim = saved.claim.copy(text = "three")), Replace(ClaimSupports::supports))
    }

    @Test
    fun `a stamped node held in two fields is handed the stamp the node is left with in both`() {
        stateless.save(Claim("c1", "one"))
        val held = stateless.save(Claim("k", "held"))
        val changed = held.copy(text = "changed")

        val saved = stateless.save(ClaimPair(Claim("c1", "one"), lead = changed, supports = listOf(changed)))

        assertEquals(stamp("k"), assertNotNull(saved.lead).stamp)
        assertEquals(stamp("k"), saved.supports.single().stamp)
        // Its relationships are as its stamp says, so a list of it can be replaced.
        stateless.save(ClaimSupports(saved.supports.single(), supports = emptyList()), Replace(ClaimSupports::supports))
    }

    @Test
    fun `two objects for one node in two fields are each handed the stamp the node is left with`() {
        stateless.save(Claim("c1", "one"))
        val held = stateless.save(Claim("k", "held"))

        val saved = stateless.save(ClaimPair(Claim("c1", "one"), lead = held.copy(text = "changed"), supports = listOf(held.copy(text = "changed"))))

        assertEquals(stamp("k"), assertNotNull(saved.lead).stamp)
        assertEquals(stamp("k"), saved.supports.single().stamp)
    }

    @Test
    fun `a stamped node held twice in one field is handed the stamp the node is left with each time`() {
        stateless.save(Claim("c1", "one"))
        val held = stateless.save(Claim("k", "held"))

        val saved = stateless.save(ClaimSupports(Claim("c1", "one"), supports = listOf(held.copy(text = "first"), held.copy(text = "last"))))

        assertEquals(listOf(stamp("k"), stamp("k")), saved.supports.map { it.stamp })
        assertEquals(listOf("k/last"), strings("MATCH (n:Claim {id: 'k'}) RETURN n.id + '/' + n.text"))
    }

    @Test
    fun `an object a batch holds twice is handed the stamp its node is left with each time`() {
        val first = stateless.save(Claim("c1", "one"))

        val saved = stateless.saveAll(listOf(first.copy(text = "two"), first.copy(text = "three")))

        assertEquals(listOf(stamp("c1"), stamp("c1")), saved.map { it.stamp })
        stateless.save(saved.first().copy(text = "four"))
    }

    // ----- An object that was not loaded -----

    @Test
    fun `an object built from scratch over a node that is there cannot replace the lists it never loaded`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"), Human("cy", "Cy"))))

        val scratch = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        assertEquals(Stamps.nodeToken(stamp("c1")) + ":", scratch.claim.stamp, "the node token alone")
        val refusal = assertFailsWith<StaleObjectException> { stateless.save(scratch, Replace.all()) }
        assertContains(refusal.message.orEmpty(), "saved over a node it had not loaded")
        assertFailsWith<StaleObjectException> { stateless.save(scratch, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->bob", "c1->cy"), edges("MENTIONS"))
        // Its own data is as its stamp says, so a save that only adds is applied.
        stateless.save(scratch.copy(claim = scratch.claim.copy(text = "two")))
    }

    @Test
    fun `an object built from scratch whose save makes the node can replace its lists`() {
        val made = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        assertEquals(stamp("c1"), made.claim.stamp)
        stateless.save(made.copy(people = listOf(Human("ada", "Ada"))), Replace.all())

        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    @Test
    fun `a related node built from scratch over a node that is there is handed the node token alone`() {
        stateless.save(ClaimSupports(Claim("k", "held"), supports = listOf(Claim("x", "x"))))

        val saved = stateless.save(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("k", "held"), Claim("new", "new"))))

        val (existing, made) = saved.supports
        assertEquals(Stamps.nodeToken(stamp("k")) + ":", existing.stamp)
        assertEquals(stamp("new"), made.stamp, "a node the save made is handed its whole stamp")
        assertFailsWith<StaleObjectException> { stateless.save(ClaimSupports(existing), Replace.all()) }
        assertEquals(listOf("c1->k", "c1->new", "k->x"), edges("SUPPORTS"))
    }

    @Test
    fun `a batch hands a fragment built from scratch over a node that is there the node token alone`() {
        stateless.save(Claim("c1", "one"))

        val (existing, made) = stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two")))

        assertEquals(Stamps.nodeToken(stamp("c1")) + ":", existing.stamp)
        assertEquals(stamp("c2"), made.stamp)
    }

    // ----- Property keys that look like parameters -----

    @Test
    fun `a bag key holding a dollar and a field's name is stored as given`() {
        stateless.save(Tagged("t1", "x", meta = mapOf("\$id" to "v", "cost\$text" to "w")))
        stateless.save(ClaimTags(Claim("c1", "one"), tags = listOf(Tagged("t2", "y", meta = mapOf("\$id" to "v")))))

        val keys = "MATCH (n:Tagged {id: '%s'}) UNWIND keys(n) AS k WITH k WHERE k STARTS WITH 'meta' RETURN k"
        assertEquals(listOf("meta.\$id", "meta.cost\$text"), strings(keys.format("t1")))
        assertEquals(listOf("meta.\$id"), strings(keys.format("t2")))
        assertEquals(mapOf("\$id" to "v", "cost\$text" to "w"), stateless.load("t1", Tagged::class.java)?.meta)
    }

    // ----- An update writes to the node it loaded -----

    @Test
    fun `update does not bring back a node deleted after its load`() {
        stateless.save(Memo("m1", "one"))

        val updated = stateless.update<Memo>("m1") {
            run("MATCH (m:Memo {id: 'm1'}) DETACH DELETE m")
            it.copy(text = "two")
        }

        assertNull(updated)
        assertEquals(emptyList(), strings("MATCH (m:Memo) RETURN m.id"))
    }

    @Test
    fun `update of a stamped node deleted after its load returns null on its only attempt`() {
        stateless.save(Claim("c1", "one"))

        val updated = stateless.update<Claim>("c1", attempts = 1) {
            run("MATCH (c:Claim {id: 'c1'}) DETACH DELETE c")
            it.copy(text = "two")
        }

        assertNull(updated)
        assertEquals(emptyList(), strings("MATCH (c:Claim) RETURN c.id"))
    }

    @Test
    fun `update of a stamped node deleted after its load returns null`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        val updated = stateless.update<ClaimView>("c1") {
            run("MATCH (c:Claim {id: 'c1'}) DETACH DELETE c")
            it.copy(claim = it.claim.copy(text = "two"), people = it.people + Human("bob", "Bob"))
        }

        assertNull(updated)
        assertEquals(emptyList(), strings("MATCH (c:Claim) RETURN c.id"))
        assertEquals(listOf("ada"), strings("MATCH (h:Human) RETURN h.id"), "nothing of the update was written")
    }

    @Test
    fun `a stale save the change makes itself is thrown on and the change is not run again`() {
        stateless.save(Claim("c1", "one"))
        val other = stateless.save(Claim("c2", "one"))
        stateless.save(other.copy(text = "two"))
        var runs = 0

        assertFailsWith<StaleObjectException> {
            stateless.update<Claim>("c1") {
                runs++
                stateless.save(other.copy(text = "stale"))
                it.copy(text = "two")
            }
        }

        assertEquals(1, runs)
    }

    @Test
    fun `update writes a view whose root is declared by the class it extends`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        val updated = stateless.update<InheritedClaimView>("c1") { InheritedClaimView(it.claim.copy(text = "two"), it.people + Human("bob", "Bob")) }

        assertEquals("two", assertNotNull(updated).claim.text)
        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"))
    }

    // ----- A batch whose objects hold each other -----

    @Test
    fun `a view refused because an earlier save of its batch changed its root says so`() {
        stateless.save(Claim("a", "A"))
        stateless.save(Claim("b", "B"))
        val a = assertNotNull(stateless.load("a", ClaimSupports::class.java))
        val b = assertNotNull(stateless.load("b", ClaimSupports::class.java))

        val refusal = assertFailsWith<StaleObjectException> {
            stateless.saveAll(listOf(a.copy(supports = listOf(b.claim)), b), Replace(ClaimSupports::supports))
        }

        assertTrue(refusal.bySameBatch)
        assertEquals("b", refusal.id)
        assertContains(refusal.message.orEmpty(), "Claim 'b' was changed by an earlier save of the same saveAll")
        if (atomicBatch) assertEquals(emptyList(), edges("SUPPORTS")) else assertEquals(listOf("a->b"), edges("SUPPORTS"))
    }

    @Test
    fun `a view given before the object that holds its root is saved, and handed the stamp the batch leaves`() {
        stateless.save(Claim("a", "A"))
        stateless.save(Claim("b", "B"))
        val a = assertNotNull(stateless.load("a", ClaimSupports::class.java))
        val b = assertNotNull(stateless.load("b", ClaimSupports::class.java))

        val (savedB, savedA) = stateless.saveAll(listOf(b, a.copy(supports = listOf(b.claim))), Replace(ClaimSupports::supports))

        assertEquals(listOf("a->b"), edges("SUPPORTS"))
        assertEquals(stamp("b"), savedB.claim.stamp)
        assertEquals(stamp("a"), savedA.claim.stamp)
        stateless.save(savedB, Replace(ClaimSupports::supports))
    }

    @Test
    fun `a view refused because another writer changed its root does not blame the batch`() {
        stateless.save(Claim("a", "A"))
        stateless.save(Claim("b", "B"))
        val a = assertNotNull(stateless.load("a", ClaimSupports::class.java))
        val b = assertNotNull(stateless.load("b", ClaimSupports::class.java))
        run("MATCH (b:Claim {id: 'b'}) SET b.text = 'changed', ${Stamps.setClause("b")}")

        val refusal = assertFailsWith<StaleObjectException> {
            stateless.saveAll(listOf(a, b), Replace(ClaimSupports::supports))
        }

        assertEquals(false, refusal.bySameBatch)
        assertContains(refusal.message.orEmpty(), "Claim 'b' was changed by another writer")
    }

    // ----- Several relationships between the same two nodes -----

    @Test
    fun `a save that rewrites one of two relationships between the same nodes gives both ends a new relationship token`() {
        stateless.save(Claim("c1", "one"))
        stateless.save(Human("ada", "Ada"))
        run("MATCH (c:Claim {id: 'c1'}), (h:Human {id: 'ada'}) CREATE (c)-[:CITES {page: 1}]->(h), (c)-[:CITES {page: 2}]->(h)")
        val loaded = assertNotNull(stateless.load("c1", Claim::class.java))
        val before = stamp("c1")
        val adaBefore = stamp("ada")

        stateless.save(ClaimCitations(loaded, cited = listOf(Citation(1, Human("ada", "Ada")))))

        assertEquals(listOf("1", "1"), strings("MATCH (:Claim)-[r:CITES]->(:Human) RETURN toString(r.page)"), "both were given the page")
        assertEquals(Stamps.nodeToken(before), Stamps.nodeToken(stamp("c1")))
        assertNotEquals(Stamps.linksToken(before), Stamps.linksToken(stamp("c1")))
        assertNotEquals(Stamps.linksToken(adaBefore), Stamps.linksToken(stamp("ada")))
    }

    @Test
    fun `a save that finds every relationship between the same nodes as it writes them keeps the relationship tokens`() {
        stateless.save(Claim("c1", "one"))
        stateless.save(Human("ada", "Ada"))
        run("MATCH (c:Claim {id: 'c1'}), (h:Human {id: 'ada'}) CREATE (c)-[:CITES {page: 1}]->(h), (c)-[:CITES {page: 1}]->(h)")
        val loaded = assertNotNull(stateless.load("c1", Claim::class.java))
        val before = stamp("c1")

        stateless.save(ClaimCitations(loaded, cited = listOf(Citation(1, Human("ada", "Ada")))))

        assertEquals(before, stamp("c1"))
    }

    // ----- Targets a replaced field does not own -----

    @Test
    fun `a node dropped from a replaced field and held by a nested view is not deleted as unreferenced`() {
        stateless.save(Board(Memo("m1", "memo"), attendees = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        run("MATCH (h:Human {id: 'ada'}) SET h.extra = 'kept'")

        stateless.save(
            Board(Memo("m1", "memo"), attendees = emptyList(), claims = listOf(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))),
            Replace(Board::attendees, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
        )

        assertEquals(listOf("ada/kept"), strings("MATCH (h:Human) RETURN h.id + '/' + coalesce(h.extra, '')"), "bob is deleted, ada is as she was")
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
        assertEquals(emptyList(), edges("LISTS"))
    }

    @Test
    fun `replacing a field keeps the relationships a field beside it holds to nodes both read`() {
        stateless.save(Claim("c1", "one"))
        run(
            """
            MATCH (c:Claim {id: 'c1'})
            CREATE (c)-[:ASSIGNED {since: 2020}]->(:Worker:Manager {id: 'mo', name: 'Mo', `${Stamps.PROPERTY}`: 'aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb'}),
                   (c)-[:ASSIGNED]->(:Worker {id: 'wes', name: 'Wes'}),
                   (c)-[:ASSIGNED]->(:Worker {id: 'gone', name: 'Gone'})
            """.trimIndent()
        )
        val mo = stamp("mo")

        stateless.save(
            ClaimStaff(Claim("c1", "one"), workers = listOf(Worker("wes", "Wes")), managers = listOf(Manager("mo", "Mo"))),
            Replace(ClaimStaff::workers),
        )

        assertEquals(listOf("c1->mo", "c1->wes"), edges("ASSIGNED"))
        assertEquals(listOf("2020"), strings("MATCH (:Claim)-[r:ASSIGNED]->(:Manager) RETURN toString(r.since)"), "the manager's relationship was not removed and made again")
        assertEquals(mo, stamp("mo"), "and so its relationship token stands")
    }

    // ----- An object that cannot carry its stamp -----

    @Test
    fun `an object that cannot be handed its stamp is refused before anything is written`() {
        val refusal = assertFailsWith<IllegalArgumentException> { stateless.save(Odd("o1", seed = 7)) }

        assertContains(refusal.message.orEmpty(), "Odd cannot be handed the stamp its save would leave, so it was not saved")
        assertEquals(emptyList(), strings("MATCH (n:Odd) RETURN n.id"))
        assertFailsWith<IllegalArgumentException> { stateless.saveAll(listOf(Odd("o2", seed = 7))) }
        assertEquals(emptyList(), strings("MATCH (n:Odd) RETURN n.id"))
    }

    @Test
    fun `a Java record is handed its stamp`() {
        val saved = stateless.save(JavaStampedRecord("r1", "one", null))

        assertEquals(stamp("r1"), saved.stamp())
        assertEquals("two", stateless.save(JavaStampedRecord("r1", "two", saved.stamp())).text())
    }

    // ----- A stored stamp that is not two tokens -----

    /** Stamps no save leaves: too short, too long, without its second token, and empty. */
    private val misshapen = listOf("short", "0123456789abcdef0123456789abcdef0", "0123456789abcdef:", "")

    private val twoTokens = Regex("[0-9a-f]{16}:[0-9a-f]{16}")

    @Test
    fun `a save over a stored stamp that is not two tokens leaves one that is and hands it back`() {
        misshapen.forEachIndexed { index, stored ->
            val id = "odd-$index"
            run("CREATE (:Claim {id: '$id', text: 'one', ${Stamps.QUOTED}: '$stored'})")
            val loaded = assertNotNull(stateless.load("$id", ClaimView::class.java))
            assertEquals(stored, loaded.claim.stamp)

            val saved = stateless.save(loaded.copy(claim = loaded.claim.copy(text = "two")))

            assertTrue(twoTokens.matches(stamp(id)), "the save over '$stored' left ${stamp(id)}")
            assertEquals(stamp(id), saved.claim.stamp, "the stamp handed back for '$stored'")
            stateless.save(saved.copy(people = listOf(Human("$id-h", "H"))), Replace(ClaimView::people))
            assertEquals(listOf("$id->$id-h"), edges("MENTIONS").filter { it.startsWith("$id->") })
        }
    }

    @Test
    fun `a stamp that is not two tokens is replaced by a save that changes nothing else, and by the clauses for Cypher`() {
        misshapen.forEachIndexed { index, stored ->
            val id = "odd-$index"
            run("CREATE (:Claim {id: '$id', text: 'one', ${Stamps.QUOTED}: '$stored'})")
            val loaded = assertNotNull(stateless.load("$id", Claim::class.java))

            val saved = stateless.save(loaded)
            assertEquals(stamp(id), saved.stamp)
            assertTrue(twoTokens.matches(stamp(id)), "the save over '$stored' left ${stamp(id)}")

            run("MATCH (c:Claim {id: '$id'}) SET c.${Stamps.QUOTED} = '$stored' SET ${Stamps.setClause("c")}")
            assertTrue(twoTokens.matches(stamp(id)), "setClause over '$stored' left ${stamp(id)}")
            assertFailsWith<StaleObjectException>("setClause on '$stored'") { stateless.save(loaded.copy(text = "late")) }

            run("MATCH (c:Claim {id: '$id'}) SET c.${Stamps.QUOTED} = '$stored' SET ${Stamps.linksClause("c")}")
            assertTrue(twoTokens.matches(stamp(id)), "linksClause over '$stored' left ${stamp(id)}")
            assertFailsWith<StaleObjectException>("linksClause on '$stored'") {
                stateless.save(ClaimView(loaded, people = emptyList()), Replace(ClaimView::people))
            }
            assertEquals("one", strings("MATCH (c:Claim {id: '$id'}) RETURN c.text").single())
        }
    }

    @Test
    fun `a relationship written to a node whose stamp is not two tokens leaves it one that is`() {
        run("CREATE (:Claim {id: 'k', text: 'held', ${Stamps.QUOTED}: 'short'})")

        val saved = stateless.save(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("k", "held"))))

        assertTrue(twoTokens.matches(stamp("k")), "left ${stamp("k")}")
        assertEquals(Stamps.nodeToken(stamp("k")) + ":", saved.supports.single().stamp, "it was there, and was not loaded")
    }

    // ----- Fields named for a save -----

    @Test
    fun `a property of another class than the one saved is refused`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            stateless.save(ClaimView(Claim("c1", "one")), only = setOf(Memo::text))
        }

        assertContains(refusal.message.orEmpty(), "Memo::text is not a field of Claim")
        assertEquals(emptyList(), strings("MATCH (n:Claim) RETURN n.id"))
    }
}

@Testcontainers
class StatelessSaveReviewNeo4jTest : StatelessSaveReviewContract() {
    companion object {
        private const val PASSWORD = "savereview"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-save-review", type = DatabaseType.NEO4J,
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
class StatelessSaveReviewFalkorDbTest : StatelessSaveReviewContract() {
    companion object {
        private const val GRAPH = "savereview"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-save-review", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val atomicBatch: Boolean = false
}

@Testcontainers
class StatelessSaveReviewMemgraphTest : StatelessSaveReviewContract() {
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
                name = "memgraph-save-review", type = DatabaseType.MEMGRAPH,
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
