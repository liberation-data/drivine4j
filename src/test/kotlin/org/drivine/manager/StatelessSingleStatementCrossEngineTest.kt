package org.drivine.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
import sample.stateless.CitedBy
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimBasis
import sample.stateless.ClaimCitations
import sample.stateless.ClaimDigest
import sample.stateless.ClaimLead
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimSupporters
import sample.stateless.ClaimSupports
import sample.stateless.ClaimTags
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Dossier
import sample.stateless.Draft
import sample.stateless.DraftBoard
import sample.stateless.Frozen
import sample.stateless.Human
import sample.stateless.HumanCitations
import sample.stateless.HumanClaims
import sample.stateless.HumanFollowers
import sample.stateless.Memo
import sample.stateless.MemoView
import sample.stateless.Pals
import sample.stateless.Tagged

/**
 * A stateless save as one statement, verified on Neo4j, FalkorDB and Memgraph: what it writes and
 * removes, the stamps it hands back, and what it refuses.
 */
abstract class StatelessSingleStatementContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun strings(cypher: String): Set<String> =
        pm.query(QuerySpecification.withStatement(cypher).transform(String::class.java)).toSet()

    private fun property(id: String, key: String): String? = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN coalesce(toString(n[\$key]), '')")
            .bind(mapOf("id" to id, "key" to key)).transform(String::class.java)
    ).firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun stamp(id: String): String? = property(id, Stamps.PROPERTY)

    /** Every relationship of [type], as `from->to`. */
    private fun edges(type: String): List<String> = pm.query(
        QuerySpecification.withStatement("MATCH (a)-[r]->(b) WHERE type(r) = \$type RETURN a.id + '->' + b.id")
            .bind(mapOf("type" to type)).transform(String::class.java)
    ).sorted()

    private fun ids(label: String): Set<String> = strings("MATCH (n:$label) RETURN n.id")

    /** Whether there is a node with [id]: [property] is null for a node that is gone as for a property that is. */
    private fun exists(id: String): Boolean = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN n.id").bind(mapOf("id" to id)).transform(String::class.java)
    ).isNotEmpty()

    /**
     * The whole graph as sorted lines: each node with its labels and every property, its stamp among
     * them, and each relationship with its ends and its properties.
     */
    private fun snapshot(): List<String> {
        fun line(values: Any?) = (values as Map<*, *>).entries.sortedBy { it.key.toString() }.joinToString { "${it.key}=${it.value}" }
        val nodes = pm.query(
            QuerySpecification.withStatement("MATCH (n) RETURN {labels: labels(n), properties: properties(n)} AS row").transform(Map::class.java)
        ).map { "(${(it["labels"] as List<*>).map(Any?::toString).sorted().joinToString(":")} ${line(it["properties"])})" }
        val relationships = pm.query(
            QuerySpecification.withStatement(
                "MATCH (a)-[r]->(b) RETURN {from: a.id, type: type(r), to: b.id, properties: properties(r)} AS row"
            ).transform(Map::class.java)
        ).map { "${it["from"]} -${it["type"]} ${line(it["properties"])}-> ${it["to"]}" }
        return (nodes + relationships).sorted()
    }

    /** How many statements [save] runs, through a manager of its own. */
    private fun statements(save: (StatelessGraphObjectManager) -> Unit): Int {
        val counting = CountingStatements(pm)
        save(StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry()))
        return counting.statements
    }

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- One statement -----

    @Test
    fun `a view save with relationships to replace and targets to delete is one statement`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        val counting = CountingStatements(pm)
        val manager = StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry())

        manager.save(
            ClaimView(Claim("c1", "two"), people = listOf(Human("ada", "Ada"), Human("cy", "Cy")), companies = listOf(Company("acme", "Acme"))),
            Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
        )

        assertEquals(1, counting.statements)
        assertEquals(listOf("c1->acme", "c1->ada", "c1->cy"), edges("MENTIONS"))
        assertEquals(setOf("ada", "cy"), ids("Human"))
    }

    @Test
    fun `a view save refused as stale writes nothing`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")

        assertFailsWith<StaleObjectException> {
            stateless.save(loaded.copy(people = listOf(Human("new", "New"))), Replace(ClaimView::people))
        }

        assertEquals(setOf("ada"), ids("Human"), "no node was made")
        assertEquals(listOf("c1->ada"), edges("MENTIONS"), "no relationship was removed or made")
    }

    // ----- Stamps handed back -----

    @Test
    fun `a view save hands back the stamp of each related node that has one`() {
        val claim = stateless.save(Claim("c1", "one"))

        val saved = stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(claim.copy(text = "edited"))))

        assertNotEquals(claim.stamp, saved.claims.single().stamp)
        assertEquals(stamp("c1"), saved.claims.single().stamp)
        stateless.save(saved.claims.single().copy(note = "saved on its own"))
        assertEquals("saved on its own", property("c1", "note"))
    }

    @Test
    fun `a related node is written unchecked`() {
        val claim = stateless.save(Claim("c1", "one"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.note = 'elsewhere', ${Stamps.setClause("c")}")

        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(claim.copy(text = "mine"))))

        assertEquals("mine", property("c1", "text"))
        assertEquals("elsewhere", property("c1", "note"))
    }

    @Test
    fun `saveAll hands back objects that can be saved again`() {
        val first = stateless.save(Claim("c1", "one"))

        val saved = stateless.saveAll(listOf(first.copy(text = "two"), Claim("c2", "new")))

        assertNotEquals(first.stamp, saved[0].stamp)
        assertEquals(stamp("c1"), saved[0].stamp)
        assertEquals(stamp("c2"), saved[1].stamp)
        stateless.save(saved[0].copy(note = "again"))
        assertEquals("again", property("c1", "note"))
    }

    @Test
    fun `saveAll writes over a stale stamp, and writes the rest`() {
        val loaded = stateless.save(Claim("c1", "one"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")

        val saved = stateless.saveAll(listOf(loaded.copy(text = "mine"), Claim("c2", "two"), Memo("m1", "memo")))

        assertEquals("mine", property("c1", "text"))
        assertEquals("two", property("c2", "text"))
        assertEquals("memo", property("m1", "text"))
        // The object never held what the other writer left, so its stamp does not come to vouch for it.
        assertEquals(loaded.stamp, (saved[0] as Claim).stamp)
        assertEquals(stamp("c2"), (saved[1] as Claim).stamp)
    }

    @Test
    fun `saveAll hands back a stamp that does not vouch for a relationship added since the object was loaded`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(stateless.load<Claim>("c1"))
        stateless.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "REVIEWED_BY")
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("bob", "Bob"))))

        val saved = stateless.saveAll(listOf(loaded.copy(text = "mine"))).single()

        assertEquals("mine", property("c1", "text"))
        assertEquals(stamp("c1")?.substringBefore(':'), saved.stamp?.substringBefore(':'))
        assertEquals(loaded.stamp?.substringAfter(':'), saved.stamp?.substringAfter(':'))
        assertFailsWith<StaleObjectException> {
            stateless.save(ClaimView(saved, people = listOf(Human("ada", "Ada"))), Replace(ClaimView::people))
        }
        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"))
        stateless.save(saved.copy(note = "its own data is still saved"))
    }

    @Test
    fun `saveAll replaces the relationships of each view`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("ada", "Ada"))))

        stateless.saveAll(
            listOf(
                ClaimView(Claim("c1", "one"), people = listOf(Human("bob", "Bob"))),
                ClaimView(Claim("c2", "two"), people = listOf(Human("cy", "Cy"))),
            ),
            Replace(ClaimView::people),
        )

        assertEquals(listOf("c1->bob", "c2->cy"), edges("MENTIONS"))
    }

    @Test
    fun `saveAll refuses Replace for an object that is not a view`() {
        val failure = assertFailsWith<IllegalArgumentException> { stateless.saveAll(listOf(Memo("m1", "memo")), Replace(MemoView::people)) }
        assertContains(failure.message.orEmpty(), "Memo is not one")
        assertFalse(exists("m1"), "nothing was written")
    }

    // ----- Classes that are not data classes -----

    @Test
    fun `a class whose fields can be set is given its stamp in place`() {
        val draft = Draft("d1", "one")

        val saved = stateless.save(draft)

        assertSame(draft, saved)
        assertEquals(stamp("d1"), draft.stamp)
        run("MATCH (d:Draft {id: 'd1'}) SET d.text = 'other', ${Stamps.setClause("d")}")
        assertFailsWith<StaleObjectException> { stateless.save(draft.apply { text = "mine" }) }
    }

    @Test
    fun `an immutable class that is not a data class is returned as a copy with its stamp`() {
        val saved = stateless.save(Frozen("f1", "one"))

        assertEquals(stamp("f1"), saved.stamp)
        assertEquals("one", saved.text)
        run("MATCH (f:Frozen {id: 'f1'}) SET f.text = 'other', ${Stamps.setClause("f")}")
        assertFailsWith<StaleObjectException> { stateless.save(saved) }
    }

    @Test
    fun `a node with a property bag loads its stamp, and a stale save of it is refused`() {
        stateless.save(Tagged("t1", "one", mapOf("source" to "web")))

        val loaded = assertNotNull(stateless.load<Tagged>("t1"))

        assertEquals(stamp("t1"), assertNotNull(loaded.stamp))
        assertEquals(mapOf<String, Any?>("source" to "web"), loaded.meta, "the stamp is not read into the bag")
        run("MATCH (t:Tagged {id: 't1'}) SET t.text = 'other', ${Stamps.setClause("t")}")
        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(text = "mine")) }
        assertEquals("other", property("t1", "text"))
    }

    @Test
    fun `saving a list or a property bag that has not changed keeps the stamp`() {
        val draft = stateless.save(Draft("d1", "one", tags = listOf("a", "b")))
        val first = draft.stamp
        stateless.save(draft)
        assertEquals(first, draft.stamp)
        stateless.save(draft.apply { tags = listOf("a", "c") })
        assertNotEquals(first, draft.stamp)

        val tagged = stateless.save(Tagged("t1", "one", mapOf("source" to "web")))
        assertEquals(tagged.stamp, stateless.save(tagged).stamp)
        assertNotEquals(tagged.stamp, stateless.save(tagged.copy(meta = mapOf("source" to "print"))).stamp)
    }

    // ----- update -----

    @Test
    fun `update saves a change made to the loaded object itself`() {
        stateless.save(Draft("d1", "before"))

        val updated = stateless.update<Draft>("d1") { it.text = "after"; it }

        assertEquals("after", property("d1", "text"))
        assertEquals(stamp("d1"), updated?.stamp)
    }

    @Test
    fun `update saves a relationship added to the loaded view itself`() {
        stateless.save(DraftBoard(Draft("d1", "before"), mutableListOf(Human("ada", "Ada"))))

        stateless.update<DraftBoard>("d1") { board ->
            board.draft.text = "after"
            board.people.add(Human("bob", "Bob"))
            board.people.removeIf { it.id == "ada" }
            board
        }

        assertEquals("after", property("d1", "text"))
        assertEquals(listOf("d1->bob"), edges("MENTIONS"))
    }

    @Test
    fun `update returns an object that can be saved again, and keeps the stamp when nothing changed`() {
        val saved = stateless.save(Claim("c1", "one"))

        val same = assertNotNull(stateless.update<Claim>("c1") { it })
        assertEquals(saved.stamp, same.stamp)

        val updated = assertNotNull(stateless.update<Claim>("c1") { it.copy(note = "noted") })
        assertEquals(stamp("c1"), updated.stamp)
        stateless.save(updated.copy(text = "two"))
        assertEquals("two", property("c1", "text"))
    }

    @Test
    fun `update with one attempt throws at once, and none is refused`() {
        stateless.save(Claim("c1", "one"))

        assertFailsWith<StaleObjectException> {
            stateless.update<Claim>("c1", attempts = 1) { loaded ->
                run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
                loaded.copy(note = "mine")
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> { stateless.update<Claim>("c1", attempts = 0) { it } }
        assertContains(failure.message.orEmpty(), "attempts must be positive")
    }

    // ----- Writers at once -----

    @Test
    fun `of several writers replacing the relationships of the same loaded view at once, exactly one succeeds`() {
        val writers = 6
        repeat(10) { round ->
            val id = "race-$round"
            val loaded = stateless.save(ClaimView(Claim(id, "start"), people = listOf(Human("$id-0", "Zero"))))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writers)
            try {
                // Each writer's manager is built before the start, so that only the saves race.
                val managers = (1..writers).associateWith { stateless }
                val outcomes = (1..writers).map { writer ->
                    pool.submit<Pair<Int, String?>?> {
                        start.await()
                        try {
                            writer to managers.getValue(writer).save(loaded.copy(people = listOf(Human("$id-$writer", "W$writer"))), Replace(ClaimView::people)).claim.stamp
                        } catch (stale: StaleObjectException) {
                            null
                        }
                    }
                }
                start.countDown()
                val saved = outcomes.map { it.get(60, TimeUnit.SECONDS) }.filterNotNull()

                assertEquals(1, saved.size, "round $round")
                val (winner, winnersStamp) = saved.single()
                assertEquals(listOf("$id->$id-$winner"), edges("MENTIONS").filter { it.startsWith("$id->") })
                assertEquals(winnersStamp, stamp(id))
            } finally {
                pool.shutdown()
            }
        }
    }

    @Test
    fun `every one of several updates of the same view at once is applied`() {
        val writers = 6
        stateless.save(ClaimView(Claim("c1", "start")))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writers)
        try {
            val managers = (1..writers).associateWith { stateless }
            val updates = (1..writers).map { writer ->
                pool.submit<ClaimView?> {
                    start.await()
                    managers.getValue(writer).update<ClaimView>("c1", attempts = writers * 3) { it.copy(people = it.people + Human("h$writer", "H$writer")) }
                }
            }
            start.countDown()
            updates.forEach { assertNotNull(it.get(120, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdown()
        }

        assertEquals((1..writers).map { "c1->h$it" }, edges("MENTIONS"))
    }

    @Test
    fun `update does not write a read-only field`() {
        stateless.save(ClaimReviewers(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:REVIEWED_BY]->(:Human {id: 'rev', name: 'Rev'})")

        stateless.update<ClaimReviewers>("c1") { it.copy(claim = it.claim.copy(text = "two"), reviewers = emptyList()) }

        assertEquals("two", property("c1", "text"))
        assertEquals(listOf("c1->rev"), edges("REVIEWED_BY"))
    }

    // ----- Relationships and the root's stamp -----

    @Test
    fun `of two writers who loaded a view, the second to replace its relationships is refused`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val mine = assertNotNull(stateless.load<ClaimView>("c1"))
        val theirs = assertNotNull(stateless.load<ClaimView>("c1"))

        stateless.save(theirs.copy(people = theirs.people + Human("bob", "Bob")))

        assertFailsWith<StaleObjectException> { stateless.save(mine, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"))
    }

    @Test
    fun `removing a relationship gives the root a new stamp, and removing none keeps it`() {
        val saved = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        val same = stateless.save(saved, Replace(ClaimView::people))
        assertEquals(saved.claim.stamp, same.claim.stamp)

        val fewer = stateless.save(same.copy(people = same.people.take(1)), Replace(ClaimView::people))
        assertNotEquals(same.claim.stamp, fewer.claim.stamp)
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    @Test
    fun `a relationship's property is changed without a second relationship, and gives the root a new stamp`() {
        val saved = stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(7, Human("ada", "Ada")))))
        assertEquals(saved.claim.stamp, stateless.save(saved).claim.stamp)

        val changed = stateless.save(saved.copy(cited = listOf(Citation(9, Human("ada", "Ada")))))

        assertNotEquals(saved.claim.stamp, changed.claim.stamp)
        assertEquals(setOf("9"), strings("MATCH (:Claim)-[r:CITES]->(:Human) RETURN toString(r.page)"))
        assertEquals(listOf("c1->ada"), edges("CITES"))
    }

    // ----- The two tokens of a stamp -----

    private fun nodeToken(stamp: String?) = assertNotNull(stamp).substringBefore(':')
    private fun linkToken(stamp: String?) = assertNotNull(stamp).substringAfter(':')

    @Test
    fun `a stamp is two tokens, and a save replaces the one for what it changed`() {
        val saved = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        assertTrue(Regex("[0-9a-f]{16}:[0-9a-f]{16}").matches(assertNotNull(saved.claim.stamp)), "was ${saved.claim.stamp}")

        val edited = stateless.save(saved.copy(claim = saved.claim.copy(text = "two")))
        assertNotEquals(nodeToken(saved.claim.stamp), nodeToken(edited.claim.stamp))
        assertEquals(linkToken(saved.claim.stamp), linkToken(edited.claim.stamp))

        val linked = stateless.save(edited.copy(people = edited.people + Human("bob", "Bob")))
        assertEquals(nodeToken(edited.claim.stamp), nodeToken(linked.claim.stamp))
        assertNotEquals(linkToken(edited.claim.stamp), linkToken(linked.claim.stamp))
        assertEquals(stamp("c1"), linked.claim.stamp)
    }

    @Test
    fun `a save that adds is applied though another writer added a relationship since it was loaded`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val mine = assertNotNull(stateless.load<ClaimView>("c1"))
        val theirs = assertNotNull(stateless.load<ClaimView>("c1"))

        stateless.save(theirs.copy(people = theirs.people + Human("bob", "Bob")))
        stateless.save(mine.copy(claim = mine.claim.copy(note = "mine"), people = mine.people + Human("cy", "Cy")))

        assertEquals(listOf("c1->ada", "c1->bob", "c1->cy"), edges("MENTIONS"))
        assertEquals("mine", property("c1", "note"))
    }

    @Test
    fun `several writers adding to the same loaded view at once are all applied`() {
        val writers = 4
        repeat(10) { round ->
            val id = "adds-$round"
            stateless.save(ClaimView(Claim(id, "one")))
            val loaded = assertNotNull(stateless.load<ClaimView>(id))
            val pool = Executors.newFixedThreadPool(writers)
            try {
                val start = CountDownLatch(1)
                val managers = (0 until writers).associateWith { stateless }
                val saves = (0 until writers).map { writer ->
                    pool.submit<Unit> {
                        start.await()
                        managers.getValue(writer).save(loaded.copy(people = listOf(Human("$id-h$writer", "Human $writer"))))
                    }
                }
                start.countDown()
                saves.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertEquals((0 until writers).map { "$id->$id-h$it" }, edges("MENTIONS").filter { it.startsWith("$id->") }, "round $round")
        }
    }

    @Test
    fun `a node's own data is saved though a relationship to it was written from its other end`() {
        val hub = stateless.save(Claim("hub", "Hub"))

        stateless.save(ClaimSupports(Claim("c1", "one"), supports = listOf(hub)))
        assertEquals(nodeToken(hub.stamp), nodeToken(stamp("hub")))
        assertNotEquals(linkToken(hub.stamp), linkToken(stamp("hub")))

        stateless.save(hub.copy(text = "Hub, renamed"))
        assertEquals("Hub, renamed", property("hub", "text"))
        assertEquals(listOf("c1->hub"), edges("SUPPORTS"))
    }

    @Test
    fun `Replace is refused when a relationship was written from the other end`() {
        stateless.save(ClaimSupporters(Claim("hub", "Hub"), supporters = listOf(Claim("s1", "one"))))
        val mine = assertNotNull(stateless.load<ClaimSupporters>("hub"))

        stateless.save(ClaimSupports(Claim("s2", "two"), supports = listOf(mine.claim)))

        assertFailsWith<StaleObjectException> { stateless.save(mine, Replace(ClaimSupporters::supporters)) }
        assertEquals(listOf("s1->hub", "s2->hub"), edges("SUPPORTS"))

        val fresh = assertNotNull(stateless.load<ClaimSupporters>("hub"))
        stateless.save(fresh.copy(supporters = fresh.supporters.filter { it.id == "s2" }), Replace(ClaimSupporters::supporters))
        assertEquals(listOf("s2->hub"), edges("SUPPORTS"))
    }

    @Test
    fun `a target that loses a relationship to Replace gets a new relationship token`() {
        stateless.save(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("s1", "one"), Claim("s2", "two"))))
        val s1 = stamp("s1")
        val s2 = stamp("s2")

        val loaded = assertNotNull(stateless.load<ClaimSupports>("c1"))
        stateless.save(loaded.copy(supports = loaded.supports.filter { it.id == "s1" }), Replace(ClaimSupports::supports))

        assertEquals(s1, stamp("s1"))
        assertEquals(nodeToken(s2), nodeToken(stamp("s2")))
        assertNotEquals(linkToken(s2), linkToken(stamp("s2")))
    }

    @Test
    fun `Replace is refused after edges adds or removes a relationship, and a save that adds is not`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(Human("cy", "Cy"))

        val beforeRemoval = assertNotNull(stateless.load<ClaimView>("c1"))
        stateless.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS")
        assertFailsWith<StaleObjectException> { stateless.save(beforeRemoval, Replace(ClaimView::people)) }

        val beforeAddition = assertNotNull(stateless.load<ClaimView>("c1"))
        stateless.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("cy"), "MENTIONS")
        assertFailsWith<StaleObjectException> { stateless.save(beforeAddition, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->cy"), edges("MENTIONS"))

        stateless.save(beforeAddition.copy(claim = beforeAddition.claim.copy(note = "still saved")))
        assertEquals("still saved", property("c1", "note"))
    }

    @Test
    fun `Replace is refused after GraphObjectManager wrote a relationship`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val mine = assertNotNull(stateless.load<ClaimView>("c1"))

        @Suppress("DEPRECATION")
        val gom = GraphObjectManager(pm, org.drivine.session.SessionManager(Neo4jObjectMapper.instance), Neo4jObjectMapper.instance, SubtypeRegistry())
        gom.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        assertFailsWith<StaleObjectException> { stateless.save(mine, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"))
    }

    @Test
    fun `Cypher that keeps the contract marks what it changed, and each save checks its own part`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        stateless.save(Human("bob", "Bob"))
        val loaded = assertNotNull(stateless.load<ClaimView>("c1"))

        run(
            """
            MATCH (c:Claim {id: 'c1'}), (h:Human {id: 'bob'})
            CREATE (c)-[:MENTIONS]->(h)
            SET ${Stamps.linksClause("c")}, ${Stamps.linksClause("h")}
            """.trimIndent()
        )
        assertEquals(nodeToken(loaded.claim.stamp), nodeToken(stamp("c1")))
        assertFailsWith<StaleObjectException> { stateless.save(loaded, Replace(ClaimView::people)) }
        stateless.save(loaded.copy(claim = loaded.claim.copy(note = "saved")))

        val again = assertNotNull(stateless.load<ClaimView>("c1"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'elsewhere', ${Stamps.setClause("c")}")
        val marked = assertNotNull(stamp("c1"))
        assertTrue(Regex("[0-9a-f]{16}:[0-9a-f]{16}").matches(marked), "was $marked")
        assertEquals(linkToken(again.claim.stamp), linkToken(marked))
        assertFailsWith<StaleObjectException> { stateless.save(again) }
    }

    // ----- Replace -----

    @Test
    fun `Replace on a field of relationships with properties removes what the list no longer holds`() {
        stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(7, Human("ada", "Ada")), Citation(8, Human("bob", "Bob")))))

        stateless.save(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(8, Human("bob", "Bob")))), Replace(ClaimCitations::cited))

        assertEquals(listOf("c1->bob"), edges("CITES"))
    }

    @Test
    fun `an incoming relationship with properties is written towards the root, and replaced`() {
        stateless.save(HumanCitations(Human("ada", "Ada"), citedBy = listOf(CitedBy(7, Claim("c1", "one")), CitedBy(8, Claim("c2", "two")))))
        assertEquals(listOf("c1->ada", "c2->ada"), edges("CITES"))
        assertEquals(setOf(7, 8), assertNotNull(stateless.load<HumanCitations>("ada")).citedBy.map { it.page }.toSet())

        stateless.save(HumanCitations(Human("ada", "Ada"), citedBy = listOf(CitedBy(7, Claim("c1", "one")))), Replace(HumanCitations::citedBy))

        assertEquals(listOf("c1->ada"), edges("CITES"))
    }

    @Test
    fun `Replace on an incoming field leaves a relationship that points the other way`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "one"))))
        run("MATCH (h:Human {id: 'ada'}), (c:Claim {id: 'c1'}) CREATE (h)-[:MENTIONS]->(c)")

        stateless.save(HumanClaims(Human("ada", "Ada")), Replace(HumanClaims::claims))

        assertEquals(listOf("ada->c1"), edges("MENTIONS"))
    }

    @Test
    fun `a single relationship set to null is removed by Replace and kept without it`() {
        val saved = stateless.save(ClaimLead(Claim("c1", "one"), lead = Human("ada", "Ada")))

        stateless.save(saved.copy(lead = null))
        assertEquals(listOf("c1->ada"), edges("LED_BY"))

        stateless.save(saved.copy(lead = null), Replace(ClaimLead::lead))
        assertEquals(emptyList(), edges("LED_BY"))
        assertEquals(setOf("ada"), ids("Human"))
    }

    @Test
    fun `DELETE_UNREFERENCED on an incoming field keeps a target that still points at another root`() {
        val shared = Claim("c1", "shared")
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(shared, Claim("c2", "ada's own"))))
        stateless.save(HumanClaims(Human("bob", "Bob"), claims = listOf(shared)))

        stateless.save(HumanClaims(Human("ada", "Ada")), Replace(HumanClaims::claims, removedTargets = RemovedTargets.DELETE_UNREFERENCED))

        assertEquals(listOf("c1->bob"), edges("MENTIONS"))
        assertEquals(setOf("c1"), ids("Claim"), "c2 pointed at nothing else and is deleted")
    }

    @Test
    fun `DELETE_UNREFERENCED on an outgoing field deletes a target nothing points at, whatever it points at`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("bob", "Bob"))))
        run("MATCH (h:Human {id: 'ada'}) CREATE (h)-[:WORKS_AT]->(:Company {id: 'acme'})")

        stateless.save(ClaimView(Claim("c1", "one")), Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED))

        assertEquals(setOf("bob"), ids("Human"), "bob is still mentioned by c2; ada pointed at a company and is deleted")
        assertEquals(listOf("c2->bob"), edges("MENTIONS"))
    }

    @Test
    fun `Replace of a list that is null is refused, and of an empty list removes every relationship`() {
        stateless.save(DraftBoard(Draft("d1", "one"), mutableListOf(Human("ada", "Ada"))))
        val board = DraftBoard(Draft("d1", "one")).also { nulled ->
            DraftBoard::class.java.getDeclaredField("people").apply { isAccessible = true }.set(nulled, null)
        }

        val failure = assertFailsWith<IllegalArgumentException> { stateless.save(board, Replace(DraftBoard::people)) }
        assertContains(failure.message.orEmpty(), "is null")
        assertEquals(listOf("d1->ada"), edges("MENTIONS"))

        stateless.save(DraftBoard(Draft("d1", "one")), Replace(DraftBoard::people))
        assertEquals(emptyList(), edges("MENTIONS"))
    }

    // ----- Undirected fields -----

    @Test
    fun `an undirected field is saved as one relationship, however often it is saved`() {
        val pals = Pals(Human("ada", "Ada"), pals = listOf(Human("bob", "Bob")))

        stateless.save(pals)
        stateless.save(pals)

        assertEquals(listOf("ada->bob"), edges("KNOWS"))
        assertEquals(listOf("bob"), assertNotNull(stateless.load<Pals>("ada")).pals.map { it.id })
    }

    @Test
    fun `an undirected field does not double a relationship stored towards the root`() {
        run("CREATE (:Human {id: 'bob', name: 'Bob'})-[:KNOWS]->(:Human {id: 'ada', name: 'Ada'})")

        stateless.save(Pals(Human("ada", "Ada"), pals = listOf(Human("bob", "Bob"))))

        assertEquals(listOf("bob->ada"), edges("KNOWS"))
    }

    @Test
    fun `Replace on an undirected field removes a relationship stored either way`() {
        run("CREATE (b:Human {id: 'bob', name: 'Bob'})-[:KNOWS]->(a:Human {id: 'ada', name: 'Ada'})-[:KNOWS]->(:Human {id: 'cy', name: 'Cy'})")

        stateless.save(Pals(Human("ada", "Ada")), Replace(Pals::pals))

        assertEquals(emptyList(), edges("KNOWS"))
        assertEquals(setOf("ada", "bob", "cy"), ids("Human"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `GraphObjectManager does not double an undirected relationship stored towards the root`() {
        run("CREATE (:Human {id: 'bob', name: 'Bob'})-[:KNOWS]->(:Human {id: 'ada', name: 'Ada'})")
        val gom = GraphObjectManager(pm, org.drivine.session.SessionManager(Neo4jObjectMapper.instance), Neo4jObjectMapper.instance, SubtypeRegistry())

        gom.save(Pals(Human("ada", "Ada"), pals = listOf(Human("bob", "Bob"))))

        assertEquals(listOf("bob->ada"), edges("KNOWS"))
    }

    // ----- only and except on a view -----

    @Test
    fun `only on a view names fields of its root, and its relationships are still written`() {
        stateless.save(ClaimView(Claim("c1", "one", note = "first")))

        stateless.save(ClaimView(Claim("c1", "two", note = "second"), people = listOf(Human("ada", "Ada"))), only = setOf(Claim::note))

        assertEquals("one", property("c1", "text"))
        assertEquals("second", property("c1", "note"))
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    // ----- What is refused -----

    @Test
    fun `a stamp on an object whose node has none is refused as changed, with no stamp found`() {
        val saved = stateless.save(Claim("c1", "one"))
        run("MATCH (c:Claim {id: 'c1'}) REMOVE c.${Stamps.QUOTED}")

        val failure = assertFailsWith<StaleObjectException> { stateless.save(saved.copy(text = "two")) }

        assertFalse(failure.deleted)
        assertNull(failure.foundStamp)
        assertEquals(saved.stamp, failure.expectedStamp)
        assertEquals(Claim::class.java, failure.type)
        assertEquals("c1", failure.id)
        assertContains(failure.message.orEmpty(), "Claim 'c1' was changed by another writer")
        assertContains(failure.message.orEmpty(), "found none")
    }

    @Test
    fun `arguments that cannot be applied are refused before anything is written`() {
        val both = assertFailsWith<IllegalArgumentException> {
            stateless.save(Claim("c1", "one"), only = setOf(Claim::text), except = setOf(Claim::note))
        }
        assertContains(both.message.orEmpty(), "only or except, not both")

        val notAView = assertFailsWith<IllegalArgumentException> { stateless.save(Claim("c1", "one"), Replace(ClaimView::people)) }
        assertContains(notAView.message.orEmpty(), "Claim is not one")

        val noSuchField = assertFailsWith<IllegalArgumentException> {
            stateless.save(ClaimView(Claim("c1", "one")), Replace(HumanClaims::claims))
        }
        assertContains(noSuchField.message.orEmpty(), "has no relationship field 'claims'")

        val noStampField = assertFailsWith<IllegalArgumentException> { stateless.save(MemoView(Memo("m1", "memo")), Replace.all()) }
        assertContains(noStampField.message.orEmpty(), "declares none")

        assertEquals(emptySet(), ids("Claim") + ids("Memo"))
    }

    // ----- What a stamp handed back vouches for -----

    @Test
    fun `a save that adds hands back a stamp Replace refuses, when another writer added a relationship first`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(stateless.load<ClaimView>("c1"))
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("bob", "Bob"))))

        val saved = stateless.save(loaded.copy(claim = loaded.claim.copy(note = "edited")))

        assertEquals(nodeToken(stamp("c1")), nodeToken(saved.claim.stamp), "the node's own data is as the object has it")
        assertEquals(linkToken(loaded.claim.stamp), linkToken(saved.claim.stamp), "the object never held bob")
        assertFailsWith<StaleObjectException> { stateless.save(saved, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->bob"), edges("MENTIONS"))
        // Its own data can still be saved.
        stateless.save(saved.copy(claim = saved.claim.copy(note = "again")))
        assertEquals("again", property("c1", "note"))
    }

    @Test
    fun `a save that adds hands back a stamp Replace accepts, when no other writer came between`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(stateless.load<ClaimView>("c1"))

        val saved = stateless.save(loaded.copy(people = loaded.people + Human("bob", "Bob")))

        assertEquals(stamp("c1"), saved.claim.stamp)
        stateless.save(saved.copy(people = saved.people.filter { it.id == "bob" }), Replace(ClaimView::people))
        assertEquals(listOf("c1->bob"), edges("MENTIONS"))
    }

    @Test
    fun `update hands back a stamp Replace refuses, when another writer added a relationship as it ran`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        val updated = assertNotNull(
            stateless.update<ClaimView>("c1") { loaded ->
                stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("bob", "Bob"))))
                loaded.copy(people = loaded.people + Human("cy", "Cy"))
            }
        )

        assertFailsWith<StaleObjectException> { stateless.save(updated, Replace(ClaimView::people)) }
        assertEquals(listOf("c1->ada", "c1->bob", "c1->cy"), edges("MENTIONS"))
    }

    @Test
    fun `a related node is handed back with the relationship token it was loaded with`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "one"))))
        stateless.save(Human("bob", "Bob"))
        val loaded = assertNotNull(stateless.load<HumanClaims>("ada"))
        stateless.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS")

        val saved = stateless.save(loaded.copy(claims = loaded.claims.map { it.copy(note = "edited") }))

        val claim = saved.claims.single()
        assertEquals(nodeToken(stamp("c1")), nodeToken(claim.stamp))
        assertEquals(linkToken(loaded.claims.single().stamp), linkToken(claim.stamp), "the claim was loaded before it mentioned bob")
        assertNotEquals(linkToken(stamp("c1")), linkToken(claim.stamp))
    }

    // ----- update, on a related node -----

    @Test
    fun `update clears a field set to null on a related node, and leaves a field another writer changed`() {
        stateless.save(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "one", note = "first"), Claim("c2", "two", note = "kept"))))

        stateless.update<HumanClaims>("ada") { loaded ->
            run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'changed elsewhere'")
            run("MATCH (c:Claim {id: 'c2'}) SET c.text = 'changed elsewhere'")
            loaded.copy(claims = loaded.claims.map { if (it.id == "c1") it.copy(note = null) else it })
        }

        assertNull(property("c1", "note"))
        assertEquals("changed elsewhere", property("c1", "text"), "text was not altered, so it was not written")
        assertEquals("changed elsewhere", property("c2", "text"), "c2 was not altered at all")
        assertEquals("kept", property("c2", "note"))
    }

    @Test
    fun `update writes what the change altered on a related node with a property bag, and drops a key it removed`() {
        stateless.save(ClaimTags(Claim("c1", "one"), tags = listOf(Tagged("t1", "tag", mapOf("source" to "web", "lang" to "en")))))

        stateless.update<ClaimTags>("c1") { loaded ->
            run("MATCH (t:Tagged {id: 't1'}) SET t.text = 'changed elsewhere'")
            loaded.copy(tags = loaded.tags.map { it.copy(meta = mapOf("source" to "web")) })
        }

        assertNull(property("t1", "meta.lang"))
        assertEquals("web", property("t1", "meta.source"))
        assertEquals("changed elsewhere", property("t1", "text"))
    }

    @Test
    fun `update clears a field set to null on the root of a nested view`() {
        stateless.save(Dossier(Memo("m1", "memo"), claims = listOf(ClaimView(Claim("c1", "one", note = "first"), people = listOf(Human("ada", "Ada"))))))

        stateless.update<Dossier>("m1") { loaded ->
            run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'changed elsewhere'")
            loaded.copy(claims = loaded.claims.map { it.copy(claim = it.claim.copy(note = null)) })
        }

        assertNull(property("c1", "note"))
        assertEquals("changed elsewhere", property("c1", "text"))
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    @Test
    fun `update of a node that dropped a property-bag key is a load and a save, and reads nothing else`() {
        stateless.save(Tagged("t1", "one", mapOf("source" to "web", "lang" to "en")))
        val counting = CountingStatements(pm)
        val manager = StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry())

        manager.update<Tagged>("t1") { it.copy(meta = mapOf("source" to "web")) }

        assertEquals(2, counting.statements)
        assertNull(property("t1", "meta.lang"))
        assertEquals("web", property("t1", "meta.source"))
    }

    // ----- Replace, at its edges -----

    @Test
    fun `Replace all is refused for a list that is null`() {
        val saved = stateless.save(DraftBoard(Draft("d1", "one"), mutableListOf(Human("ada", "Ada"))))
        DraftBoard::class.java.getDeclaredField("people").apply { isAccessible = true }.set(saved, null)

        val failure = assertFailsWith<IllegalArgumentException> { stateless.save(saved, Replace.all()) }

        assertContains(failure.message.orEmpty(), "is null")
        assertEquals(listOf("d1->ada"), edges("MENTIONS"))
    }

    @Test
    fun `DELETE_UNREFERENCED removes a relationship from the root to itself, and keeps the root`() {
        run("CREATE (a:Human {id: 'ada', name: 'Ada'}), (b:Human {id: 'bob', name: 'Bob'}), (a)-[:FOLLOWS]->(a), (b)-[:FOLLOWS]->(a)")

        stateless.save(HumanFollowers(Human("ada", "Ada")), Replace(HumanFollowers::followers, removedTargets = RemovedTargets.DELETE_UNREFERENCED))

        assertEquals(emptyList(), edges("FOLLOWS"))
        assertEquals(setOf("ada"), ids("Human"), "bob followed nothing else and is deleted; ada is the root")
    }

    // ----- edges -----

    @Test
    fun `relate marks both nodes when it makes a relationship or changes its properties, and neither when it finds it as it is`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val claim = nodeRef<Claim>("c1")
        val ada = nodeRef<Human>("ada")
        val (claimStamp, adaStamp) = stamp("c1") to stamp("ada")

        assertEquals(true, stateless.edges.relate(claim, ada, "MENTIONS"))
        assertEquals(claimStamp, stamp("c1"))
        assertEquals(adaStamp, stamp("ada"))

        stateless.edges.relate(claim, ada, "MENTIONS", mapOf("page" to 3))
        assertNotEquals(linkToken(claimStamp), linkToken(stamp("c1")))
        assertNotEquals(linkToken(adaStamp), linkToken(stamp("ada")))
        val (marked, adaMarked) = stamp("c1") to stamp("ada")

        stateless.edges.relate(claim, ada, "MENTIONS", mapOf("page" to 3))
        assertEquals(marked, stamp("c1"))
        assertEquals(adaMarked, stamp("ada"))
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
        assertEquals(false, stateless.edges.relate(claim, nodeRef<Human>("nobody"), "MENTIONS"))
    }

    @Test
    fun `unrelateAll removes incoming and undirected relationships, and leaves other types`() {
        run(
            """
            CREATE (a:Human {id: 'ada'}), (b:Human {id: 'bob'}), (c:Human {id: 'cy'}),
                   (b)-[:KNOWS]->(a), (a)-[:KNOWS]->(c), (b)-[:FOLLOWS]->(a)
            """.trimIndent()
        )
        val ada = nodeRef<Human>("ada")

        assertEquals(1, stateless.edges.unrelateAll(ada, "KNOWS", Direction.INCOMING))
        assertEquals(listOf("ada->cy"), edges("KNOWS"))
        run("MATCH (b:Human {id: 'bob'}), (a:Human {id: 'ada'}) CREATE (b)-[:KNOWS]->(a)")
        assertEquals(2, stateless.edges.unrelateAll(ada, "KNOWS", Direction.UNDIRECTED))
        assertEquals(listOf("bob->ada"), edges("FOLLOWS"))
        assertEquals(0, stateless.edges.unrelate(nodeRef<Human>("nobody"), ada, "FOLLOWS"))
    }

    // ----- One statement, whatever the shape -----

    @Test
    fun `a save is one statement, whatever the shape of the object`() {
        val shapes: Map<String, (StatelessGraphObjectManager) -> Unit> = linkedMapOf(
            "a fragment" to { it.save(Memo("m1", "memo")) },
            "a fragment with a stamp" to { it.save(Claim("c1", "one")) },
            "a fragment with a property bag" to { it.save(Tagged("t1", "one", mapOf("source" to "web"))) },
            "a relationship with properties" to { it.save(ClaimCitations(Claim("c2", "two"), cited = listOf(Citation(7, Human("ada", "Ada"))))) },
            "an incoming field" to { it.save(HumanClaims(Human("bob", "Bob"), claims = listOf(Claim("c3", "three")))) },
            "an undirected field" to { it.save(Pals(Human("cy", "Cy"), pals = listOf(Human("dan", "Dan")))) },
            "a view of views" to {
                it.save(Dossier(Memo("m2", "memo"), claims = listOf(ClaimView(Claim("c4", "four"), people = listOf(Human("eve", "Eve"))))))
            },
            "a relationship that holds one node" to { it.save(ClaimLead(Claim("c5", "five"), lead = Human("fay", "Fay"))) },
            "related nodes with a property bag" to { it.save(ClaimTags(Claim("c6", "six"), tags = listOf(Tagged("t2", "two", mapOf("k" to "v"))))) },
            "only" to { it.save(Claim("c1", "uno", note = "noted"), only = setOf(Claim::note)) },
            "except" to { it.save(Claim("c1", "uno", note = "left"), except = setOf(Claim::note)) },
            "CLEAR on a node with no property bag" to { it.save(Claim("c1", "one"), nullPolicy = NullPolicy.CLEAR) },
            "an empty list to replace" to { it.save(ClaimView(Claim("c2", "two")), Replace(ClaimView::people)) },
        )

        shapes.forEach { (shape, save) -> assertEquals(1, statements(save), shape) }

        assertNull(property("c1", "note"), "only wrote the note, except left it alone, and CLEAR cleared it")
        assertEquals("one", property("c1", "text"))
    }

    @Test
    fun `a save under CLEAR of a node with a property bag reads its keys first, and is two statements`() {
        stateless.save(Tagged("t1", "one", mapOf("source" to "web", "lang" to "en")))

        assertEquals(2, statements { it.save(Tagged("t1", "one", mapOf("source" to "web")), nullPolicy = NullPolicy.CLEAR) })

        assertNull(property("t1", "meta.lang"))
        assertEquals("web", property("t1", "meta.source"))
    }

    @Test
    fun `an update that changes nothing is a load and a save, and one of a node that is not there is a load`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val before = snapshot()

        assertEquals(2, statements { manager -> manager.update<ClaimView>("c1") { it } })
        assertEquals(before, snapshot(), "nothing was changed, and so no stamp was")

        assertEquals(1, statements { manager -> assertNull(manager.update<ClaimView>("nobody") { it }) })
    }

    // ----- A refused save, over the whole graph -----

    @Test
    fun `a fragment save refused as stale leaves the whole graph as it was`() {
        val loaded = stateless.save(Claim("c1", "one", note = "first"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
        val before = snapshot()

        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(text = "late", note = null), nullPolicy = NullPolicy.CLEAR) }

        assertEquals(before, snapshot())
    }

    @Test
    fun `a view save that replaces, deletes and changes a related node, refused as stale, leaves the whole graph as it was`() {
        val loaded = stateless.save(
            ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob")), companies = listOf(Company("acme", "Acme")))
        )
        val late = loaded.copy(
            claim = loaded.claim.copy(text = "late"), people = listOf(Human("ada", "Changed"), Human("new", "New")), companies = emptyList(),
        )

        // The root's own data changed.
        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
        val before = snapshot()
        assertFailsWith<StaleObjectException> { stateless.save(late, Replace.all(RemovedTargets.DELETE_UNREFERENCED)) }
        assertEquals(before, snapshot(), "after a change to the root")

        // A relationship of the root changed, and its data did not.
        val reloaded = assertNotNull(stateless.load<ClaimView>("c1"))
        run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(d:Human {id: 'dan', name: 'Dan'}) SET ${Stamps.linksClause("c")}, ${Stamps.linksClause("d")}")
        val withDan = snapshot()
        assertFailsWith<StaleObjectException> {
            stateless.save(
                reloaded.copy(claim = reloaded.claim.copy(text = "late"), people = listOf(Human("ada", "Changed"), Human("new", "New"))),
                Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
            )
        }
        assertEquals(withDan, snapshot(), "after a relationship was added")
    }

    @Test
    fun `a batch refused as stale leaves the whole graph as it was, on an engine that has transactions`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(b:Human {id: 'bob', name: 'Bob'}) SET ${Stamps.linksClause("c")}, ${Stamps.linksClause("b")}")
        val before = snapshot()

        assertFailsWith<StaleObjectException> {
            stateless.saveAll(
                listOf(
                    ClaimView(Claim("c0", "zero"), people = listOf(Human("ada", "Changed"), Human("cy", "Cy"))),
                    loaded.copy(claim = loaded.claim.copy(text = "late"), people = emptyList()),
                ),
                Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED),
            )
        }

        if (pm.type != DatabaseType.FALKORDB) {
            assertEquals(before, snapshot())
        } else {
            // FalkorDB has no transactions: the view saved before the refused one stays saved, and the refused one wrote nothing.
            assertEquals(listOf("c0->ada", "c0->cy", "c1->ada", "c1->bob"), edges("MENTIONS"))
            assertEquals("Changed", property("ada", "name"))
            assertEquals("one", property("c1", "text"))
            assertTrue(exists("bob"), "no target of the refused view was deleted")
        }
    }

    // ----- Stamps handed back, by every way a node is reached -----

    @Test
    fun `a stamped node reached through a relationship with properties is handed back its stamp`() {
        val saved = stateless.save(
            HumanCitations(Human("ada", "Ada"), citedBy = listOf(CitedBy(7, Claim("c1", "one")), CitedBy(8, Claim("c2", "two"))))
        )
        saved.citedBy.forEach { assertEquals(stamp(it.target.id), assertNotNull(it.target.stamp), it.target.id) }

        val loaded = assertNotNull(stateless.load<HumanCitations>("ada"))
        val edited = stateless.save(loaded.copy(citedBy = loaded.citedBy.map { it.copy(target = it.target.copy(text = "edited")) }))

        edited.citedBy.forEach { cited ->
            assertEquals(stamp(cited.target.id), cited.target.stamp, cited.target.id)
            assertNotEquals(loaded.citedBy.single { it.target.id == cited.target.id }.target.stamp, cited.target.stamp)
        }
        stateless.save(edited.citedBy.first { it.target.id == "c1" }.target.copy(note = "saved on its own"))
        assertEquals("saved on its own", property("c1", "note"))
    }

    @Test
    fun `stamps are handed back at each level of a view of views`() {
        val saved = stateless.save(
            ClaimDigest(Memo("m1", "memo"), parts = listOf(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("c2", "two")))))
        )

        val part = saved.parts.single()
        assertEquals(stamp("c1"), assertNotNull(part.claim.stamp), "the root of the nested view")
        assertEquals(stamp("c2"), assertNotNull(part.supports.single().stamp), "the node the nested view holds")
        assertEquals(listOf("c1->c2"), edges("SUPPORTS"))

        // Each is as the store has it: its own data can be saved, and the nested view's list replaced.
        stateless.save(part.supports.single().copy(text = "dos"))
        stateless.save(part.copy(supports = emptyList()), Replace(ClaimSupports::supports))
        assertEquals("dos", property("c2", "text"))
        assertEquals(emptyList(), edges("SUPPORTS"))
    }

    @Test
    fun `a stale root of a nested view is written unchecked, as any related node is, and keeps the stamp it had`() {
        stateless.save(ClaimDigest(Memo("m1", "memo"), parts = listOf(ClaimSupports(Claim("c1", "one")))))
        val loaded = assertNotNull(stateless.load<ClaimDigest>("m1"))
        run("MATCH (c:Claim {id: 'c1'}) SET c.note = 'elsewhere', ${Stamps.setClause("c")}")

        val saved = stateless.save(loaded.copy(parts = loaded.parts.map { it.copy(claim = it.claim.copy(text = "late")) }))

        assertEquals("late", property("c1", "text"))
        assertEquals("elsewhere", property("c1", "note"))
        assertEquals(loaded.parts.single().claim.stamp, saved.parts.single().claim.stamp, "it does not vouch for a change it never held")
        assertFailsWith<StaleObjectException> { stateless.save(saved.parts.single().claim.copy(text = "later")) }
    }

    @Test
    fun `a stamped node held by a relationship that holds one node is handed back its stamp`() {
        val saved = stateless.save(ClaimBasis(Claim("c1", "one"), basis = Claim("c2", "two")))

        assertEquals(stamp("c1"), saved.claim.stamp)
        assertEquals(stamp("c2"), assertNotNull(saved.basis?.stamp))
        assertEquals(listOf("c1->c2"), edges("RESTS_ON"))

        stateless.save(assertNotNull(saved.basis).copy(text = "dos"))
        assertEquals("dos", property("c2", "text"))
        stateless.save(saved.copy(basis = null), Replace(ClaimBasis::basis))
        assertEquals(emptyList(), edges("RESTS_ON"))
    }

    // ----- What a refusal says -----

    @Test
    fun `a Replace refused for a relationship says so, and one refused for a root that is gone says that`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        run("MATCH (c:Claim {id: 'c1'}) CREATE (c)-[:MENTIONS]->(b:Human {id: 'bob', name: 'Bob'}) SET ${Stamps.linksClause("c")}, ${Stamps.linksClause("b")}")

        val added = assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(people = emptyList()), Replace(ClaimView::people)) }

        assertFalse(added.deleted)
        assertEquals(Claim::class.java, added.type)
        assertEquals("c1", added.id)
        assertEquals(loaded.claim.stamp, added.expectedStamp)
        assertEquals(stamp("c1"), added.foundStamp)
        assertContains(added.message.orEmpty(), "Claim 'c1' had a relationship added or removed by another writer")

        run("MATCH (c:Claim {id: 'c1'}) DETACH DELETE c")

        val gone = assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(people = emptyList()), Replace(ClaimView::people)) }

        assertTrue(gone.deleted)
        assertNull(gone.foundStamp)
        assertContains(gone.message.orEmpty(), "Claim 'c1' was deleted by another writer")
        assertContains(gone.message.orEmpty(), "found no node")
        assertFalse(exists("c1"), "a refused save does not bring the node back")
    }

    // ----- Cypher that keeps the contract, on a node that has no stamp -----

    @Test
    fun `Cypher that keeps the contract gives a node that has no stamp a whole one`() {
        run("CREATE (:Claim {id: 'c1', text: 'one'}), (:Claim {id: 'c2', text: 'two'})")

        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'uno', ${Stamps.setClause("c")}")
        run("MATCH (c:Claim {id: 'c2'}) SET ${Stamps.linksClause("c")}")

        listOf("c1", "c2").forEach { id ->
            assertTrue(WELL_FORMED.matches(assertNotNull(stamp(id))), "$id has ${stamp(id)}")
            val loaded = assertNotNull(stateless.load<Claim>(id))
            assertEquals(stamp(id), loaded.stamp)
            stateless.save(loaded.copy(note = "checked"))
            assertEquals("checked", property(id, "note"))
        }
    }

    // ----- A stored stamp that is not two tokens -----

    /** Stamps no save leaves: too short, too long, without its second token, and empty. */
    private val malformed = listOf("short", "0123456789abcdef0123456789abcdef0", "0123456789abcdef:", "")

    @Test
    fun `a change by another writer is seen though the stored stamp was not two tokens`() {
        malformed.forEachIndexed { index, stored ->
            val id = "odd-$index"
            run("CREATE (:Claim {id: '$id', text: 'one', ${Stamps.QUOTED}: '$stored'})")
            val loaded = assertNotNull(stateless.load<ClaimView>(id))
            assertEquals(stored, loaded.claim.stamp)

            run("MATCH (c:Claim {id: '$id'}) SET c.note = 'elsewhere', ${Stamps.setClause("c")}")
            assertFailsWith<StaleObjectException>("setClause on '$stored'") { stateless.save(loaded.copy(claim = loaded.claim.copy(text = "late"))) }

            run("MATCH (c:Claim {id: '$id'}) SET c.${Stamps.QUOTED} = '$stored'")
            run("MATCH (c:Claim {id: '$id'}) SET ${Stamps.linksClause("c")}")
            assertFailsWith<StaleObjectException>("linksClause on '$stored'") {
                stateless.save(loaded.copy(people = emptyList()), Replace(ClaimView::people))
            }
            assertEquals("one", property(id, "text"))
        }
    }

    @Test
    fun `a save over a stored stamp that is not two tokens leaves one that is, and hands it back`() {
        val failures = malformed.mapIndexedNotNull { index, stored ->
            val id = "odd-$index"
            run("CREATE (:Claim {id: '$id', text: 'one', ${Stamps.QUOTED}: '$stored'})")
            val loaded = assertNotNull(stateless.load<ClaimView>(id))
            runCatching {
                val saved = stateless.save(loaded.copy(claim = loaded.claim.copy(text = "two")))
                assertEquals("two", property(id, "text"))
                assertTrue(WELL_FORMED.matches(assertNotNull(stamp(id))), "the save left ${stamp(id)}")
                assertEquals(stamp(id), saved.claim.stamp, "the stamp handed back")
                stateless.save(saved.copy(people = listOf(Human("$id-h", "H"))), Replace(ClaimView::people))
                assertEquals(listOf("$id->$id-h"), edges("MENTIONS").filter { it.startsWith("$id->") })
            }.exceptionOrNull()?.let { "'$stored': ${it.message?.lineSequence()?.first()}" }
        }

        assertEquals(emptyList(), failures)
    }

    // ----- only and except, at their edges -----

    @Test
    fun `only and except name fields of the root, and a relationship field is refused`() {
        val view = ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada")))

        val only = assertFailsWith<IllegalArgumentException> { stateless.save(view, only = setOf(ClaimView::people)) }
        val except = assertFailsWith<IllegalArgumentException> { stateless.save(view, except = setOf(ClaimView::people)) }

        assertContains(only.message.orEmpty(), "Claim has no field 'people' to save")
        assertContains(except.message.orEmpty(), "Claim has no field 'people' to save")
        assertEquals(emptySet(), ids("Claim") + ids("Human"), "nothing was written")
    }

    @Test
    fun `except on a view leaves a field of its root unwritten, and its relationships are still written`() {
        stateless.save(ClaimView(Claim("c1", "one", note = "first")))

        stateless.save(ClaimView(Claim("c1", "two", note = "second"), people = listOf(Human("ada", "Ada"))), except = setOf(Claim::note))

        assertEquals("two", property("c1", "text"))
        assertEquals("first", property("c1", "note"))
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    @Test
    fun `only that names the id or the stamp writes no other field, and the save is checked all the same`() {
        val saved = stateless.save(Claim("c1", "one", note = "first"))

        val byId = stateless.save(saved.copy(text = "two", note = "second"), only = setOf(Claim::id))
        val byStamp = stateless.save(byId.copy(text = "three", stamp = byId.stamp), only = setOf(Claim::stamp))

        assertEquals(setOf("c1"), ids("Claim"))
        assertEquals("one", property("c1", "text"))
        assertEquals("first", property("c1", "note"))
        assertEquals(stamp("c1"), byStamp.stamp, "the stamp is the store's, never the object's")
        assertTrue(WELL_FORMED.matches(assertNotNull(stamp("c1"))))

        run("MATCH (c:Claim {id: 'c1'}) SET c.text = 'other', ${Stamps.setClause("c")}")
        assertFailsWith<StaleObjectException> { stateless.save(byStamp.copy(note = "late"), only = setOf(Claim::stamp)) }
        assertFailsWith<StaleObjectException> { stateless.save(byStamp.copy(note = "late"), except = setOf(Claim::stamp)) }
        assertEquals("first", property("c1", "note"))
    }

    @Test
    fun `only that names a property bag writes the bag and no other field`() {
        stateless.save(Tagged("t1", "one", mapOf("source" to "web")))

        stateless.save(Tagged("t1", "two", mapOf("source" to "wire", "lang" to "en")), only = setOf(Tagged::meta))
        assertEquals("one", property("t1", "text"))
        assertEquals("wire", property("t1", "meta.source"))
        assertEquals("en", property("t1", "meta.lang"))

        stateless.save(Tagged("t1", "three", mapOf("source" to "post")), except = setOf(Tagged::meta))
        assertEquals("three", property("t1", "text"))
        assertEquals("wire", property("t1", "meta.source"))
    }

    // ----- update, at its edges -----

    @Test
    fun `a change that throws writes nothing, and is not applied again`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val before = snapshot()
        var applied = 0

        val failure = assertFailsWith<IllegalStateException> {
            stateless.update<ClaimView>("c1") {
                applied++
                error("the change failed")
            }
        }

        assertEquals("the change failed", failure.message)
        assertEquals(1, applied)
        assertEquals(before, snapshot())
    }

    @Test
    fun `a change that returns an object of another class is refused, and nothing is written`() {
        stateless.save(Claim("c1", "one"))
        val before = snapshot()
        @Suppress("UNCHECKED_CAST")
        val change = { _: Claim -> Memo("c1", "a memo") } as (Claim) -> Claim

        val failure = assertFailsWith<IllegalArgumentException> { stateless.update("c1", Claim::class.java, change = change) }

        assertContains(failure.message.orEmpty(), "The change returned a Memo for a Claim")
        assertEquals(before, snapshot())
    }

    @Test
    fun `update of a type with no stamp does not notice another writer, and writes what it altered over the change`() {
        stateless.save(Memo("m1", "one"))

        val updated = stateless.update<Memo>("m1") { loaded ->
            run("MATCH (m:Memo {id: 'm1'}) SET m.text = 'other'")
            loaded.copy(text = "mine")
        }

        assertEquals("mine", assertNotNull(updated).text)
        assertEquals("mine", property("m1", "text"), "with no stamp to compare, the other writer's change is not noticed")
    }

    // ----- A manager used again -----

    @Test
    fun `a manager used again keeps nothing of what it loaded or saved`() {
        val manager = stateless
        manager.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(manager.load<ClaimView>("c1"))
        run(
            """
            MATCH (c:Claim {id: 'c1'})
            CREATE (c)-[:MENTIONS]->(b:Human {id: 'bob', name: 'Bob'})
            SET c.text = 'other', ${Stamps.setClause("c")}, ${Stamps.linksClause("b")}
            """.trimIndent()
        )

        assertFailsWith<StaleObjectException> { manager.save(loaded.copy(claim = loaded.claim.copy(note = "late"))) }

        val fresh = assertNotNull(manager.load<ClaimView>("c1"))
        assertEquals("other", fresh.claim.text, "the load reads the store, not what the manager saved")
        assertEquals(setOf("ada", "bob"), fresh.people.map { it.id }.toSet())
        assertEquals(stamp("c1"), fresh.claim.stamp)

        // An object it never loaded is written as given: nothing it loaded before decides what is removed.
        manager.save(ClaimView(Claim("c1", "again"), people = listOf(Human("cy", "Cy"))))
        assertEquals(listOf("c1->ada", "c1->bob", "c1->cy"), edges("MENTIONS"))

        manager.save(assertNotNull(manager.load<ClaimView>("c1")).let { it.copy(people = it.people.filter { human -> human.id == "ada" }) }, Replace(ClaimView::people))
        assertEquals(listOf("c1->ada"), edges("MENTIONS"))
    }

    // ----- edges, and the nodes it marks -----

    @Test
    fun `unrelate and unrelateAll give each node that lost a relationship a new relationship token, and no other node`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"), Human("cy", "Cy"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("dan", "Dan"))))
        val loaded = assertNotNull(stateless.load<ClaimView>("c1"))
        val all = listOf("c1", "c2", "ada", "bob", "cy", "dan")
        fun stamps() = all.associateWith { assertNotNull(stamp(it), it) }
        fun changed(before: Map<String, String>, token: (String) -> String) = all.filter { token(before.getValue(it)) != token(stamps().getValue(it)) }.toSet()
        val start = stamps()

        assertEquals(1, stateless.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "MENTIONS"))

        assertEquals(setOf("c1", "ada"), changed(start) { it.substringAfter(':') }, "both ends of the relationship removed")
        assertEquals(emptySet(), changed(start) { it.substringBefore(':') }, "no node's own data changed")
        val afterOne = stamps()

        assertEquals(2, stateless.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS"))

        assertEquals(setOf("c1", "bob", "cy"), changed(afterOne) { it.substringAfter(':') }, "the node and each former neighbour")
        assertEquals(emptySet(), changed(afterOne) { it.substringBefore(':') })
        val afterAll = stamps()

        assertEquals(0, stateless.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS"))
        assertEquals(afterAll, stamps(), "removing nothing marks nothing")
        assertFailsWith<StaleObjectException> { stateless.save(loaded, Replace(ClaimView::people)) }
        assertEquals(listOf("c2->dan"), edges("MENTIONS"))
    }
}

