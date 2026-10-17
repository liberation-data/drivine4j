package org.drivine.query

import org.drivine.manager.NullPolicy
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.GraphViewModel
import org.drivine.session.SnapshotDigest
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimSupports
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a save is asked to write, before any backend writes it. */
class SaveRequestTest {

    private val digests = SnapshotDigest(Neo4jObjectMapper.instance)
    private val claim = Claim("c1", "one")

    @Test
    fun `a save that is not of a change is unlimited, replaces no list, and makes its root`() {
        val request = SaveRequest(claim, checked = true, NullPolicy.IGNORE)

        assertNull(request.rootWriteFields)
        assertNull(request.before)
        assertNull(request.after)
        assertEquals(RootMode.EITHER, request.rootMode)
        GraphViewModel.from(ClaimSupports::class.java).relationships.forEach { assertNull(request.replaced(it), it.fieldName) }
    }

    @Test
    fun `a digest as loaded without one as it is now, or the other way, is refused`() {
        val digest = digests.of(claim)

        val loadedOnly = assertFailsWith<IllegalArgumentException> { SaveRequest(claim, checked = true, NullPolicy.CLEAR, before = digest) }
        assertContains(loadedOnly.message!!, "its digest now")
        assertFailsWith<IllegalArgumentException> { SaveRequest(claim, checked = true, NullPolicy.CLEAR, after = digest) }
    }

    @Test
    fun `the save of a change is checked, clears what was set to null, writes the dirty root fields and makes no node`() {
        val changed = claim.copy(text = "two")
        val before = digests.of(claim)
        val after = digests.of(changed)
        val request = SaveRequest.changed(changed, before, after, dirty = setOf("text"))

        assertSame(changed, request.obj)
        assertTrue(request.checked)
        assertEquals(NullPolicy.CLEAR, request.nullPolicy)
        assertEquals(setOf("text"), request.rootWriteFields)
        assertSame(before, request.before)
        assertSame(after, request.after)
        assertEquals(RootMode.FOUND, request.rootMode)
        assertNull(SaveRequest.changed(changed, before, after, dirty = null).rootWriteFields, "no dirty set is every field")
    }

    @Test
    fun `a save that makes its root only when it is absent is a whole object's, unchecked, and makes the root`() {
        val request = SaveRequest.absent(claim)
        assertEquals(RootMode.ABSENT, request.rootMode)
        assertFalse(request.checked)
        assertEquals(NullPolicy.IGNORE, request.nullPolicy)
        assertNull(request.before)

        assertFailsWith<IllegalArgumentException> { SaveRequest(claim, checked = true, NullPolicy.IGNORE, rootMode = RootMode.ABSENT) }
        val digest = digests.of(claim)
        assertFailsWith<IllegalArgumentException> {
            SaveRequest(claim, checked = false, NullPolicy.IGNORE, before = digest, after = digest, rootMode = RootMode.ABSENT)
        }
    }
}
