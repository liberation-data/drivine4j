package org.drivine.mapper

import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimWithTagsView
import sample.stateless.ClaimWithTags
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackedTextTest {

    private val stamp = "0123456789abcdef:fedcba9876543210"

    /** [row] as a load hands it over, mapped to [type]. */
    private fun <T> loaded(type: Class<T>, row: Map<String, Any?>): T =
        TransformPostProcessor<Any, T>(type).apply(listOf(row)).single()

    @Test
    fun `a load keeps the text each packed property held, by field name, beside the fragment and not for an equal one`() {
        val row = mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "[\"a\", \"b\"]")
        val claim = loaded(ClaimWithTags::class.java, row)
        val equal = loaded(ClaimWithTags::class.java, row + ("tags" to "[\"a\",\"b\"]"))

        assertEquals(listOf("a", "b"), claim.tags)
        assertEquals(claim, equal)
        assertEquals(mapOf("tags" to "[\"a\", \"b\"]"), PackedText.of(claim, stamp))
        assertEquals(mapOf("tags" to "[\"a\",\"b\"]"), PackedText.of(equal, stamp))
        assertNull(PackedText.of(claim.copy(), stamp), "no load made a copy")
    }

    @Test
    fun `a load of a view keeps the text beside its root`() {
        val view = loaded(ClaimWithTagsView::class.java, mapOf("claim" to mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "[\"a\"]"), "people" to emptyList<Any>()))

        assertEquals(mapOf("tags" to "[\"a\"]"), PackedText.of(view.claim, stamp))
    }

    @Test
    fun `the text is given only to a fragment that carries the stamp it was loaded with`() {
        val claim = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "plain"))

        assertEquals(mapOf("tags" to "plain"), PackedText.of(claim, stamp))
        assertNull(PackedText.of(claim, "another"))
    }

    @Test
    fun `a property the node held nothing for is kept as none, and a class with no packed field or no stamp keeps nothing`() {
        val claim = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to null))
        val unpacked = loaded(Claim::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp))
        val unstamped = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "tags" to "[]"))

        assertEquals(mapOf("tags" to null), PackedText.of(claim, stamp))
        assertNull(PackedText.of(unpacked, stamp))
        assertNull(PackedText.of(unstamped, null))
    }
}
