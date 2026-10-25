package org.drivine

import org.junit.jupiter.api.Test
import sample.stateless.Claim
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a refused save says became of the node, read from the stamp it expected and the one it found. */
class StaleObjectExceptionTest {

    private val expected = "aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"

    private fun stale(
        expected: String = this.expected, found: String?, deleted: Boolean = false, bySameBatch: Boolean = false, cause: Throwable? = null,
        fieldsDiffer: Boolean = false,
    ) = StaleObjectException(Claim::class.java, "c1", expected, found, deleted, bySameBatch, cause, fieldsDiffer)

    private fun message(e: StaleObjectException): String = e.message.orEmpty()

    @Test
    fun `a node that is gone was deleted, whatever stamp is given with it`() {
        val gone = stale(found = null, deleted = true)
        val goneWithStamp = stale(found = expected, deleted = true)

        assertTrue(message(gone).startsWith("Claim 'c1' was deleted by another writer after it was loaded, so it was not saved."), message(gone))
        assertContains(message(gone), "Expected stamp $expected, found no node.")
        assertContains(message(goneWithStamp), "was deleted by another writer")
        assertContains(message(goneWithStamp), "found no node")
    }

    @Test
    fun `a node whose own token is as expected and whose other is not had a relationship added or removed`() {
        val relinked = stale(found = "aaaaaaaaaaaaaaaa:cccccccccccccccc")

        assertContains(message(relinked), "Claim 'c1' had a relationship added or removed by another writer after it was loaded")
        assertContains(message(relinked), "Expected stamp $expected, found aaaaaaaaaaaaaaaa:cccccccccccccccc.")
    }

    @Test
    fun `a node that carries the stamp compared and differs in a field says a field differs, and not that a relationship changed`() {
        val sameStamp = stale(found = expected, fieldsDiffer = true)
        val sameNodeToken = stale(found = "aaaaaaaaaaaaaaaa:cccccccccccccccc", fieldsDiffer = true)

        for (e in listOf(sameStamp, sameNodeToken)) {
            assertTrue(message(e).startsWith("Claim 'c1' has a field that is not as the object was loaded with, though it carries the stamp the object was loaded with, so it was not saved."), message(e))
            assertContains(message(e), "Another writer changed the field and left the stamp as it was, or the load read the node while another writer committed.")
            assertFalse("relationship" in message(e), message(e))
            assertTrue(e.fieldsDiffer)
        }
        assertContains(message(sameStamp), "Expected stamp $expected, found $expected.")
        assertFalse(stale(found = expected).fieldsDiffer)
    }

    @Test
    fun `a node that is gone was deleted, though a field was said to differ`() {
        assertContains(message(stale(found = null, deleted = true, fieldsDiffer = true)), "was deleted by another writer")
    }

    @Test
    fun `a node whose own token differs was changed, though its relationships are as they were`() {
        val changed = stale(found = "dddddddddddddddd:bbbbbbbbbbbbbbbb")

        assertContains(message(changed), "Claim 'c1' was changed by another writer after it was loaded")
        assertFalse("relationship" in message(changed).lineSequence().first())
    }

    @Test
    fun `an object saved over a node it had not loaded vouches for no relationships, and the message says so and not that another writer changed them`() {
        val unvouched = stale(expected = "aaaaaaaaaaaaaaaa:", found = "aaaaaaaaaaaaaaaa:cccccccccccccccc")

        assertContains(message(unvouched), "has relationships the object does not vouch for")
        assertContains(message(unvouched), "its stamp carries no relationship token")
        assertFalse("had a relationship added or removed by another writer" in message(unvouched))
    }

    @Test
    fun `a node that is there with no stamp was changed, and is said to carry none`() {
        val unstamped = stale(found = null)

        assertContains(message(unstamped), "was changed by another writer")
        assertContains(message(unstamped), "Expected stamp $expected, found none.")
        assertNull(unstamped.foundStamp)
        assertFalse(unstamped.deleted)
    }

    @Test
    fun `a node changed by an earlier save of the same batch says so, and how to save it instead`() {
        val batch = stale(found = "dddddddddddddddd:bbbbbbbbbbbbbbbb", bySameBatch = true)

        assertContains(message(batch), "Claim 'c1' was changed by an earlier save of the same saveAll, so the batch was not saved.")
        assertContains(message(batch), "Expected stamp $expected, found dddddddddddddddd:bbbbbbbbbbbbbbbb.")
        assertContains(message(batch), "give this object first in the batch")
        assertFalse("another writer" in message(batch))
        assertTrue(batch.bySameBatch)
    }

    @Test
    fun `a save the engine turned away carries the engine's error, and does not say the node changed`() {
        val engine = IllegalStateException("deadlock detected")
        val turnedAway = stale(found = null, cause = engine)

        assertSame(engine, turnedAway.cause)
        assertContains(message(turnedAway), "Claim 'c1' was not saved: the engine turned the save away each time it was run")
        assertContains(message(turnedAway), "deadlock detected")
        assertContains(message(turnedAway), "The node may have changed since it was loaded.")
        assertFalse("was changed by another writer" in message(turnedAway))
        assertFalse("Expected stamp" in message(turnedAway))
    }

    @Test
    fun `the engine's error is what the message is about even for a save of a batch`() {
        val turnedAway = stale(found = null, bySameBatch = true, cause = RuntimeException("conflict"))

        assertContains(message(turnedAway), "the engine turned the save away")
        assertFalse("earlier save of the same saveAll" in message(turnedAway))
    }

    @Test
    fun `it carries what the save knew, and is not a DrivineException, so a handler for one does not catch it`() {
        val e = stale(found = "dddddddddddddddd:bbbbbbbbbbbbbbbb")

        assertEquals(Claim::class.java, e.type)
        assertEquals("c1", e.id)
        assertEquals(expected, e.expectedStamp)
        assertEquals("dddddddddddddddd:bbbbbbbbbbbbbbbb", e.foundStamp)
        assertFalse((e as RuntimeException) is DrivineException)
        assertNull(e.cause)
    }
}
