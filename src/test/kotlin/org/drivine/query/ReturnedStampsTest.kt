package org.drivine.query

import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The row a save statement returns, as it is read: the same row from every backend. */
class ReturnedStampsTest {

    private val root = "aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"
    private val was = "cccccccccccccccc:dddddddddddddddd"
    private val now = "eeeeeeeeeeeeeeee:ffffffffffffffff"

    @Test
    fun `a row that is the root's stamp alone hands back no other`() {
        val returned = ReturnedStamps.of(root)

        assertEquals(root, returned.root)
        assertTrue(returned.related.isEmpty())
    }

    @Test
    fun `each related node is read by the index it was given, in whatever order the row lists them`() {
        val returned = ReturnedStamps.of("$root,1/$was=$now,0/=$was")

        assertEquals(root, returned.root)
        assertEquals(setOf(0, 1), returned.related.keys)
        assertEquals(was, returned.related.getValue(1).found)
        assertEquals(now, returned.related.getValue(1).stamp)
        assertEquals(1, returned.related.getValue(1).index)
    }

    @Test
    fun `a node the statement made was found with nothing, and one that was there without a stamp with the mark for that`() {
        val returned = ReturnedStamps.of("$root,0/=$now,1/${Stamps.NEVER_STAMPED}=$now")

        assertEquals("", returned.related.getValue(0).found)
        assertEquals(Stamps.NEVER_STAMPED, returned.related.getValue(1).found)
        assertEquals(now, returned.related.getValue(1).stamp)
    }

    @Test
    fun `a stamp is read whole, its colon and all, and so is one that is not two tokens`() {
        val entry = FoundStamp.of("12/legacy=$now")
        val odd = FoundStamp.of("3/$was=x=y")

        assertEquals(12, entry.index)
        assertEquals("legacy", entry.found)
        assertEquals(now, entry.stamp)
        assertEquals("x=y", odd.stamp, "what follows the first = is the stamp")
    }

    @Test
    fun `the root's stamp with no relationship token is still the root's, and an index that is no number is refused`() {
        assertEquals("aaaaaaaaaaaaaaaa:", ReturnedStamps.of("aaaaaaaaaaaaaaaa:,0/=$now").root)
        assertFailsWith<NumberFormatException> { FoundStamp.of("x/=$now") }
    }
}
