package org.drivine.manager

import org.junit.jupiter.api.Test
import sample.stateless.ClaimView
import sample.stateless.HumanClaims
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a [Replace] names, however it is built, and what it refuses to be. */
class RelationshipWriteTest {

    @Test
    fun `a replace of named fields names them by the property's name, and keeps removed targets unless told otherwise`() {
        val replace = Replace(ClaimView::people, ClaimView::companies)

        assertEquals(setOf("people", "companies"), replace.fields)
        assertFalse(replace.everyField)
        assertEquals(RemovedTargets.KEEP, replace.removedTargets)
        assertEquals(RemovedTargets.DELETE_UNREFERENCED, Replace(ClaimView::people, removedTargets = RemovedTargets.DELETE_UNREFERENCED).removedTargets)
    }

    @Test
    fun `only a property's name is used, so a field named twice, or by another view's property of that name, is one field`() {
        assertEquals(setOf("people"), Replace(ClaimView::people, ClaimView::people).fields)
        assertEquals(Replace(HumanClaims::claims).fields, Replace.of(setOf("claims")).fields)
    }

    @Test
    fun `a replace that names no field is refused, and told how to name them or all`() {
        val byProperty = assertFailsWith<IllegalArgumentException> { Replace() }
        val byName = assertFailsWith<IllegalArgumentException> { Replace.of(emptySet()) }

        for (refused in listOf(byProperty, byName)) {
            assertContains(refused.message.orEmpty(), "Replace names no field, so it would replace nothing.")
            assertContains(refused.message.orEmpty(), "Replace.all()")
        }
    }

    @Test
    fun `a replace of every field names none of them, and is not refused for that`() {
        val all = Replace.all()

        assertTrue(all.everyField)
        assertTrue(all.fields.isEmpty())
        assertEquals(RemovedTargets.KEEP, all.removedTargets)
        assertEquals(RemovedTargets.DELETE_UNREFERENCED, Replace.all(RemovedTargets.DELETE_UNREFERENCED).removedTargets)
    }

    @Test
    fun `the fields named as strings are those fields, for a caller with no property to name`() {
        val replace = Replace.of(setOf("people", "companies"), RemovedTargets.DELETE_UNREFERENCED)

        assertEquals(setOf("people", "companies"), replace.fields)
        assertFalse(replace.everyField)
        assertEquals(RemovedTargets.DELETE_UNREFERENCED, replace.removedTargets)
    }

    @Test
    fun `adding and replacing are the two ways a save writes relationships`() {
        val writes: List<RelationshipWrite> = listOf(Add, Replace.all())

        assertEquals(listOf(false, true), writes.map { it is Replace })
    }
}