/** A stamp as a save or [Stamps] leaves it: two tokens of sixteen hex digits. */
private val WELL_FORMED = Regex("[0-9a-f]{16}:[0-9a-f]{16}")

/** Decorates a [PersistenceManager], counting the statements run through it. */
private class CountingStatements(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var statements = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        statements++
        return delegate.query(spec)
    }

    override fun execute(spec: QuerySpecification<*>) {
        statements++
        delegate.execute(spec)
    }

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T {
        statements++
        return delegate.getOne(spec)
    }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        statements++
        return delegate.maybeGetOne(spec)
    }

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): java.util.Optional<T> {
        statements++
        return delegate.optionalGetOne(spec)
    }

    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        statements += specs.size
        delegate.executeBatch(specs)
    }

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        statements += specs.size
        return delegate.queryBatch(specs)
    }
}

@Testcontainers
class StatelessSingleStatementNeo4jTest : StatelessSingleStatementContract() {
    companion object {
        private const val PASSWORD = "singlestatement"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-single-statement", type = DatabaseType.NEO4J,
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
class StatelessSingleStatementFalkorDbTest : StatelessSingleStatementContract() {
    companion object {
        private const val GRAPH = "singlestatement"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-single-statement", host = container.host, port = container.getMappedPort(6379),
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
class StatelessSingleStatementMemgraphTest : StatelessSingleStatementContract() {
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
                name = "memgraph-single-statement", type = DatabaseType.MEMGRAPH,
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
