package org.drivine.manager

import java.util.IdentityHashMap
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimDossier
import sample.stateless.ClaimPair
import sample.stateless.ClaimSet
import sample.stateless.ClaimView
import sample.stateless.CitedBy
import sample.stateless.Draft
import sample.stateless.Frozen
import sample.stateless.FrozenBoard
import sample.stateless.Human
import sample.stateless.HumanCitations
import sample.stateless.HumanClaimsLoaded
import sample.stateless.JavaStampedRecord
import sample.stateless.Memo
import sample.stateless.MemoView
import sample.stateless.Odd

/** The shapes of object a save hands stamps back to, and the check that it can before it writes. */
class StampedCopyShapesTest {

    private val copies = StampedCopy(Neo4jObjectMapper.instance)

    private fun stamps(vararg stamps: Pair<Any, String>) = IdentityHashMap<Any, String>().apply { putAll(stamps) }

    @Test
    fun `a Java record is rebuilt with its stamp`() {
        val record = JavaStampedRecord("r1", "one", null)

        val stamped = copies.of(record, stamps(record to "a:b"))

        assertEquals(JavaStampedRecord("r1", "one", "a:b"), stamped)
        assertNull(record.stamp())
    }

    @Test
    fun `a class that is immutable and no data class is rebuilt with its stamp`() {
        val frozen = Frozen("f1", "one")

        val stamped = copies.of(frozen, stamps(frozen to "a:b"))

        assertNotSame(frozen, stamped)
        assertEquals(listOf("f1", "one", "a:b"), listOf(stamped.id, stamped.text, stamped.stamp))
    }

    @Test
    fun `a class whose fields can be set is given its stamp in place`() {
        val draft = Draft("d1", "one")

        assertSame(draft, copies.of(draft, stamps(draft to "a:b")))
        assertEquals("a:b", draft.stamp)
    }

    @Test
    fun `an immutable view that is no data class is rebuilt around its stamped nodes`() {
        val root = Frozen("f1", "one")
        val held = Claim("c1", "held")
        val board = FrozenBoard(root, claims = listOf(held))

        val stamped = copies.of(board, stamps(root to "a:b", held to "c:d"))

        assertEquals("a:b", stamped.frozen.stamp)
        assertEquals(listOf(Claim("c1", "held", stamp = "c:d")), stamped.claims)
    }

    @Test
    fun `the stamped nodes of a set are handed their stamps and stay a set`() {
        val (one, two) = Claim("s1", "one") to Claim("s2", "two")
        val view = ClaimSet(Claim("c1", "root"), supports = linkedSetOf(one, two))

        val stamped = copies.of(view, stamps(one to "a:b", two to "c:d"))

        assertEquals(setOf(one.copy(stamp = "a:b"), two.copy(stamp = "c:d")), stamped.supports)
    }

    @Test
    fun `the root of a nested view is handed its stamp`() {
        val nested = Claim("c2", "nested")
        val view = ClaimDossier(Claim("c1", "root"), covered = listOf(ClaimView(nested, people = listOf(Human("ada", "Ada")))))

        val stamped = copies.of(view, stamps(nested to "a:b"))

        assertEquals("a:b", stamped.covered.single().claim.stamp)
        assertEquals(listOf(Human("ada", "Ada")), stamped.covered.single().people)
    }

    @Test
    fun `the target of a relationship fragment is handed its stamp`() {
        val target = Claim("c1", "cited")
        val view = HumanCitations(Human("ada", "Ada"), citedBy = listOf(CitedBy(7, target)))

        val stamped = copies.of(view, stamps(target to "a:b"))

        assertEquals(listOf(CitedBy(7, target.copy(stamp = "a:b"))), stamped.citedBy)
    }

    @Test
    fun `an object with nothing to stamp is itself`() {
        val view = ClaimView(Claim("c1", "root"), people = listOf(Human("ada", "Ada")))

        assertSame(view, copies.of(view, stamps()))
    }

