package org.drivine.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimStats
import sample.stateless.JavaClaimSummary
import sample.stateless.UndeclaredAggregate
import sample.stateless.UndeclaredCount
import sample.stateless.UndeclaredPath

/**
 * A `@GraphPath`, `@Count` or `@Aggregate` field is read-only whether or not it says so: its view's
 * model builds without `@ReadOnly`, and no save writes it. On a `@GraphRelationship` field
 * `@ReadOnly` is a choice, and on the other three it is allowed and changes nothing.
 */
class ReadOnlyFieldTests {

    private fun GraphViewModel.readOnly(field: String): Boolean = relationships.single { it.fieldName == field }.readOnly

    @Test
    fun `a path field is read-only without being declared so`() {
        val model = GraphViewModel.from(UndeclaredPath::class.java)

        assertTrue(model.readOnly("employers"))
    }

    @Test
    fun `a view with a count field builds without the field being declared read-only`() {
        val model = GraphViewModel.from(UndeclaredCount::class.java)

        assertEquals(listOf("mentionCount"), model.aggregateFields.map { it.fieldName })
        assertEquals(emptyList(), model.relationships, "a count is no relationship, so no save writes it")
    }

    @Test
    fun `a view with an aggregate field builds without the field being declared read-only`() {
        val model = GraphViewModel.from(UndeclaredAggregate::class.java)

        assertEquals(listOf("averageWeight"), model.aggregateFields.map { it.fieldName })
        assertEquals(emptyList(), model.relationships, "an aggregate is no relationship, so no save writes it")
    }

    @Test
    fun `a path or count field may still be declared read-only`() {
        assertTrue(GraphViewModel.from(ClaimEmployers::class.java).readOnly("employers"))
        assertEquals(listOf("mentionCount"), GraphViewModel.from(ClaimStats::class.java).aggregateFields.map { it.fieldName })
    }

    @Test
    fun `a relationship field is read-only only when it is declared so`() {
        val model = GraphViewModel.from(ClaimReviewers::class.java)

        assertTrue(model.readOnly("reviewers"))
        assertFalse(model.readOnly("people"))
        assertFalse(GraphViewModel.from(ClaimEmployers::class.java).readOnly("people"), "a path beside it does not make it read-only")
    }

    @Test
    fun `a Java view follows the same rules`() {
        val model = GraphViewModel.from(JavaClaimSummary::class.java)

        assertTrue(model.readOnly("employers"), "a path, not declared read-only")
        assertTrue(model.readOnly("reviewers"), "a relationship declared read-only")
        assertFalse(model.readOnly("people"))
        assertEquals(setOf("mentionCount", "averageWeight"), model.aggregateFields.map { it.fieldName }.toSet())
    }
}
