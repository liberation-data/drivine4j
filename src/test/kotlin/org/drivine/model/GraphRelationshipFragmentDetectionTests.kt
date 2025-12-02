package org.drivine.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Tests that @GraphRelationshipFragment is properly detected and throws an error
 * when used (reserving design space for future implementation).
 */
class GraphRelationshipFragmentDetectionTests {

    @Test
    fun `should throw UnsupportedOperationException when using GraphRelationshipFragment`() {
        val exception = assertThrows<UnsupportedOperationException> {
            GraphViewModel.from(TestViewWithRelationshipFragment::class.java)
        }

        assertTrue(exception.message!!.contains("Relationship fragments"))
        assertTrue(exception.message!!.contains("not yet supported"))
        assertTrue(exception.message!!.contains("TestRelationshipFragment"))
    }
}