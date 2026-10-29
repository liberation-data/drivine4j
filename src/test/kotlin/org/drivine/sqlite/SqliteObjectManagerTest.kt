package org.drivine.sqlite

import org.drivine.StaleObjectException
import org.drivine.manager.CascadeType
import org.drivine.manager.NullPolicy
import org.drivine.manager.PatchResult
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.manager.Replace
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.nodeRef
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.manager.TemporalsNode
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.query.dsl.any
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.containsIgnoreCase
import org.drivine.query.dsl.hasItem
import org.drivine.query.dsl.none
import org.drivine.query.dsl.notIn
import org.drivine.query.dsl.query
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
import sample.groups.Feedback
import sample.groups.FeedbackQueryDsl
import sample.mapped.view.DeletableSession
import sample.mapped.view.DeletableSessionTree
import sample.nodelabels.MemberNode
import sample.nodelabels.MixedBagNode
import sample.nodelabels.Role
import sample.nodelabels.ThingNode
import sample.patch.ClaimQueryDsl
import sample.patch.PatchSession
import sample.patch.PatchSessionQueryDsl
import sample.proposition.Mention
import sample.proposition.PropositionNode
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Human
import sample.stateless.Memo
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `StatelessGraphObjectManager` on SQLite, end to end and without Cypher: what is written is read back
 * from the tables, as SQL. The cross-engine contracts seed and read back in Cypher; this covers, besides,
 * saving, stamping and checking, patching, relationships on their own, the query DSL, views with
 * relationships, paths and recursion, cascading deletes, and the searches, by what the tables hold.
 */
class SqliteObjectManagerTest {

    private val pm = SqliteTestDatabase.graphManager("ogm")
    private val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val seed = SqliteSeed(pm)

    private fun <T : Any> one(sql: String, vararg values: Pair<String, Any?>, type: Class<T>): T? =
        pm.query(QuerySpecification.withStatement(sql).bind(values.toMap()).transform(type)).singleOrNull()

    /** The stamp the node with [id] carries; null when it has none. */
    private fun stamp(id: String): String? = one("SELECT stamp FROM ogm_node WHERE props ->> '$.id' = \$id", "id" to id, type = String::class.java)

    /** The JSON the node with [id] keeps its properties in. */
    private fun props(id: String): String? = one("SELECT props FROM ogm_node WHERE props ->> '$.id' = \$id", "id" to id, type = String::class.java)

    /** The relationships of [type] from the node with id [from], as the ids of the nodes they lead to. */
    private fun related(from: String, type: String): Set<String> = pm.query(
        QuerySpecification.withStatement(
            "SELECT b.props ->> '$.id' FROM ogm_edge e JOIN ogm_node a ON a.nid = e.src JOIN ogm_node b ON b.nid = e.dst WHERE a.props ->> '$.id' = \$from AND e.type = \$type"
        ).bind(mapOf("from" to from, "type" to type)).transform(String::class.java)
    ).toSet()

    private fun count(table: String): Long = one("SELECT COUNT(*) FROM ogm_$table", type = Long::class.java)!!

    @BeforeEach
    fun clean() = SqliteTestDatabase.clear(pm)

    // ----- Saving and the stamp -----

    @Test
    fun `a save returns the object with a new stamp, and loading gives the same one`() {
        val saved = stateless.save(Claim("c1", "Ada founded Acme"))

        val stamp = assertNotNull(saved.stamp)
        assertTrue(Stamps.isTwoTokens(stamp))
        assertEquals(stamp, stateless.load("c1", Claim::class.java)?.stamp)
        assertEquals(stamp, stamp("c1"))
        assertEquals("""["Claim"]""", one("SELECT labels FROM ogm_node", type = String::class.java))

        val again = stateless.save(saved.copy(text = "Ada founded Acme in 1999"))
        assertNotEquals(stamp, again.stamp, "a save that changes the node writes a new stamp")
        assertEquals("Ada founded Acme in 1999", stateless.load("c1", Claim::class.java)?.text)
        assertEquals(1, count("node"))
    }

    @Test
    fun `a save that changes nothing leaves the stamp as it was`() {
        val saved = stateless.save(Claim("c1", "one", "first"))
        val again = stateless.save(saved)

        assertEquals(saved.stamp, again.stamp)
        assertEquals(saved.stamp, stamp("c1"))
    }