    @Test
    fun `a field that holds one node is handed its stamp, a field that holds none is left, and a list nothing in changed is the list it was`() {
        val lead = Claim("l1", "lead")
        val supports = listOf(Claim("s1", "one"))
        val view = ClaimPair(Claim("c1", "root"), lead = lead, supports = supports)

        val stamped = copies.of(view, stamps(lead to "a:b"))

        assertEquals("a:b", stamped.lead?.stamp)
        assertSame(supports, stamped.supports)
        assertSame(view.claim, stamped.claim)
        assertNull(lead.stamp, "the object that was saved is left as it was")

        val alone = ClaimPair(Claim("c1", "root"), lead = null)
        assertSame(alone, copies.of(alone, stamps(lead to "a:b")))
    }

    @Test
    fun `a node only loaded through a read-only field is not handed a stamp, for the save did not write it`() {
        val loaded = Claim("c1", "loaded")
        val view = HumanClaimsLoaded(Human("ada", "Ada"), claims = listOf(loaded))

        assertSame(view, copies.of(view, stamps(loaded to "a:b")))
    }

    @Test
    fun `a fragment that declares no stamp field is itself, though a stamp is keyed by it`() {
        val human = Human("ada", "Ada")
        val view = ClaimView(Claim("c1", "root"), people = listOf(human))

        assertSame(human, copies.of(human, stamps(human to "a:b")))
        assertSame(view, copies.of(view, stamps(human to "a:b")))
    }

    @Test
    fun `fragments are told apart by identity, so an equal one is not handed another's stamp`() {
        val held = Claim("s1", "same")
        val twin = Claim("s1", "same")
        val view = ClaimPair(Claim("c1", "root"), supports = listOf(held, twin))

        val stamped = copies.of(view, stamps(twin to "a:b"))

        assertEquals(listOf(null, "a:b"), stamped.supports.map { it.stamp })
        assertSame(held, stamped.supports.first())
    }

    @Test
    fun `the stamp an object carries is its root's, and none for a root that carries or declares none`() {
        val root = Claim("c1", "root", stamp = "a:b")

        assertEquals("a:b", copies.stampOf(root))
        assertEquals("a:b", copies.stampOf(ClaimView(root)))
        assertNull(copies.stampOf(Claim("c2", "never saved")))
        assertNull(copies.stampOf(Human("ada", "Ada")))
        assertNull(copies.stampOf(MemoView(Memo("m1", "memo"))))
        // A related node's stamp is not the object's.
        assertNull(copies.stampOf(ClaimPair(Claim("c3", "root"), lead = root)))
    }

    @Test
    fun `the root of a view is the fragment it holds, and of a fragment itself`() {
        val root = Claim("c1", "root")

        assertSame(root, copies.rootOf(ClaimView(root)))
        assertSame(root, copies.rootOf(root))
    }

    // ----- Whether an object can be handed its stamps, asked before its save -----

    @Test
    fun `an object that can carry its stamps passes, and is left as it is`() {
        val draft = Draft("d1", "one")
        val record = JavaStampedRecord("r1", "one", null)
        val root = Frozen("f1", "one")
        val board = FrozenBoard(root, claims = listOf(Claim("c1", "held")))

        copies.requireStampable(draft, listOf(draft))
        copies.requireStampable(record, listOf(record))
        copies.requireStampable(board, listOf(root, board.claims.single()))

        assertNull(draft.stamp, "a field that can be set is not set by the check")
    }

    @Test
    fun `an object no copy can be made of is refused`() {
        val odd = Odd("o1", seed = 7)

        val refusal = assertFailsWith<IllegalArgumentException> { copies.requireStampable(odd, listOf(odd)) }

        assertContains(refusal.message.orEmpty(), "Odd cannot be handed the stamp its save would leave, so it was not saved")
    }
}
