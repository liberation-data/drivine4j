package org.drivine.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that @GraphRelationshipFragment is properly detected and metadata is extracted.
 */
class GraphRelationshipFragmentDetectionTests {

    @Test
    fun `should detect and extract relationship fragment metadata`() {
        val viewModel = GraphViewModel.from(TestViewWithRelationshipFragment::class.java)

        // Should have one relationship
        assertEquals(1, viewModel.relationships.size)

        val relationship = viewModel.relationships[0]

        // Verify it's detected as a relationship fragment
        assertTrue(relationship.isRelationshipFragment)

        // Verify target field info
        assertEquals("target", relationship.targetFieldName)
        assertEquals(TestNode::class.java, relationship.targetNodeType)

        // Verify relationship properties
        assertEquals(1, relationship.relationshipProperties.size)
        assertTrue(relationship.relationshipProperties.contains("createdAt"))

        // Verify element type is the fragment class itself
        assertEquals(TestRelationshipFragment::class.java, relationship.elementType)
    }
}
