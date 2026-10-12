package org.drivine.oracle

import org.drivine.StaleObjectException
import org.drivine.annotation.Direction
import org.drivine.manager.Replace
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.nodeRef
import org.drivine.manager.update
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Human
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saves, updates and relationships through `StatelessGraphObjectManager` on Oracle. */
@OracleTests
class OracleWriteSmokeTest {

    private val pm = OracleTestDatabase.graphManager("smoke")
    private val manager = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    @BeforeEach
    fun clean() = OracleTestDatabase.clear(pm)

    @Test
    fun `a fragment is saved with a stamp, and loads back as it was saved`() {
        val saved = manager.save(Claim("c1", "Ada founded Acme", note = "n"))

        assertNotNull(saved.stamp)
        assertEquals(saved, manager.load("c1", Claim::class.java))
        assertEquals(saved.stamp, manager.save(saved).stamp, "a save that changes nothing keeps the stamp")
        assertNotEquals(saved.stamp, manager.save(saved.copy(text = "changed")).stamp)
    }

    @Test
    fun `a save of an object another writer changed is refused`() {
        val loaded = manager.save(Claim("c1", "one"))
        manager.save(loaded.copy(text = "two"))

        val stale = assertFailsWith<StaleObjectException> { manager.save(loaded.copy(text = "three")) }

        assertEquals(loaded.stamp, stale.expectedStamp)
        assertEquals("two", manager.load("c1", Claim::class.java)!!.text)
    }

    @Test
    fun `a view saves its root, its related nodes and the relationships to them`() {
        manager.save(ClaimView(Claim("c1", "Ada founded Acme"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob")), companies = listOf(Company("acme", "Acme"))))

        val view = manager.load("c1", ClaimView::class.java)!!
        assertEquals(setOf("ada", "bob"), view.people.map { it.id }.toSet())
        assertEquals(listOf(Company("acme", "Acme")), view.companies)

        // A list that is the whole list drops what it no longer holds, and no node.
        manager.save(view.copy(people = view.people.filter { it.id == "ada" }), Replace(ClaimView::people))
        assertEquals(listOf("ada"), manager.load("c1", ClaimView::class.java)!!.people.map { it.id })
        assertEquals(2, manager.count(Human::class.java))
    }

    @Test
    fun `update writes what the change altered`() {
        manager.save(ClaimView(Claim("c1", "one", note = "keep"), people = listOf(Human("ada", "Ada"), Human("bob", "Bob"))))

        val updated = manager.update<ClaimView>("c1") { it.copy(claim = it.claim.copy(text = "two"), people = it.people.filter { p -> p.id == "bob" }) }

        assertEquals("two", updated!!.claim.text)
        val loaded = manager.load("c1", ClaimView::class.java)!!
        assertEquals(Claim("c1", "two", "keep", loaded.claim.stamp), loaded.claim)
        assertEquals(listOf("bob"), loaded.people.map { it.id })
        assertNull(manager.update<ClaimView>("nope") { it })
    }

    @Test
    fun `relationships are made and removed on their own`() {
        manager.saveAll(listOf(Claim("c1", "one"), Human("ada", "Ada"), Human("bob", "Bob")))

        assertTrue(manager.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "MENTIONS"))
        assertTrue(manager.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("bob"), "MENTIONS", mapOf("page" to 3)))
        assertEquals(false, manager.edges.relate(nodeRef<Claim>("c1"), nodeRef<Human>("nobody"), "MENTIONS"))
        assertEquals(setOf("ada", "bob"), manager.edges.loadRelated(nodeRef<Claim>("c1"), "MENTIONS", Direction.OUTGOING, Human::class.java).map { it.id }.toSet())

        assertEquals(1, manager.edges.unrelate(nodeRef<Claim>("c1"), nodeRef<Human>("ada"), "MENTIONS"))
        assertEquals(1, manager.edges.unrelateAll(nodeRef<Claim>("c1"), "MENTIONS"))
        assertEquals(emptyList(), manager.load("c1", ClaimView::class.java)!!.people)
    }
}
