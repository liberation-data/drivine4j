package org.drivine.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import sample.stateless.ClaimStampedCitations
import sample.stateless.JavaStampedRecord
import sample.stateless.StampedView

/** A stamp is a node's: `@NodeStamp` is declared on a fragment, in Kotlin or in Java, and nowhere else. */
class NodeStampPlacementTest {

    @Test
    fun `a stamp field on a view is refused`() {
        assertEquals(
            "@NodeStamp field 'stamp' on StampedView, which is a @GraphView: a stamp is a node's, so it is declared on a @NodeFragment. " +
                "Here it would be neither loaded nor compared. Declare it on its root fragment.",
            assertFailsWith<IllegalArgumentException> { GraphViewModel.from(StampedView::class.java) }.message,
        )
    }

    @Test
    fun `a stamp field on a relationship fragment is refused`() {
        assertEquals(
            "@NodeStamp field 'stamp' on StampedCitation, which is a @RelationshipFragment: a stamp is a node's, so it is declared on a @NodeFragment. " +
                "Here it would be neither loaded nor compared. Declare it on the fragment it points at.",
            assertFailsWith<IllegalArgumentException> { GraphViewModel.from(ClaimStampedCitations::class.java) }.message,
        )
    }

    @Test
    fun `a Java class declares a stamp field, whose type does not say it can be null`() {
        assertEquals("stamp", FragmentModel.from(JavaStampedRecord::class.java).stampField)
    }
}
