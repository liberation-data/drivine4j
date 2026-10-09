package org.drivine.model

import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import sample.stateless.ClaimEmployers
import sample.stateless.ClaimReviewers
import sample.stateless.ClaimStats
import sample.stateless.UndeclaredAggregate
import sample.stateless.UndeclaredCount
import sample.stateless.UndeclaredPath

/**
 * A field that no save can write must say so. A `@GraphPath`, `@Count` or `@Aggregate` field
 * without `@ReadOnly` is rejected when the view's model is built, with a message that names the
 * field and the annotation to add.
 */
class ReadOnlyFieldTests {

    @Test
    fun `a path field must be declared read-only`() {
        val e = assertFailsWith<IllegalArgumentException> { GraphViewModel.from(UndeclaredPath::class.java) }
        assertContains(e.message.orEmpty(), "employers")
        assertContains(e.message.orEmpty(), "@ReadOnly")
    }

    @Test
    fun `a count field must be declared read-only`() {
        val e = assertFailsWith<IllegalArgumentException> { GraphViewModel.from(UndeclaredCount::class.java) }
        assertContains(e.message.orEmpty(), "mentionCount")
        assertContains(e.message.orEmpty(), "@ReadOnly")
    }

    @Test
    fun `an aggregate field must be declared read-only`() {
        val e = assertFailsWith<IllegalArgumentException> { GraphViewModel.from(UndeclaredAggregate::class.java) }
        assertContains(e.message.orEmpty(), "averageWeight")
        assertContains(e.message.orEmpty(), "@ReadOnly")
    }

    @Test
    fun `path, count and relationship fields declared read-only are accepted`() {
        GraphViewModel.from(ClaimEmployers::class.java)
        GraphViewModel.from(ClaimStats::class.java)
        GraphViewModel.from(ClaimReviewers::class.java)
    }
}
