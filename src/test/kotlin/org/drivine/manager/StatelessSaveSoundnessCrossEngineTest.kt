package org.drivine.manager

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveStatementBuilder
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
import sample.flatexpand.SeqExpandView
import sample.stateless.Claim
import org.drivine.session.SessionManager
import sample.stateless.Citation
import sample.stateless.ClaimCircle
import sample.stateless.ClaimCitations
import sample.stateless.ClaimRemarks
import sample.stateless.ClaimSupports
import sample.stateless.ClaimTagRemarks
import sample.stateless.Remark
import sample.stateless.TagRemark
import sample.stateless.Tagged
import sample.stateless.ClaimPeers
import sample.stateless.ClaimQueues
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimThreads
import sample.stateless.ClaimTickets
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.HumanClaims
import sample.stateless.Memo
import sample.stateless.MemoView
import sample.stateless.Thread
import sample.stateless.Ticket
import sample.stateless.UndeclaredPath
import sample.stateless.Widget
import sample.stateless.WidgetView

/**
 * What a stateless save must not do, verified on Neo4j, FalkorDB and Memgraph: write a relationship
 * its field does not read, delete a node the object still holds, miss a node by a renamed id, or hand
 * back a stamp that vouches for data the object never held.
 */
abstract class StatelessSaveSoundnessContract {

    abstract val pm: NonTransactionalPersistenceManager

    abstract val connections: ConnectionProvider

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun strings(cypher: String): List<String> =
        pm.query(QuerySpecification.withStatement(cypher).transform(String::class.java)).sorted()

    /** The property [key] of the node whose [idProperty] is [id], as a string; null when it has none. */
    private fun property(id: String, key: String, idProperty: String = "id"): String? = pm.query(
        QuerySpecification.withStatement("MATCH (n) WHERE n[\$idProperty] = \$id RETURN coalesce(toString(n[\$key]), '')")
            .bind(mapOf("id" to id, "key" to key, "idProperty" to idProperty)).transform(String::class.java)
    ).firstOrNull()?.takeIf { it.isNotEmpty() }

    /** Every relationship of [type], as `from->to`. */
    private fun edges(type: String): List<String> = pm.query(
        QuerySpecification.withStatement("MATCH (a)-[r]->(b) WHERE type(r) = \$type RETURN toString(a.id) + '->' + toString(b.id)")
            .bind(mapOf("type" to type)).transform(String::class.java)
    ).sorted()

    private fun ids(label: String): List<String> = strings("MATCH (n:$label) RETURN n.id")

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- Relationships a field does not read -----

    @Test
    fun `a field that reads several hops is not saved as relationships of one hop`() {
        run("CREATE (:Seq {id: 's1'})-[:NEXT]->(:Seq {id: 's2'})-[:NEXT]->(:Seq {id: 's3'})-[:NEXT]->(:Seq {id: 's4'})")
        val loaded = assertNotNull(stateless.load("s2", SeqExpandView::class.java))
        assertEquals(setOf("s3", "s4"), loaded.following.map { it.id }.toSet(), "the field reads past the first hop")

        stateless.save(loaded)

        assertEquals(listOf("s1->s2", "s2->s3", "s3->s4"), edges("NEXT"))
    }

    @Test
    fun `a field that reads several hops cannot be replaced`() {
        run("CREATE (:Seq {id: 's1'})-[:NEXT]->(:Seq {id: 's2'})")
        val loaded = assertNotNull(stateless.load("s1", SeqExpandView::class.java))

        assertFailsWith<IllegalArgumentException> { stateless.save(loaded, Replace(SeqExpandView::following)) }
    }

    // ----- Targets a replace deletes -----