    @Test
    fun `a save is refused when another writer changed the node, and nothing is written`() {
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))
        val elsewhere = Stamps.fresh()
        pm.execute(QuerySpecification.withStatement("UPDATE ogm_node SET props = json_set(props, '$.text', 'Bob founded Acme'), stamp = \$stamp").bind(mapOf("stamp" to elsewhere)))

        val stale = assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(note = "checked")) }

        assertFalse(stale.deleted)
        assertEquals(loaded.stamp, stale.expectedStamp)
        assertEquals(elsewhere, stale.foundStamp)
        assertEquals("Bob founded Acme", stateless.load("c1", Claim::class.java)?.text, "nothing was written")
        assertNull(stateless.load("c1", Claim::class.java)?.note)
    }

    @Test
    fun `a save is refused when another writer deleted the node, and the node is not brought back`() {
        val loaded = stateless.save(Claim("c1", "Ada founded Acme"))
        pm.execute(QuerySpecification.withStatement("DELETE FROM ogm_node"))

        val stale = assertFailsWith<StaleObjectException> { stateless.save(loaded) }

        assertTrue(stale.deleted)
        assertEquals(0, count("node"))
    }

    @Test
    fun `an object with no stamp is saved unchecked, and a type with no stamp field is still stamped`() {
        stateless.save(Claim("c1", "Ada founded Acme"))
        pm.execute(QuerySpecification.withStatement("UPDATE ogm_node SET props = json_set(props, '$.text', 'changed elsewhere')"))
        stateless.save(Claim("c1", "built from scratch"))
        assertEquals("built from scratch", stateless.load("c1", Claim::class.java)?.text)

        stateless.save(Memo("m1", "Call Ada"))
        assertNotNull(stamp("m1"))
    }

    @Test
    fun `a property no save writes keeps the text it was stored with`() {
        seed.node("Claim", properties = mapOf("id" to "c1", "text" to "one"))
        pm.execute(QuerySpecification.withStatement("UPDATE ogm_node SET props = json_set(props, '$.score', json('1.50'))"))

        stateless.save(Claim("c1", "two"))

        assertTrue("1.50" in props("c1")!!, props("c1"))
    }

    @Test
    fun `create makes a node that is not there, and of one that is, or a view whose root is, writes nothing`() {
        val made = assertNotNull(stateless.create(Claim("c1", "first")))
        assertTrue(Stamps.isTwoTokens(made.stamp!!))
        assertEquals(made.stamp, stamp("c1"))

        assertNull(stateless.create(Claim("c1", "second")))
        assertNull(stateless.create(ClaimView(Claim("c1", "third"), people = listOf(Human("ada", "Ada")))))

        assertEquals("first", stateless.load("c1", Claim::class.java)?.text)
        assertEquals(made.stamp, stamp("c1"), "neither a field nor the stamp was written")
        assertEquals(1, count("node"), "the related node was not made")
        assertEquals(0, count("edge"))
    }

    @Test
    fun `saveAll writes every object in one go, and each comes back stamped`() {
        val saved = stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two"), Claim("c3", "three")))

        assertEquals(3, saved.size)
        assertTrue(saved.all { it.stamp != null })
        assertEquals(setOf("c1", "c2", "c3"), stateless.loadAll(Claim::class.java).map { it.id }.toSet())
    }

    // ----- Views and their relationships -----

    @Test
    fun `a view is saved with its relationships, and saved with fewer in place of them removes the rest`() {
        val saved = stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob")), companies = listOf(Company("acme", "Acme"))))

        assertEquals(setOf("ada", "bob", "acme"), related("c1", "MENTIONS"))
        val loaded = assertNotNull(stateless.load("c1", ClaimView::class.java))
        assertEquals(listOf("ada", "bob"), loaded.people.map { it.id }.sorted())
        assertEquals(saved.claim.stamp, loaded.claim.stamp)

        stateless.save(loaded.copy(people = loaded.people.filter { it.id == "ada" }), Replace(ClaimView::people))

        assertEquals(setOf("ada", "acme"), related("c1", "MENTIONS"))
        assertNotNull(stateless.load("bob", Human::class.java), "the node a relationship led to is kept")
        assertNotEquals(Stamps.linksToken(saved.claim.stamp!!), Stamps.linksToken(stamp("c1")!!), "a change of relationships renews the second token")

        val again = assertNotNull(stateless.load("c1", ClaimView::class.java))
        stateless.save(again.copy(people = emptyList()), Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED))
        assertEquals(setOf("acme"), related("c1", "MENTIONS"))
        assertNull(stateless.load("ada", Human::class.java), "a node nothing else refers to goes with its relationship")
    }

    @Test
    fun `a removed target another node still refers to is kept, and only its relationship goes`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))
        stateless.save(ClaimView(Claim("c2", "two"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(stateless.load("c1", ClaimView::class.java))

        stateless.save(loaded.copy(people = emptyList()), Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED))

        assertEquals(emptySet(), related("c1", "MENTIONS"))
        assertEquals(setOf("ada"), related("c2", "MENTIONS"))
        assertNotNull(stateless.load("ada", Human::class.java), "a node another still refers to is kept")
        assertNull(stateless.load("bob", Human::class.java))
    }

    @Test
    fun `labels a field holds are written as labels, and a save that clears removes only those the field wrote`() {
        fun labels(id: String): Set<String> = pm.query(
            QuerySpecification.withStatement("SELECT l.value FROM ogm_node n, json_each(n.labels) l WHERE n.props ->> '$.id' = \$id")
                .bind(mapOf("id" to id)).transform(String::class.java)
        ).toSet()
        fun audit(id: String) = pm.execute(
            QuerySpecification.withStatement("UPDATE ogm_node SET labels = (SELECT json_group_array(value) FROM (SELECT value FROM json_each(labels) UNION SELECT 'Audited' ORDER BY 1)) WHERE props ->> '$.id' = \$id")
                .bind(mapOf("id" to id))
        )

        stateless.save(MemberNode("m1", "Ada", setOf(Role.Admin, Role.Author)))
        assertEquals(setOf("Member", "Admin", "Author"), labels("m1"))
        audit("m1")
        stateless.save(MemberNode("m1", "Ada", setOf(Role.Reviewer)))
        assertEquals(setOf("Member", "Admin", "Author", "Reviewer", "Audited"), labels("m1"), "a save that does not clear adds")
        stateless.save(MemberNode("m1", "Ada", setOf(Role.Reviewer)), nullPolicy = NullPolicy.CLEAR)
        assertEquals(setOf("Member", "Reviewer", "Audited"), labels("m1"))
        assertEquals(setOf(Role.Reviewer), stateless.load("m1", MemberNode::class.java)?.roles)

        stateless.save(ThingNode("t1", "Lyre", setOf("Instrument", "String Instrument"), mapOf("strings" to 7, "maker" to "Erard")))
        audit("t1")
        stateless.save(ThingNode("t1", "Lyre", setOf("Antique", "Restored"), mapOf("strings" to 7)), nullPolicy = NullPolicy.CLEAR)
        assertEquals(setOf("Thing", "Antique", "Restored", "Audited"), labels("t1"))
        val thing = assertNotNull(stateless.load("t1", ThingNode::class.java))
        assertEquals(setOf("strings"), thing.properties.keys, "a flat bag that clears drops the keys it no longer holds")
        stateless.save(ThingNode("t1", "Lyre", emptySet(), thing.properties), nullPolicy = NullPolicy.CLEAR)
        assertEquals(setOf("Thing", "Audited"), labels("t1"))

        stateless.save(MixedBagNode("x1", mapOf("source" to "wiki"), mapOf("colour" to "red")))
        stateless.save(MixedBagNode("x1", mapOf("source" to "wiki"), emptyMap()), nullPolicy = NullPolicy.CLEAR)
        val mixed = assertNotNull(stateless.load("x1", MixedBagNode::class.java))
        assertEquals(mapOf("source" to "wiki"), mixed.meta, "clearing the flat bag leaves the prefixed bag's keys")
        assertTrue(mixed.rest.isEmpty())
    }

    @Test
    fun `a stale view is refused as a stale fragment is`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))
        val loaded = assertNotNull(stateless.load("c1", ClaimView::class.java))
        stateless.save(loaded.copy(claim = loaded.claim.copy(text = "two")))

        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(people = emptyList())) }
        assertEquals(setOf("ada"), related("c1", "MENTIONS"))
    }

    @Test
    fun `a view with a recursive field holds each level down to its depth`() {
        with(seed) {
            listOf("root", "child", "grandchild", "other").forEach { node("Session", properties = mapOf("id" to it)) }
            node("Message", properties = mapOf("id" to "m1", "text" to "hello"))
            edge("child", "PART_OF", "root")
            edge("grandchild", "PART_OF", "child")
            edge("child", "HAS_MESSAGE", "m1")
        }

        val tree = assertNotNull(stateless.load("root", DeletableSessionTree::class.java))

        assertEquals(listOf("child"), tree.parts.map { it.session.id })
        assertEquals(listOf("m1"), tree.parts.single().messages.map { it.id })
        assertEquals(listOf("grandchild"), tree.parts.single().parts.map { it.session.id })
    }

    // ----- Cascading deletes -----

    @Test
    fun `a cascading delete takes the view's nodes and keeps those outside it`() {
        with(seed) {
            node("User", properties = mapOf("id" to "u"))
            node("Session", properties = mapOf("id" to "s"))
            node("Message", properties = mapOf("id" to "m1"))
            node("Message", properties = mapOf("id" to "m2"))
            edge("s", "OWNED_BY", "u"); edge("s", "HAS_MESSAGE", "m1"); edge("s", "HAS_MESSAGE", "m2"); edge("m1", "AUTHORED_BY", "u")
        }

        assertEquals(3, stateless.delete("s", DeletableSession::class.java, CascadeType.DELETE_ALL))

        assertEquals(1, count("node"), "the user, outside the view, is kept")
        assertEquals(0, count("edge"))
    }

    @Test
    fun `an orphan cascade keeps a node something outside the cascade still holds`() {
        with(seed) {
            node("User", properties = mapOf("id" to "u"))
            node("Session", properties = mapOf("id" to "s"))
            node("Message", properties = mapOf("id" to "m1"))
            node("Message", properties = mapOf("id" to "m2"))
            edge("s", "HAS_MESSAGE", "m1"); edge("s", "HAS_MESSAGE", "m2"); edge("u", "PINNED", "m1")
        }

        assertEquals(2, stateless.delete("s", DeletableSession::class.java, CascadeType.DELETE_ORPHAN))

        assertNotNull(stateless.load("m1", sample.mapped.view.Message::class.java))
        assertNull(stateless.load("m2", sample.mapped.view.Message::class.java))
    }

    /** Sessions s0 ← s1 ← … ← s[levels], each with a message sm[i], and [extra] relationships beside them. */
    private fun chain(levels: Int, vararg extra: Triple<String, String, String>) = with(seed) {
        (0..levels).forEach { i ->
            node("Session", properties = mapOf("id" to "s$i"))
            node("Message", properties = mapOf("id" to "sm$i"))
            edge("s$i", "HAS_MESSAGE", "sm$i")
            if (i > 0) edge("s$i", "PART_OF", "s${i - 1}")
        }
        extra.forEach { (from, type, to) -> edge(from, type, to) }
    }

    private fun ids(): Set<String> = pm.query(QuerySpecification.withStatement("SELECT props ->> '$.id' FROM ogm_node").transform(String::class.java)).toSet()

    @Test
    fun `a recursive cascade deeper than its maxDepth is refused before anything is deleted, and a ring is deleted once`() {
        chain(4)
        val seeded = ids()
        for (cascade in listOf(CascadeType.DELETE_ALL, CascadeType.DELETE_ORPHAN)) {
            val refused = assertFailsWith<IllegalStateException>("$cascade") { stateless.delete("s0", DeletableSessionTree::class.java, cascade) }
            assertTrue("reaches 3 levels deep (maxDepth = 3), and 1 node below that would be left behind" in refused.message.orEmpty(), refused.message)
            assertEquals(seeded, ids(), "nothing is deleted when a cascade is refused")
        }

        // Exactly maxDepth levels below the root: nothing is left below, so nothing is refused.
        SqliteTestDatabase.clear(pm)
        chain(3)
        assertEquals(8, stateless.delete("s0", DeletableSessionTree::class.java, CascadeType.DELETE_ALL))
        assertEquals(emptySet(), ids())

        chain(2, Triple("s0", "PART_OF", "s2"))
        assertEquals(6, stateless.delete("s0", DeletableSessionTree::class.java, CascadeType.DELETE_ORPHAN), "each node of the ring once")
        assertEquals(emptySet(), ids())
    }

    @Test
    fun `an orphan cascade keeps every level below a kept node, however long the chain`() {
        chain(5, Triple("s3", "PART_OF", "s0"))
        seed.node("User", properties = mapOf("id" to "u"))
        seed.edge("s1", "PINNED_BY", "u")
        // s1 is pinned from outside; s2 … s5 and their messages hang below it, s3 reached from the root too.

        assertEquals(2, stateless.delete("s0", DeletableSessionTree::class.java, CascadeType.DELETE_ORPHAN))

        assertEquals((1..5).flatMap { listOf("s$it", "sm$it") }.toSet() + "u", ids())
    }

    // ----- Writers at once -----

    @Test
    fun `writers on many connections save at once, each waiting its turn, and every save is written`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..8).map { thread ->
                pool.submit {
                    (1..25).forEach { i ->
                        val saved = stateless.save(Claim("t$thread-$i", "first"))
                        stateless.save(saved.copy(text = "second"))
                    }
                }
            }.forEach { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        val claims = stateless.loadAll(Claim::class.java)
        assertEquals(200, claims.size)
        assertTrue(claims.all { it.text == "second" })
        assertEquals("ok", one("PRAGMA integrity_check", type = String::class.java))
    }

    @Test
    fun `deletes beside writers on other connections are run again when turned away, and every one deletes`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..8).map { thread ->
                pool.submit {
                    (1..30).forEach { i ->
                        stateless.save(Claim("t$thread-$i", "first"))
                        // A delete reads the nodes it takes, then deletes them: another connection may write in between.
                        val deleted = if (i % 2 == 0) stateless.delete("t$thread-$i", Claim::class.java) else stateless.deleteAll(Claim::class.java, "n.id = 't$thread-$i'")
                        assertEquals(1, deleted)
                    }
                }
            }.forEach { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        assertEquals(0, stateless.count(Claim::class.java))
    }

    // ----- Patching -----

    private val changed = PatchResult(found = true, applied = true, changed = true)
    private val unchanged = PatchResult(found = true, applied = true, changed = false)
    private val unmet = PatchResult(found = true, applied = false, changed = false)
    private val absent = PatchResult(found = false, applied = false, changed = false)

    @Test
    fun `a patch says whether the node was found, whether the predicate held and whether a value differed`() {
        stateless.save(Claim("c1", "one", "first"))
        fun patch(id: String, text: String) = stateless.patch(id, Claim::class.java, ClaimQueryDsl.INSTANCE) { fields { query.note setTo "patched" }; where { query.text eq text } }

        assertEquals(absent, patch("nobody", "one"))
        assertEquals(unmet, patch("c1", "two"))
        assertEquals("first", stateless.load("c1", Claim::class.java)?.note)
        val before = stamp("c1")!!
        assertEquals(changed, patch("c1", "one"))
        assertEquals("patched", stateless.load("c1", Claim::class.java)?.note)
        assertNotEquals(Stamps.nodeToken(before), Stamps.nodeToken(stamp("c1")!!))
        assertEquals(Stamps.linksToken(before), Stamps.linksToken(stamp("c1")!!))
        assertEquals(unchanged, patch("c1", "one"))
        assertEquals(1, count("node"), "a patch never makes a node")
    }

    @Test
    fun `a moment moves forward and never back`() {
        val noon = Instant.parse("2026-03-01T12:00:00Z")
        stateless.save(PatchSession("s1"))
        fun touch(at: Instant) = stateless.patch("s1", PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) {
            fields { query.lastActivityAt setTo at }
            where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt at } }
        }

        assertEquals(changed, touch(noon))
        assertEquals(unmet, touch(noon.minusSeconds(60)))
        assertEquals(unmet, touch(noon))
        assertEquals(changed, touch(noon.plusMillis(120)))
        assertEquals(changed, touch(noon.plusSeconds(60)))
        assertEquals(noon.plusSeconds(60), stateless.load("s1", PatchSession::class.java)?.lastActivityAt)
        assertEquals(1, stateless.count(PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) { where { query.lastActivityAt gt noon } })
    }

    @Test
    fun `many writers move one moment forward in any order, and it ends at the latest`() {
        val noon = Instant.parse("2026-03-01T12:00:00Z")
        stateless.save(PatchSession("s1"))
        val times = (1..24).map { noon.plusSeconds(it.toLong()) }.shuffled()
        val pool = Executors.newFixedThreadPool(6)
        try {
            times.map { at ->
                pool.submit {
                    stateless.patch("s1", PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) {
                        fields { query.lastActivityAt setTo at }
                        where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt at } }
                    }
                }
            }.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        assertEquals(times.max(), stateless.load("s1", PatchSession::class.java)?.lastActivityAt)
    }

    @Test
    fun `patchAll writes the named fields of each object, and says what it did to each`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two", "second")))

        val results = stateless.patchAll(listOf(Claim("c1", "one", "patched"), Claim("c2", "two", "second"), Claim("c3", "three")), only = setOf(Claim::note))

        assertEquals(listOf(changed, unchanged, absent), results)
        assertEquals("patched", stateless.load("c1", Claim::class.java)?.note)
        assertNull(stateless.load("c3", Claim::class.java))
    }

    // ----- Relationships on their own -----

    @Test
    fun `relate makes one relationship and writes its properties, CREATE makes another, and unrelate removes them`() {
        stateless.saveAll(listOf(Claim("c1", "one"), Claim("c2", "two")))
        stateless.save(Human("ada", "Ada"))
        val claim = nodeRef<Claim>("c1")
        val ada = nodeRef<Human>("ada")

        assertTrue(stateless.edges.relate(claim, ada, "MENTIONS", mapOf("weight" to 2)))
        assertTrue(stateless.edges.relate(claim, ada, "MENTIONS", mapOf("weight" to 3)))
        assertEquals(1, count("edge"))
        assertEquals(3L, one("SELECT props ->> '$.weight' FROM ogm_edge", type = Long::class.java))
        stateless.edges.relate(claim, ada, "CITES", mode = RelateMode.CREATE)
        stateless.edges.relate(claim, ada, "CITES", mode = RelateMode.CREATE)
        assertEquals(3, count("edge"))
        assertFalse(stateless.edges.relate(claim, nodeRef<Human>("nobody"), "MENTIONS"), "a node that is not there relates nothing")

        assertEquals(listOf("c1"), stateless.edges.loadRelated(ada, "MENTIONS", org.drivine.annotation.Direction.INCOMING, Claim::class.java).map { it.id })
        assertEquals(1, stateless.edges.unrelate(claim, ada, "MENTIONS"))
        assertEquals(setOf("ada"), related("c1", "CITES"))
        stateless.edges.unrelateAll(claim, "CITES", org.drivine.annotation.Direction.OUTGOING)
        assertEquals(0, count("edge"))
    }

    @Test
    fun `unrelateAll removes a node's relationships in the direction it names, one to itself once, and marks every end`() {
        stateless.saveAll(listOf(Claim("a", "a"), Claim("b", "b"), Claim("c", "c")))
        val (a, b, c) = listOf("a", "b", "c").map { nodeRef<Claim>(it) }
        fun relateAll() {
            stateless.edges.relate(a, a, "R")
            stateless.edges.relate(a, b, "R")
            stateless.edges.relate(c, a, "R")
        }
        relateAll()
        assertTrue(stateless.edges.relate(a, a, "R"), "a node related to itself is found as related")
        assertEquals(3, count("edge"), "a merge to itself finds the one it made")

        assertEquals(2, stateless.edges.unrelateAll(a, "R", org.drivine.annotation.Direction.INCOMING), "from c, and from a itself")
        assertEquals(setOf("b"), related("a", "R"))
        assertEquals(1, count("edge"))

        stateless.edges.unrelateAll(a, "R", org.drivine.annotation.Direction.OUTGOING)
        relateAll()
        val before = listOf("a", "b", "c").associateWith { stamp(it)!! }
        assertEquals(3, stateless.edges.unrelateAll(a, "R", org.drivine.annotation.Direction.UNDIRECTED), "to b, from c, and to itself once")
        assertEquals(0, count("edge"))
        listOf("a", "b", "c").forEach { id ->
            assertEquals(Stamps.nodeToken(before.getValue(id)), Stamps.nodeToken(stamp(id)!!), "$id's own properties are untouched")
            assertNotEquals(Stamps.linksToken(before.getValue(id)), Stamps.linksToken(stamp(id)!!), "$id's relationships changed")
        }
    }

    // ----- The query DSL -----

    private fun proposition(id: String, context: String, level: Int, grounding: List<String>, embedding: List<Float>?, vararg mentions: Pair<String, String>) =
        PropositionView(
            PropositionNode(id, context, "active", level, embedding = embedding, grounding = grounding),
            mentions.mapIndexed { i, (resolved, role) -> Mention("$id-m$i", resolved, role) },
        )

    private fun seedPropositions() {
        stateless.saveAll(
            listOf(
                proposition("p1", "c", 1, listOf("chunk-1", "chunk-2"), listOf(1f, 0f, 0f, 0f), "entX" to "SUBJECT"),
                proposition("p2", "c", 2, listOf("chunk-2"), listOf(0.9f, 0.1f, 0f, 0f), "entY" to "OBJECT"),
                proposition("p3", "d", 3, listOf("chunk-1"), listOf(0f, 1f, 0f, 0f)),
                proposition("p4", "c", 4, listOf("chunk-1"), listOf(0f, 0f, 1f, 0f), "entX" to "OBJECT"),
                proposition("p5", "c", 5, emptyList(), null, "entX" to "SUBJECT", "entZ" to "OBJECT"),
            )
        )
    }

    private fun ids(spec: org.drivine.query.dsl.GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): List<String> =
        stateless.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec).map { it.proposition.id }

    @Test
    fun `the DSL filters on the root's properties`() {
        seedPropositions()

        assertEquals(setOf("p1", "p2", "p4", "p5"), ids { where { query.proposition.contextId eq "c" } }.toSet())
        assertEquals(setOf("p4", "p5"), ids { where { query.proposition.level gt 3 } }.toSet())
        assertEquals(setOf("p1", "p3"), ids { where { query.proposition.level `in` listOf(1, 3) } }.toSet())
        assertEquals(setOf("p2", "p4", "p5"), ids { where { query.proposition.level notIn listOf(1, 3) } }.toSet())
        assertEquals(setOf("p3"), ids { where { query.proposition.contextId neq "c" } }.toSet())
        assertEquals(setOf("p1", "p2"), ids { where { query.proposition.id startsWith "p"; query.proposition.level lte 2 } }.toSet())
        assertEquals(setOf("p3"), ids { where { query.proposition.contextId containsIgnoreCase "D" } }.toSet())
        assertEquals(setOf("p1", "p3", "p4"), ids { where { query.proposition.grounding hasItem "chunk-1" } }.toSet())
        assertEquals(setOf("p1", "p5"), ids { where { anyOf { query.proposition.level eq 1; query.proposition.level eq 5 } } }.toSet())
    }

    @Test
    fun `each operator of the DSL reads a stored value as Postgres reads it`() {
        seed.node("Claim", properties = mapOf("id" to "c1", "text" to "Graph", "note" to "alpha", "flag" to true, "n" to 7, "tags" to listOf("a", 1)))
        seed.node("Claim", "Extra", properties = mapOf("id" to "c2", "text" to "graphs", "flag" to false, "n" to 7.5))
        seed.node("Claim", properties = mapOf("id" to "c3", "text" to "other", "note" to "", "n" to "7"))
        fun ids(vararg conditions: org.drivine.query.dsl.WhereCondition) =
            stateless.loadAll(Claim::class.java, Any()) { this.conditions += conditions }.map { it.id }.sorted()
        fun on(path: String, operator: ComparisonOperator, value: Any?) = PropertyCondition(path, operator, value)

        assertEquals(listOf("c2"), ids(on("n.text", ComparisonOperator.ENDS_WITH, "phs")))
        assertEquals(listOf("c1", "c2", "c3"), ids(on("n.text", ComparisonOperator.ENDS_WITH, "")), "an empty suffix ends every text")
        assertEquals(listOf("c1", "c2"), ids(on("n.text", ComparisonOperator.CONTAINS, "rap")))
        assertEquals(listOf("c1"), ids(on("n.text", ComparisonOperator.EQUALS_IGNORE_CASE, "graph")))
        assertEquals(listOf("c2"), ids(on("n.note", ComparisonOperator.IS_NULL, null)))
        assertEquals(listOf("c1", "c3"), ids(on("n.note", ComparisonOperator.IS_NOT_NULL, null)), "an empty text is a value")
        assertEquals(listOf("c3"), ids(org.drivine.query.dsl.WhereCondition.NotCondition(listOf(on("n.note", ComparisonOperator.EQUALS, "alpha")))))
        // A boolean is compared with the text JSON writes it as, and not with SQLite's 1.
        assertEquals(listOf("c1"), ids(on("n.flag", ComparisonOperator.EQUALS, true)))
        assertEquals(listOf("c2"), ids(on("n.flag", ComparisonOperator.IN, listOf(false))))
        // A number is compared with a number where one is stored; a text with the text of any value.
        assertEquals(listOf("c1"), ids(on("n.n", ComparisonOperator.EQUALS, 7)))
        assertEquals(listOf("c2"), ids(on("n.n", ComparisonOperator.IN, listOf(7.5))))
        assertEquals(listOf("c1", "c3"), ids(on("n.n", ComparisonOperator.EQUALS, "7")))
        // A list holds an element of the same JSON type.
        assertEquals(listOf("c1"), ids(org.drivine.query.dsl.WhereCondition.ListMembershipCondition("n.tags", 1)))
        assertEquals(emptyList(), ids(org.drivine.query.dsl.WhereCondition.ListMembershipCondition("n.tags", "1")))
        assertEquals(listOf("c2"), ids(org.drivine.query.dsl.WhereCondition.LabelCondition("n", listOf("Extra"))))
        assertEquals(listOf("c2"), ids(org.drivine.query.dsl.WhereCondition.AnyLabelCondition("n", listOf("Nope", "Extra"))))
    }

    @Test
    fun `the DSL filters on related nodes`() {
        seedPropositions()

        assertEquals(setOf("p1", "p4", "p5"), ids { where { query.mentions.any { resolvedId eq "entX" } } }.toSet())
        assertEquals(setOf("p1", "p5"), ids { where { query.mentions.any { resolvedId eq "entX"; role eq "SUBJECT" } } }.toSet())
        assertEquals(setOf("p2", "p3"), ids { where { query.mentions.none { resolvedId eq "entX" } } }.toSet())
        val view = stateless.load("p5", PropositionView::class.java)!!
        assertEquals(listOf("entX", "entZ"), view.mentions.map { it.resolvedId }.sortedBy { it })
    }

    @Test
    fun `the DSL orders, pages by skip and limit, and seeks past a cursor`() {
        seedPropositions()

        assertEquals(listOf("p5", "p4"), ids { orderBy { query.proposition.level.desc() }; limit(2) })
        assertEquals(listOf("p3", "p2"), ids { orderBy { query.proposition.level.desc() }; skip(2); limit(2) })
        assertEquals(listOf("p2", "p1"), ids { orderBy { query.proposition.level.desc() }; skip(3) })
        assertEquals(
            listOf("p3", "p2"),
            ids {
                orderBy { query.proposition.level.desc(); query.proposition.id.desc() }
                seek { query.proposition.level after 4; query.proposition.id after "p4" }
                limit(2)
            },
        )
        assertEquals(4, stateless.count(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) { where { query.proposition.contextId eq "c" } })
        assertEquals(1, stateless.deleteAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE) { where { query.proposition.contextId eq "d" } })
        assertEquals(listOf("p1", "p2", "p4", "p5"), ids { orderBy { query.proposition.id.asc() } })
    }

    @Test
    fun `an order puts the nodes with no value where Cypher does, unless it says otherwise`() {
        stateless.saveAll(listOf(Claim("c1", "one", "b"), Claim("c2", "two"), Claim("c3", "three", "a")))
        fun ids(spec: org.drivine.query.dsl.GraphQuerySpec<ClaimQueryDsl>.() -> Unit) = stateless.loadAll(Claim::class.java, ClaimQueryDsl.INSTANCE, spec).map { it.id }

        assertEquals(listOf("c3", "c1", "c2"), ids { orderBy { query.note.asc() } })
        assertEquals(listOf("c2", "c1", "c3"), ids { orderBy { query.note.desc() } })
        assertEquals(listOf("c2", "c3", "c1"), ids { orderBy { query.note.asc().nullsFirst() } })
        assertEquals(listOf("c1", "c3", "c2"), ids { orderBy { query.note.desc().nullsLast() } })
    }

    @Test
    fun `a sorted relationship collection puts the nodes with no value where Cypher does`() {
        stateless.save(PropositionView(PropositionNode("p1", "c", "active", 1), listOf(Mention("m1", "b", "S"), Mention("m2", null, "S"), Mention("m3", "a", "S"))))
        fun sorted(spec: org.drivine.query.dsl.GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit) =
            stateless.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec).single().mentions.map { it.id }

        assertEquals(listOf("m3", "m1", "m2"), sorted { orderBy { query.mentions.resolvedId.asc() } }, "last when ascending")
        assertEquals(listOf("m2", "m1", "m3"), sorted { orderBy { query.mentions.resolvedId.desc() } }, "first when descending")
    }

    // ----- Searching -----

    @Test
    fun `a vector search measures every node of the label, filters what it found, and holds to its threshold`() {
        seedPropositions()
        val near = listOf(1f, 0f, 0f, 0f)

        val nearest = stateless.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, near, 3, null, null, null) {}
        assertEquals(listOf("p1", "p2"), nearest.take(2).map { it.value.proposition.id })
        assertEquals(1.0, nearest.first().score, 1e-6)
        // The nearest searchK are measured, and then filtered: p4 is fourth nearest.
        val filtered = stateless.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, near, 3, null, 5, null) {
            where { query.mentions.any { resolvedId eq "entX" } }
        }
        assertEquals(listOf("p1", "p4"), filtered.map { it.value.proposition.id })
        val close = stateless.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, near, 5, 0.9, null, null) {}
        assertEquals(listOf("p1", "p2"), close.map { it.value.proposition.id })
        // A partition label narrows what is measured to the nodes that carry it too.
        pm.execute(QuerySpecification.withStatement("UPDATE ogm_node SET labels = json_insert(labels, '$[#]', 'Pinned') WHERE props ->> '$.id' IN ('p2', 'p3')"))
        val pinned = stateless.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, near, 5, null, null, "Pinned") {}
        assertEquals(listOf("p2", "p3"), pinned.map { it.value.proposition.id })
    }

    @Test
    fun `a full-text search scores whole words, ignores case as Unicode does, and leaves out what it must not hold`() {
        stateless.saveAll(
            listOf(
                ArticleNode("a1", "Graphs", "Graph databases store GRAPH data"),
                ArticleNode("a2", "Soup", "a graph of soup recipes"),
                ArticleNode("a3", "Straße", "ÉCOLE graphs"),
            )
        )

        assertEquals(listOf("a1" to 1.0, "a2" to 0.5), stateless.loadMatching(ArticleNode::class.java, "graph", 10, 0.0).map { it.value.id to it.score })
        assertEquals(listOf("a1"), stateless.loadMatching(ArticleNode::class.java, "graph -soup", 10, 0.0).map { it.value.id })
        assertEquals(listOf("a3"), stateless.loadMatching(ArticleNode::class.java, "école", 10, 0.0).map { it.value.id })
        assertEquals(setOf("a1", "a2", "a3"), stateless.loadMatching(ArticleNode::class.java, "graph*", 10, 0.0).map { it.value.id }.toSet())
    }

    // ----- Moments -----

    /** The ids of the nodes of [TemporalsNode] that meet [conditions], in [orders]. */
    private fun temporals(vararg conditions: org.drivine.query.dsl.WhereCondition, orders: List<OrderSpec> = emptyList()): List<String> =
        stateless.loadAll(TemporalsNode::class.java, Any()) { this.conditions += conditions; this.orders += orders }.map { it.id }

    @Test
    fun `a moment, a date and a date-time round-trip, and compare and order as time whatever their offset`() {
        val base = TemporalsNode.at("base")
        stateless.save(base)
        stateless.save(TemporalsNode.at("later", 10))
        // The same moment as `later`, written with another offset: as text it would sort before `base`.
        val offset = TemporalsNode.at("offset", 10).let { it.copy(zoned = it.instant.atZone(java.time.ZoneOffset.ofHours(-5))) }
        stateless.save(offset)

        val loaded = assertNotNull(stateless.load("base", TemporalsNode::class.java))
        assertEquals(base.instant, loaded.instant)
        assertTrue(base.zoned.isEqual(loaded.zoned))
        assertEquals(base.date, loaded.date)
        assertEquals(base.dateTime, loaded.dateTime)

        fun at(path: String, operator: ComparisonOperator, value: Any) = PropertyCondition(path, operator, value)
        assertEquals(setOf("later", "offset"), temporals(at("n.instant", ComparisonOperator.GREATER_THAN, base.instant)).toSet())
        assertEquals(setOf("later", "offset"), temporals(at("n.zoned", ComparisonOperator.GREATER_THAN, base.zoned)).toSet())
        assertEquals(listOf("base", "later", "offset"), temporals(orders = listOf(OrderSpec("n.zoned", OrderDirection.ASC), OrderSpec("n.id", OrderDirection.ASC))))
        assertEquals(emptyList(), temporals(at("n.date", ComparisonOperator.GREATER_THAN, base.date)))
        assertEquals(3, temporals(at("n.dateTime", ComparisonOperator.GREATER_THAN_OR_EQUAL, base.dateTime)).size)
        assertEquals(listOf("base"), temporals(at("n.instant", ComparisonOperator.EQUALS, base.instant)))
        // A moment is compared exactly, below the millisecond too.
        assertEquals(emptyList(), temporals(at("n.instant", ComparisonOperator.EQUALS, base.instant.plusNanos(1_000))))
        assertEquals(listOf("base"), temporals(at("n.instant", ComparisonOperator.LESS_THAN, base.instant.plusNanos(1_000))))
        assertEquals(emptyList(), temporals(at("n.dateTime", ComparisonOperator.LESS_THAN, base.dateTime)))
        assertEquals(3, temporals(at("n.dateTime", ComparisonOperator.LESS_THAN, base.dateTime.plusNanos(1_000))).size, "the three share one date-time")
        // A list of moments is read as the property is: the instant each is, whatever its offset.
        val east = offset.zoned.withZoneSameInstant(java.time.ZoneOffset.ofHours(5))
        assertEquals(setOf("later", "offset"), temporals(at("n.zoned", ComparisonOperator.IN, listOf(east))).toSet())
        assertEquals(listOf("base"), temporals(at("n.zoned", ComparisonOperator.NOT_IN, listOf(east))))
        assertEquals(listOf("base"), temporals(at("n.instant", ComparisonOperator.IN, listOf(base.instant, base.instant.plusNanos(1_000)))))
        assertEquals(3, temporals(at("n.date", ComparisonOperator.IN, listOf(base.date))).size)
        assertEquals(3, temporals(at("n.dateTime", ComparisonOperator.IN, listOf(base.dateTime))).size)
        // A seek past a moment at another offset leaves out the moments at or before the instant it is.
        val seek = stateless.loadAll(TemporalsNode::class.java, Any()) {
            orders += OrderSpec("n.zoned", OrderDirection.ASC)
            seekValues += SeekValueSpec("n.zoned", base.zoned.withZoneSameInstant(java.time.ZoneOffset.ofHours(-5)))
        }
        assertEquals(setOf("later", "offset"), seek.map { it.id }.toSet())
    }

    @Test
    fun `moments a microsecond apart order, compare and seek as the moments they are`() {
        val base = TemporalsNode.at("t0")
        listOf(0L, 1_000L, 500_001_000L, 120_000_000L).forEachIndexed { i, nanos ->
            val at = base.instant.plusNanos(nanos)
            stateless.save(base.copy(id = "t$i", instant = at, zoned = at.atZone(java.time.ZoneOffset.ofHours(i - 2)), dateTime = base.dateTime.plusNanos(nanos)))
        }
        val byInstant = listOf("t0", "t1", "t3", "t2")
        assertEquals(byInstant, temporals(orders = listOf(OrderSpec("n.instant", OrderDirection.ASC))))
        assertEquals(byInstant, temporals(orders = listOf(OrderSpec("n.zoned", OrderDirection.ASC))), "offsets do not change the order")
        assertEquals(byInstant, temporals(orders = listOf(OrderSpec("n.dateTime", OrderDirection.ASC))))
        assertEquals(listOf("t2"), temporals(PropertyCondition("n.instant", ComparisonOperator.GREATER_THAN, base.instant.plusMillis(500))))
        val after = stateless.loadAll(TemporalsNode::class.java, Any()) {
            orders += OrderSpec("n.instant", OrderDirection.ASC)
            seekValues += SeekValueSpec("n.instant", base.instant)
        }
        assertEquals(listOf("t1", "t3", "t2"), after.map { it.id }, "a seek past a moment leaves out only that moment")
    }

    @Test
    fun `a moment moves forward and never back, within one second`() {
        val noon = Instant.parse("2026-03-01T12:00:00Z")
        stateless.save(PatchSession("s1"))
        fun touch(at: Instant) = stateless.patch("s1", PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) {
            fields { query.lastActivityAt setTo at }
            where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt at } }
        }

        assertEquals(changed, touch(noon))
        assertEquals(changed, touch(noon.plusMillis(120)))
        assertEquals(changed, touch(noon.plusMillis(123)))
        assertEquals(unmet, touch(noon.plusMillis(120)))
        assertEquals(unmet, touch(noon))
        assertEquals(changed, touch(noon.plusMillis(500)))
        assertEquals(changed, touch(noon.plusNanos(500_001_000)))
        assertEquals(unmet, touch(noon.plusMillis(500)))

        assertEquals(noon.plusNanos(500_001_000), stateless.load("s1", PatchSession::class.java)?.lastActivityAt)
        assertEquals(1, stateless.count(PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) { where { query.lastActivityAt lt noon.plusMillis(501) } })
        assertEquals(0, stateless.count(PatchSession::class.java, PatchSessionQueryDsl.INSTANCE) { where { query.lastActivityAt lt noon.plusMillis(500) } })
    }

    @Test
    fun `a group's least and greatest moment are found among moments before 1970 as after it`() {
        val landing = Instant.parse("1969-07-20T20:17:40Z")
        val century = Instant.parse("1900-01-01T00:00:00Z")
        val january = Instant.parse("2026-01-15T10:00:00Z")
        stateless.saveAll(
            listOf(
                Feedback("f1", "/a", createdAt = landing),
                Feedback("f2", "/a", createdAt = january),
                Feedback("f3", "/a", createdAt = century),
                Feedback("f4", "/b", createdAt = landing),
                Feedback("f5", "/b", createdAt = century.plusNanos(1_000)),
            )
        )
        val rows = stateless.groups.load(Feedback::class.java, FeedbackQueryDsl.INSTANCE) {
            val page = key(query.page)
            val first = min(query.createdAt)
            val last = max(query.createdAt)
            rows { Triple(it[page], it[first], it[last]) }
        }
        assertEquals(listOf(Triple("/a", century, january), Triple("/b", century.plusNanos(1_000), landing)), rows)
    }

    @Test
    fun `a group's least or greatest moment is ordered and compared as the moment it is, not as its text`() {
        // Stored as "…12:00:00Z" and "…12:00:00.500Z": as text, the later one sorts first.
        val noon = Instant.parse("2026-01-15T12:00:00Z")
        stateless.saveAll(listOf(Feedback("f1", "/a", createdAt = noon), Feedback("f2", "/b", createdAt = noon.plusMillis(500))))

        val ordered = stateless.groups.load(Feedback::class.java, FeedbackQueryDsl.INSTANCE) {
            val page = key(query.page)
            val last = max(query.createdAt)
            orderBy { last.asc() }
            rows { it[page] }
        }
        assertEquals(listOf("/a", "/b"), ordered)

        val atNoon = stateless.groups.load(Feedback::class.java, FeedbackQueryDsl.INSTANCE) {
            val page = key(query.page)
            val last = max(query.createdAt)
            having { last eq noon }
            rows { it[page] }
        }
        assertEquals(listOf("/a"), atNoon)
    }

    // ----- Names -----

    @Test
    fun `a label or a property with a quote or a dot in it is one label or one property`() {
        seed.node("Claim", "It's \"odd\"", properties = mapOf("id" to "c1", "text" to "one", "metadata.source" to "web"))
        seed.node("Claim", properties = mapOf("id" to "c2", "text" to "two", "metadata" to mapOf("source" to "web")))

        assertEquals(listOf("c1"), stateless.loadAll(Claim::class.java, "instr(n.labels, '\"It''s \\\"odd\\\"\"') > 0").map { it.id })
        assertEquals(listOf("c1"), stateless.loadAll(Claim::class.java, "n.`metadata.source` = 'web'").map { it.id })
    }
}
