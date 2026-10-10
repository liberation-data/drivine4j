package org.drivine.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.HumanClaims
import sample.stateless.Memo

/**
 * The stamp, the choice of fields, `update` and `unrelate` on [StatelessGraphObjectManager],
 * verified on Neo4j, FalkorDB and Memgraph.
 *
 * Another writer is plain Cypher run between a load and a save: the manager cannot see it. Where
 * that writer keeps the contract it writes a new stamp, with [Stamps.setClause].
 */
abstract class StatelessSaveContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun property(id: String, key: String): String? = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN coalesce(toString(n[\$key]), '')")
            .bind(mapOf("id" to id, "key" to key)).transform(String::class.java)
    ).firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun mentioned(from: String): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH ({id: \$from})-[:MENTIONS]->(t:Human) RETURN t.id")
            .bind(mapOf("from" to from)).transform(String::class.java)
    ).toSet()

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- The stamp -----

    @Test
    fun `a save returns the object with a new stamp, and loading gives the same one`() {
        val saved = stateless.save(Claim("c1", "Ada founded Acme"))

        val stamp = assertNotNull(saved.stamp)
        assertEquals(stamp, stateless.load<Claim>("c1")?.stamp)
        assertEquals(stamp, property("c1", Stamps.PROPERTY))

        val again = stateless.save(saved.copy(text = "Ada founded Acme in 1999"))
        assertNotEquals(stamp, again.stamp, "a save that changes the node writes a new stamp")
    }

    @Test
    fun `a save is refused when another writer changed the node`() {
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'Bob founded Acme', ${Stamps.setClause("c")}")

        val stale = assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(note = "checked")) }

        assertFalse(stale.deleted)
        assertEquals(loaded.stamp, stale.expectedStamp)
        assertEquals(property("c1", Stamps.PROPERTY), stale.foundStamp)
        assertEquals("Bob founded Acme", property("c1", "text"), "nothing was written")
        assertNull(property("c1", "note"))
    }

    @Test
    fun `a save is refused when another writer deleted the node`() {
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))
        run("MATCH (c:Claim {id: 'c1'}) DETACH DELETE c")

        val stale = assertFailsWith<StaleObjectException> { stateless.save(loaded) }

        assertTrue(stale.deleted)
        assertNull(stateless.load<Claim>("c1"), "the node is not brought back with part of its properties")
    }

    @Test
    fun `the object passed to save is stale afterwards`() {
        val first = stateless.save(Claim("c1", "Ada founded Acme"))
        stateless.save(first.copy(text = "second"))

        assertFailsWith<StaleObjectException> { stateless.save(first.copy(text = "third")) }
        assertEquals("second", property("c1", "text"))
    }

    @Test
    fun `an object with no stamp is saved unchecked`() {
        stateless.save(Claim("c1", "Ada founded Acme"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'changed elsewhere', ${Stamps.setClause("c")}")

        stateless.save(Claim("c1", "built from scratch"))

        assertEquals("built from scratch", property("c1", "text"))
    }

    @Test
    fun `a type with no stamp field is saved unchecked and still stamped`() {
        stateless.save(Memo("m1", "Call Ada"))
        val loaded = assertNotNull(stateless.load<Memo>("m1"))
        run("MATCH (m:Memo {id: 'm1'}) SET m.text = 'changed elsewhere'")

        stateless.save(loaded.copy(text = "Call Bob"))

        assertEquals("Call Bob", property("m1", "text"))
        assertNotNull(property("m1", Stamps.PROPERTY), "another manager's checked save would notice this write")
    }

    @Test
    fun `a save through GraphObjectManager is noticed by a stateless save`() {
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))

        gom.save(Claim("c1", "saved by the other manager"))

        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(note = "late")) }
    }

    @Test
    fun `a view is checked by its root`() {
        val view = stateless.save(ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"))))
        assertNotNull(view.claim.stamp)
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'changed elsewhere', ${Stamps.setClause("c")}")

        assertFailsWith<StaleObjectException> {
            stateless.save(view.copy(people = view.people + Human("bob", "Bob")))
        }

        assertEquals(setOf("ada"), mentioned("c1"), "the root is saved first, so nothing else was written")
    }

    @Test
    fun `a batch save stamps each node`() {
        stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two")))

        val stamps = listOf("c1", "c2").map { assertNotNull(property(it, Stamps.PROPERTY)) }
        assertEquals(2, stamps.toSet().size)
    }

    @Test
    fun `of several writers saving the same loaded object at once, exactly one succeeds`() {
        val writers = 8
        repeat(25) { round ->
            val id = "race-$round"
            val loaded = stateless.save(Claim(id, "start"))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writers)
            try {
                val outcomes = (1..writers).map { writer ->
                    pool.submit<Claim?> {
                        start.await()
                        try {
                            stateless.save(loaded.copy(text = "writer $writer"))
                        } catch (stale: StaleObjectException) {
                            null
                        }
                    }
                }
                start.countDown()
                val saved = outcomes.map { it.get(60, TimeUnit.SECONDS) }.filterNotNull()

                assertEquals(1, saved.size, "round $round")
                assertEquals(saved.single().text, property(id, "text"))
                assertEquals(saved.single().stamp, property(id, Stamps.PROPERTY))
            } finally {
                pool.shutdown()
            }
        }
    }

    // ----- A stamp changes only when the node does -----

    @Test
    fun `a save that changes nothing keeps the stamp`() {
        val first = stateless.save(Claim("c1", "Ada founded Acme", note = "checked"))

        val again = stateless.save(first)
        val fromScratch = stateless.save(Claim("c1", "Ada founded Acme"))

        assertEquals(first.stamp, again.stamp)
        assertEquals(first.stamp, fromScratch.stamp, "a null field is not written, so nothing changed")
        assertEquals(first.stamp, property("c1", Stamps.PROPERTY))
        stateless.save(first.copy(text = "the first object is still current"))
    }

    @Test
    fun `clearing a field changes the stamp only when the field held a value`() {
        val first = stateless.save(Claim("c1", "Ada founded Acme", note = "checked"))

        val cleared = stateless.save(first.copy(note = null), nullPolicy = NullPolicy.CLEAR)
        val clearedAgain = stateless.save(cleared, nullPolicy = NullPolicy.CLEAR)

        assertNotEquals(first.stamp, cleared.stamp)
        assertEquals(cleared.stamp, clearedAgain.stamp)
        assertNull(property("c1", "note"))
    }

    @Test
    fun `a view save keeps the stamp of a node it did not change`() {
        stateless.save(Human("ada", "Ada"))
        stateless.save(Human("bob", "Bob"))
        val ada = assertNotNull(property("ada", Stamps.PROPERTY))
        val bob = assertNotNull(property("bob", Stamps.PROPERTY))

        val view = stateless.save(
            ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"), Human("bob", "Robert")))
        )

        val adaNow = assertNotNull(property("ada", Stamps.PROPERTY))
        assertEquals(ada.substringBefore(':'), adaNow.substringBefore(':'), "ada was linked, not changed")
        assertNotEquals(ada.substringAfter(':'), adaNow.substringAfter(':'), "ada has a new relationship")
        assertNotEquals(bob.substringBefore(':'), property("bob", Stamps.PROPERTY)?.substringBefore(':'), "bob's name changed")
        assertEquals("Robert", property("bob", "name"))

        val linked = stateless.save(view.copy(people = view.people + Human("cy", "Cy")))
        assertEquals(view.claim.stamp?.substringBefore(':'), linked.claim.stamp?.substringBefore(':'), "the root's own data is as it was")
        assertNotEquals(view.claim.stamp, linked.claim.stamp, "a new relationship gives the root a new relationship token")
        assertNotNull(property("cy", Stamps.PROPERTY), "a node the save created is stamped")

        val again = stateless.save(linked)
        assertEquals(linked.claim.stamp, again.claim.stamp, "saving the same relationships again changes nothing")
    }

    @Test
    fun `a batch save keeps the stamp of a node it did not change`() {
        stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two")))
        val before = listOf("c1", "c2").map { assertNotNull(property(it, Stamps.PROPERTY)) }

        stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two, changed"), Claim("c3", "three")))

        assertEquals(before[0], property("c1", Stamps.PROPERTY))
        assertNotEquals(before[1], property("c2", Stamps.PROPERTY))
        assertNotNull(property("c3", Stamps.PROPERTY))
    }

    @Test
    fun `a node saved before stamps existed is stamped by a save that changes nothing else`() {
        run("CREATE (:Claim {id: 'c1', text: 'Ada founded Acme'})")

        val saved = stateless.save(Claim("c1", "Ada founded Acme"))

        assertEquals(assertNotNull(saved.stamp), property("c1", Stamps.PROPERTY))
    }

    @Test
    fun `GraphObjectManager keeps the stamp of a node it did not change`() {
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))

        gom.save(Claim("c1", "Ada founded Acme"))

        stateless.save(loaded.copy(note = "still current"))
    }

    // ----- An incoming relationship field -----

    private fun mentions(): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH (a)-[:MENTIONS]->(b) RETURN a.id + '->' + b.id").transform(String::class.java)
    ).toSet()

    @Test
    fun `an incoming relationship field is written towards the root and loads back`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "Ada founded Acme"))))

        assertEquals(setOf("c1->ada"), mentions())
        assertEquals(listOf("c1"), stateless.load<HumanClaims>("ada")?.claims?.map { it.id })
    }

    @Test
    fun `GraphObjectManager removes an incoming relationship dropped from a loaded view`() {
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        run("CREATE (:Human {id: 'ada', name: 'Ada'}), (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'})")
        run("MATCH (c:Claim), (h:Human {id: 'ada'}) CREATE (c)-[:MENTIONS]->(h)")
        val loaded = assertNotNull(gom.load("ada", HumanClaims::class.java))

        gom.save(loaded.copy(claims = loaded.claims.filter { it.id == "c1" }))

        assertEquals(setOf("c1->ada"), mentions())
    }

    @Test
    fun `GraphObjectManager reconciles an incoming relationship field under DELETE_ORPHAN`() {
        assumeTrue(pm.type != DatabaseType.MEMGRAPH, "Memgraph has no DELETE_ORPHAN")
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        run("CREATE (:Human {id: 'ada', name: 'Ada'}), (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'})")
        run("MATCH (c:Claim), (h:Human {id: 'ada'}) CREATE (c)-[:MENTIONS]->(h)")

        gom.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "one"))), CascadeType.DELETE_ORPHAN)

        assertEquals(setOf("c1->ada"), mentions())
        assertEquals(listOf("c1"), stateless.loadAll<Claim>().map { it.id })
    }

    @Test
    fun `GraphObjectManager deletes a view by id under DELETE_ORPHAN`() {
        assumeTrue(pm.type != DatabaseType.MEMGRAPH, "Memgraph has no DELETE_ORPHAN")
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        run("CREATE (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'}), (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'})")
        run("MATCH (c:Claim {id: 'c1'}), (h:Human) CREATE (c)-[:MENTIONS]->(h)")
        run("MATCH (c:Claim {id: 'c2'}), (h:Human {id: 'bob'}) CREATE (c)-[:MENTIONS]->(h)")

        gom.delete("c1", ClaimView::class.java, null, CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("bob"), stateless.loadAll<Human>().map { it.id }, "ada was left with no relationship, bob is still mentioned")
        assertEquals(listOf("c2"), stateless.loadAll<Claim>().map { it.id })
    }

    @Test
    fun `DELETE_ORPHAN is refused on an engine without it, naming the engine's limit`() {
        assumeTrue(pm.type == DatabaseType.MEMGRAPH, "every other engine has DELETE_ORPHAN")
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        run("CREATE (:Claim {id: 'c1', text: 'one'})")

        val onSave = assertFailsWith<UnsupportedOperationException> {
            gom.save(ClaimView(Claim("c1", "one")), CascadeType.DELETE_ORPHAN)
        }
        assertFailsWith<UnsupportedOperationException> {
            gom.delete("c1", ClaimView::class.java, null, CascadeType.DELETE_ORPHAN)
        }

        assertTrue("Memgraph" in onSave.message.orEmpty(), onSave.message)
        assertFalse("FalkorDB" in onSave.message.orEmpty(), onSave.message)
        assertEquals(listOf("c1"), stateless.loadAll<Claim>().map { it.id })
    }

    // ----- only and except -----

    @Test
    fun `only writes just the named fields`() {
        val saved = stateless.save(Claim("c1", "Ada founded Acme", note = "first"))

        stateless.save(saved.copy(text = "ignored", note = "second"), only = setOf(Claim::note))

        assertEquals("Ada founded Acme", property("c1", "text"))
        assertEquals("second", property("c1", "note"))
    }

    @Test
    fun `except writes every field but the named ones`() {
        val saved = stateless.save(Claim("c1", "Ada founded Acme", note = "first"))

        stateless.save(saved.copy(text = "rewritten", note = "ignored"), except = setOf(Claim::note))

        assertEquals("rewritten", property("c1", "text"))
        assertEquals("first", property("c1", "note"))
    }

    @Test
    fun `a field left out is not cleared under CLEAR`() {
        val saved = stateless.save(Claim("c1", "Ada founded Acme", note = "first"))

        stateless.save(saved.copy(note = null), nullPolicy = NullPolicy.CLEAR, only = setOf(Claim::text))

        assertEquals("first", property("c1", "note"))
    }

    @Test
    fun `a field that does not exist is refused`() {
        assertFailsWith<IllegalArgumentException> {
            stateless.save(Claim("c1", "Ada founded Acme"), only = setOf(Human::name))
        }
        assertNull(stateless.load<Claim>("c1"), "nothing was written")
    }

    // ----- update -----

    @Test
    fun `update writes the fields the change altered and leaves the rest`() {
        stateless.save(Claim("c1", "Ada founded Acme", note = "first"))

        val updated = stateless.update<Claim>("c1") { loaded ->
            // Another writer changes a field this change does not touch, without a new stamp.
            run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'changed elsewhere'")
            loaded.copy(note = "second")
        }

        assertEquals("second", updated?.note)
        assertEquals("second", property("c1", "note"))
        assertEquals("changed elsewhere", property("c1", "text"), "text was not altered, so it was not written")
    }

    @Test
    fun `update clears a field the change set to null`() {
        stateless.save(Claim("c1", "Ada founded Acme", note = "first"))

        stateless.update<Claim>("c1") { it.copy(note = null) }

        assertNull(property("c1", "note"))
        assertEquals("Ada founded Acme", property("c1", "text"))
    }

    @Test
    fun `update loads again and re-applies the change when another writer got there first`() {
        stateless.save(Claim("c1", "Ada founded Acme"))
        var calls = 0

        val updated = stateless.update<Claim>("c1") { loaded ->
            if (calls++ == 0) run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'Bob founded Acme', ${Stamps.setClause("c")}")
            loaded.copy(note = "seen ${loaded.text}")
        }

        assertEquals(2, calls)
        assertEquals("seen Bob founded Acme", updated?.note, "the change was applied to the fresh object")
        assertEquals("Bob founded Acme", property("c1", "text"))
    }

    @Test
    fun `update gives up after its attempts`() {
        stateless.save(Claim("c1", "Ada founded Acme"))

        assertFailsWith<StaleObjectException> {
            stateless.update<Claim>("c1", attempts = 2) { loaded ->
                run("MATCH (c:Claim {id: 'c1'}) SET ${Stamps.setClause("c")}")
                loaded.copy(note = "never")
            }
        }
        assertNull(property("c1", "note"))
    }

    @Test
    fun `update returns null for a node that is not there`() {
        assertNull(stateless.update<Claim>("missing") { it.copy(note = "x") })
    }

    @Test
    fun `update on a view removes and adds relationships and keeps another writer's`() {
        stateless.save(ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        stateless.update<ClaimView>("c1") { loaded ->
            // Another writer mentions Cy after this load.
            run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(:Human {id: 'cy', name: 'Cy'})")
            loaded.copy(people = loaded.people.filter { it.id != "bob" } + Human("dee", "Dee"))
        }

        assertEquals(setOf("ada", "cy", "dee"), mentioned("c1"), "Bob dropped, Dee added, Cy kept")
    }

    // ----- unrelate -----

    @Test
    fun `unrelate removes one relationship and no node`() {
        stateless.save(ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        val stamp = assertNotNull(property("c1", Stamps.PROPERTY))

        val removed = stateless.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS")

        assertEquals(1, removed)
        assertEquals(setOf("ada"), mentioned("c1"))
        assertNotNull(stateless.load<Human>("bob"))
        val after = assertNotNull(property("c1", Stamps.PROPERTY))
        assertEquals(stamp.substringBefore(':'), after.substringBefore(':'), "the node's own data is as it was")
        assertNotEquals(stamp.substringAfter(':'), after.substringAfter(':'), "it lost a relationship")
        assertEquals(0, stateless.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS"))
        assertEquals(after, property("c1", Stamps.PROPERTY), "removing nothing changes no stamp")
    }

    @Test
    fun `unrelateAll removes every relationship of a type in a direction`() {
        stateless.save(ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        assertEquals(0, stateless.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS", Direction.INCOMING))
        assertEquals(2, stateless.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS"))

        assertEquals(emptySet(), mentioned("c1"))
        assertEquals(2, stateless.loadAll<Human>().size)
    }
}

@Testcontainers
class StatelessSaveNeo4jTest : StatelessSaveContract() {
    companion object {
        private const val PASSWORD = "statelesssave"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-stateless-save", type = DatabaseType.NEO4J,
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
class StatelessSaveFalkorDbTest : StatelessSaveContract() {
    companion object {
        private const val GRAPH = "statelesssave"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-stateless-save", host = container.host, port = container.getMappedPort(6379),
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
class StatelessSaveMemgraphTest : StatelessSaveContract() {
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
                name = "memgraph-stateless-save", type = DatabaseType.MEMGRAPH,
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