    @Test
    fun `a target moved from one field to another is not deleted as unreferenced`() {
        val saved = stateless.save(ClaimQueues(Claim("c1", "one"), backlog = listOf(Human("ada", "Ada"))))
        run("MATCH (h:Human {id: 'ada'}) SET h.nick = 'A' CREATE (h)-[:WORKS_AT]->(:Company {id: 'acme', name: 'Acme'})")

        stateless.save(
            saved.copy(backlog = emptyList(), done = saved.backlog),
            Replace(ClaimQueues::backlog, ClaimQueues::done, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
        )

        assertEquals(emptyList(), edges("WAITING"))
        assertEquals(listOf("c1->ada"), edges("DONE"))
        assertEquals("A", property("ada", "nick"), "the node is the one that was there, with what it held")
        assertEquals(listOf("ada->acme"), edges("WORKS_AT"))
    }

    @Test
    fun `a target another field of the object still holds is not deleted as unreferenced`() {
        val ada = Human("ada", "Ada")
        val saved = stateless.save(ClaimCircle(Claim("c1", "one"), named = listOf(ada), endorsers = listOf(ada)))
        run("MATCH (h:Human {id: 'ada'}) SET h.nick = 'A'")

        stateless.save(
            saved.copy(endorsers = emptyList()),
            Replace(ClaimCircle::endorsers, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
        )

        assertEquals(listOf("ada"), ids("Human"))
        assertEquals("A", property("ada", "nick"), "the node is the one that was there, with what it held")
        assertEquals(listOf("c1->ada"), edges("NAMES"))
        assertEquals(emptyList(), edges("ENDORSES"))
    }

    @Test
    fun `a replace that names no field is refused`() {
        val view = ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada")))

        assertFailsWith<IllegalArgumentException> { stateless.save(view, Replace(removedTargets = RemovedTargets.DELETE_UNREFERENCED)) }
    }

    // ----- An id stored under another name -----

    @Test
    fun `a view whose root id is stored under another name is loaded, updated and deleted by id`() {
        stateless.save(WidgetView(Widget("w1", "one"), users = listOf(Human("ada", "Ada"))))

        val loaded = assertNotNull(stateless.load("w1", WidgetView::class.java), "loaded by id")
        assertEquals(listOf("ada"), loaded.users.map { it.id })

        assertNotNull(stateless.update<WidgetView>("w1") { it.copy(widget = it.widget.copy(name = "two")) }, "updated by id")
        assertEquals("two", property("w1", "name", idProperty = "widget_key"))

        assertEquals(1, stateless.delete("w1", WidgetView::class.java))
        assertEquals(emptyList(), strings("MATCH (n:Widget) RETURN n.widget_key"))
    }

    // ----- What a related node is written with -----

    @Test
    fun `a property declared transient is not written for a related node`() {
        stateless.save(ClaimThreads(Claim("c1", "one"), threads = listOf(Thread("t1", "hello"))))

        assertEquals("hello", property("t1", "title"))
        assertNull(property("t1", "shout"))
    }

    @Test
    fun `a property declared transient is not written by a batch`() {
        stateless.saveAll(listOf(Thread("t1", "hello"), Thread("t2", "there")))

        assertEquals("hello", property("t1", "title"))
        assertNull(property("t1", "shout"))
    }

    @Test
    fun `a target held twice by a field read in either direction is joined once`() {
        stateless.save(ClaimPeers(Claim("c1", "one"), peers = listOf(Claim("c2", "two"), Claim("c2", "two"))))

        assertEquals(listOf("c1->c2"), edges("PEER"))
    }

    @Test
    fun `update drops the relationship to a node whose id is a number`() {
        stateless.save(ClaimTickets(Claim("c1", "one"), tickets = listOf(Ticket(1, "first"), Ticket(2, "second"))))

        stateless.update<ClaimTickets>("c1") { view -> view.copy(tickets = view.tickets.filter { it.id == 1L }) }

        assertEquals(listOf("c1->1"), edges("TRACKS"))
    }

    // ----- What update compares -----

    @Test
    fun `update is checked against the stamp it loaded, whatever stamp the change returns`() {
        stateless.save(Claim("c1", "one"))

        assertFailsWith<StaleObjectException> {
            stateless.update<Claim>("c1", attempts = 1) {
                run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
                Claim(it.id, "mine")
            }
        }

        assertEquals("other", property("c1", "text"), "the other writer's change stands")
    }

    @Test
    fun `update refuses a change that gives the object another id`() {
        stateless.save(Claim("c1", "one"))

        assertFailsWith<IllegalArgumentException> { stateless.update<Claim>("c1") { it.copy(id = "c9", text = "moved") } }

        assertEquals(listOf("c1"), ids("Claim"))
        assertEquals("one", property("c1", "text"))
    }

    // ----- Stamps handed back -----

    @Test
    fun `update hands a related node back no stamp for data another writer changed`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "a", note = "n1"))))

        val updated = assertNotNull(
            stateless.update<HumanClaims>("ada") { view ->
                run("MATCH (c:Claim {id: 'c1'}) SET c.note = 'n2', ${Stamps.setClause("c")}")
                view.copy(claims = view.claims.map { it.copy(text = "b") })
            }
        )
        assertEquals("n2", property("c1", "note"), "update wrote only the field it altered")

        // The claim handed back still says n1, which it never saw replaced.
        assertFailsWith<StaleObjectException> { stateless.save(updated.claims.single()) }
        assertEquals("n2", property("c1", "note"))
    }

    @Test
    fun `a save hands a related node back no stamp for data another writer changed`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "a"))))
        val loaded = assertNotNull(stateless.load("ada", HumanClaims::class.java))
        run("MATCH (c:Claim {id: 'c1'}) SET c.note = 'n2', ${Stamps.setClause("c")}")

        val saved = stateless.save(loaded)

        // The claim handed back has no note: cleared, it would remove one it never saw.
        assertFailsWith<StaleObjectException> { stateless.save(saved.claims.single(), nullPolicy = NullPolicy.CLEAR) }
        assertEquals("n2", property("c1", "note"))
    }

    @Test
    fun `a batch hands an object back no stamp for data another writer changed`() {
        val loaded = stateless.save(Claim("c1", "one"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.note = 'n2', ${Stamps.setClause("c")}")

        val saved = stateless.saveAll(listOf(loaded.copy(text = "two"))).single()

        assertEquals("two", property("c1", "text"), "a batch is not checked")
        assertFailsWith<StaleObjectException> { stateless.save(saved, nullPolicy = NullPolicy.CLEAR) }
        assertEquals("n2", property("c1", "note"))
    }

    @Test
    fun `a save hands back the new stamp of a related node no other writer changed`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "a"))))
        val loaded = assertNotNull(stateless.load("ada", HumanClaims::class.java))

        val saved = stateless.save(loaded.copy(claims = loaded.claims.map { it.copy(text = "b") }))

        stateless.save(saved.claims.single().copy(text = "c"))
        assertEquals("c", property("c1", "text"))
    }

    // ----- A batch that replaces -----

    @Test
    fun `a batch that replaces a list is refused when a relationship was added since the view was loaded`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(:Human {id: 'bob', name: 'Bob'}) SET ${Stamps.linksClause("c")}")

        assertFailsWith<StaleObjectException> {
            stateless.saveAll(listOf(MemoView(Memo("m1", "memo")), loaded.copy(people = emptyList())), Replace(ClaimView::people))
        }

        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"), "no relationship the view never loaded was removed")
        // FalkorDB has no transactions: what the batch saved before the refusal stays.
        if (pm.type != DatabaseType.FALKORDB) assertEquals(emptyList(), ids("Memo"), "the batch was applied whole or not at all")
    }

    @Test
    fun `a batch replaces the lists of views that are as they were loaded`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        val saved = stateless.saveAll(listOf(loaded.copy(people = loaded.people.take(1))), Replace(ClaimView::people)).single()

        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
        stateless.save(saved.copy(people = emptyList()), Replace(ClaimView::people))
        assertEquals(emptyList(), edges("MENTIONS"))
    }

    // ----- A relationship's own properties -----

    private fun note(type: String): String? = strings("MATCH (:Claim {id: 'c1'})-[r:$type]->() RETURN coalesce(r.note, '')").single().ifEmpty { null }

    @Test
    fun `a save leaves a relationship's property alone where the object's is null`() {
        stateless.save(ClaimRemarks(Claim("c1", "one"), remarks = listOf(Remark("kept", Human("ada", "Ada")))))

        stateless.save(ClaimRemarks(Claim("c1", "one"), remarks = listOf(Remark(null, Human("ada", "Ada")))))

        assertEquals("kept", note("REMARKS"))
    }

    @Test
    fun `a save leaves a relationship's property alone where the object's is null, for a node with a part to itself`() {
        stateless.save(ClaimTagRemarks(Claim("c1", "one"), remarks = listOf(TagRemark("kept", Tagged("t1", "tag")))))

        stateless.save(ClaimTagRemarks(Claim("c1", "one"), remarks = listOf(TagRemark(null, Tagged("t1", "tag")))))

        assertEquals("kept", note("TAG_REMARKS"))
    }

    @Test
    fun `update clears a relationship's property the change set to null`() {
        stateless.save(ClaimRemarks(Claim("c1", "one"), remarks = listOf(Remark("gone", Human("ada", "Ada")))))

        stateless.update<ClaimRemarks>("c1") { view -> view.copy(remarks = view.remarks.map { it.copy(note = null) }) }

        assertNull(note("REMARKS"))
    }

    // ----- Several relationships between two nodes -----

    private fun linksToken(id: String): String? = property(id, Stamps.PROPERTY)?.substringAfter(':')

    @Test
    fun `relate marks both ends when it changes the properties of one of several relationships`() {
        stateless.save(Claim("c1", "one"))
        stateless.save(Claim("c2", "two"))
        val from = nodeRef<Claim>("c1")
        val to = nodeRef<Claim>("c2")
        stateless.edges.relate(from, to, "SUPPORTS", mapOf("weight" to 1), RelateMode.CREATE)
        stateless.edges.relate(from, to, "SUPPORTS", mapOf("weight" to 2), RelateMode.CREATE)
        val before = linksToken("c1") to linksToken("c2")

        stateless.edges.relate(from, to, "SUPPORTS", mapOf("weight" to 1))

        assertEquals(listOf("1", "1"), strings("MATCH (:Claim {id: 'c1'})-[r:SUPPORTS]->() RETURN toString(r.weight)"))
        assertNotEquals(before.first, linksToken("c1"), "one relationship's weight changed")
        assertNotEquals(before.second, linksToken("c2"))
    }

    @Test
    fun `a save marks the root when it changes the properties of one of several relationships`() {
        stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))))
        run("MATCH (c:Claim {id: 'c1'}), (h:Human {id: 'ada'}) CREATE (c)-[:CITES {page: 2}]->(h)")
        val before = linksToken("c1")

        stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))))

        assertEquals(listOf("1", "1"), strings("MATCH (:Claim {id: 'c1'})-[r:CITES]->() RETURN toString(r.page)"))
        assertNotEquals(before, linksToken("c1"), "one relationship's page changed")
    }

    // ----- The other manager -----

    @Test
    fun `GraphObjectManager marks neither end when it saves a relationship that is there as it is`() {
        val mapper = Neo4jObjectMapper.instance
        fun legacy() = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())
        val view = ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("c2", "two")))
        legacy().save(view)
        val before = linksToken("c1") to linksToken("c2")
        assertNotNull(before.first, "the first save marked the root")

        legacy().save(view)

        assertEquals(before, linksToken("c1") to linksToken("c2"))
    }

    // ----- Two writers at once -----

    /** Runs [first] and [second] at the same moment, and gives what each returned. */
    private fun <A, B> together(first: () -> A, second: () -> B): Pair<A, B> {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            fun <T> started(work: () -> T) = pool.submit<T> { ready.countDown(); go.await(); work() }
            val one = started(first)
            val two = started(second)
            ready.await()
            go.countDown()
            return one.get(30, TimeUnit.SECONDS) to two.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a related node written by two saves at once never keeps a stamp for data it no longer holds`() {
        repeat(40) { round ->
            val id = "k$round"
            stateless.save(Claim(id, "A"))
            // One save writes the text the node has, and so changes nothing if it runs first; the other changes it.
            val (_, changed) = together(
                { stateless.save(HumanClaims(Human("keeps$round", "Keeps"), claims = listOf(Claim(id, "A")))) },
                { stateless.save(HumanClaims(Human("changes$round", "Changes"), claims = listOf(Claim(id, "B")))) },
            )

            if (property(id, "text") == "A") {
                // The save that wrote A ran last, over B: the node's token is not the one B was stamped with.
                assertNotEquals(
                    changed.claims.single().stamp?.substringBefore(':'), property(id, Stamps.PROPERTY)?.substringBefore(':'),
                    "round $round: the node holds A under the stamp it was given for B",
                )
            }
        }
    }

    @Test
    fun `a relationship made again while a replace removes it marks the node it is made on`() {
        repeat(40) { round ->
            val claim = "r$round"
            val human = "p$round"
            val loaded = stateless.save(ClaimView(Claim(claim, "one"), people = listOf(Human(human, "Person"))))
            val (_, replaced) = together(
                { stateless.edges.relate(nodeRef<Claim>(claim), nodeRef<Human>(human), "MENTIONS") },
                { stateless.save(loaded.copy(people = emptyList()), Replace(ClaimView::people)) },
            )

            if (edges("MENTIONS").contains("$claim->$human")) {
                // relate ran last and made the relationship again: a replace of what the other save
                // handed back, which holds no relationship, must not pass for current.
                assertNotEquals(
                    replaced.claim.stamp?.substringAfter(':'), linksToken(claim),
                    "round $round: the claim has a relationship its stamp does not speak for",
                )
            }
        }
    }

    // ----- Inside a transaction -----

    @Test
    fun `a save in a transaction that read the node before another writer changed it is refused, and writes nothing`() {
        val loaded = stateless.save(Claim("c1", "one"))
        val connection = connections.connect()
        connection.startTransaction()
        try {
            // The transaction's first read. On Memgraph this is the snapshot it sees from here on.
            connection.query(QuerySpecification.withStatement("MATCH (c:Claim {id: 'c1'}) RETURN c.text").transform(String::class.java))
            run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
            val inTransaction = object : PersistenceManager by pm {
                // As a manager bound to a transaction runs a statement, and reports its failure.
                override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = try {
                    connection.query(spec)
                } catch (failure: Exception) {
                    throw DrivineException.withRootCause(failure, spec)
                }
            }
            val statement = SaveStatementBuilder(Neo4jObjectMapper.instance, pm.grammar, null)
                .build(loaded.copy(text = "late"), checked = true, NullPolicy.IGNORE)

            assertFailsWith<StaleObjectException> { SaveExecutor(inTransaction).save(statement) }
        } finally {
            runCatching { connection.rollbackTransaction() }
            connection.release()
        }

        assertEquals("other", property("c1", "text"))
    }

    // ----- An id that is a UUID -----

    @Test
    fun `update takes the id as a UUID`() {
        val id = UUID.randomUUID()
        stateless.save(Claim(id.toString(), "one"))

        assertNotNull(stateless.update<Claim>(id) { it.copy(text = "two") })

        assertEquals("two", property(id.toString(), "text"))
    }

    // ----- What a cascading delete follows -----

    @Test
    fun `a cascading delete leaves the nodes of a read-only field`() {
        run("CREATE (:Claim {id: 'c1', text: 'one'})-[:REVIEWED_BY]->(:Human {id: 'rev', name: 'Rev'})")

        stateless.delete("c1", ClaimReviewers::class.java, CascadeType.DELETE_ALL)

        assertEquals(emptyList(), ids("Claim"))
        assertEquals(listOf("rev"), ids("Human"))
    }

    @Test
    fun `a cascading delete does not follow a path field as one relationship`() {
        run("CREATE (:Claim {id: 'c1', text: 'one'})-[:MENTIONS]->(:Company {id: 'globex', name: 'Globex'})")

        stateless.delete("c1", UndeclaredPath::class.java, CascadeType.DELETE_ALL)

        assertEquals(emptyList(), ids("Claim"))
        assertEquals(listOf("globex"), ids("Company"))
    }
}

@Testcontainers
class StatelessSaveSoundnessNeo4jTest : StatelessSaveSoundnessContract() {
    companion object {
        private const val PASSWORD = "savesoundness"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-save-soundness", type = DatabaseType.NEO4J,
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
    override val connections: ConnectionProvider get() = provider
}

@Testcontainers
class StatelessSaveSoundnessFalkorDbTest : StatelessSaveSoundnessContract() {
    companion object {
        private const val GRAPH = "savesoundness"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-save-soundness", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val connections: ConnectionProvider get() = provider
}

@Testcontainers
class StatelessSaveSoundnessMemgraphTest : StatelessSaveSoundnessContract() {
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
                name = "memgraph-save-soundness", type = DatabaseType.MEMGRAPH,
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
    override val connections: ConnectionProvider get() = provider
}
