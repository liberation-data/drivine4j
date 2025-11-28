package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simple unit test for filterIsInstance that doesn't require database/Spring.
 * Tests the post-processor behavior directly.
 */
class SimpleFilterIsInstanceTest {

    sealed class Shape
    data class Circle(val radius: Double) : Shape()
    data class Rectangle(val width: Double, val height: Double) : Shape()
    data class Triangle(val base: Double, val height: Double) : Shape()

    @Test
    fun `filterIsInstance with reified type works`() {
        // Create a spec that would produce mixed types
        val spec = QuerySpecification
            .withStatement("RETURN 1")
            .filterIsInstance<Circle>()

        // Verify the post-processors were added
        val processors = spec.postProcessors
        assertEquals(2, processors.size)  // Filter + Map
        println("Post-processors added: $processors")
    }

    @Test
    fun `filterIsInstance with Class parameter works`() {
        val spec = QuerySpecification
            .withStatement("RETURN 1")
            .filterIsInstance(Rectangle::class.java)

        // Verify the post-processors were added
        val processors = spec.postProcessors
        assertEquals(2, processors.size)  // Filter + Map
        println("Post-processors added: $processors")
    }

    @Test
    fun `filterIsInstance can be chained`() {
        val spec = QuerySpecification
            .withStatement("RETURN 1")
            .filterIsInstance<Shape>()
            .filterIsInstance<Circle>()

        // First spec should have 2 processors, chained spec should reference it
        assertTrue(spec.originalSpec != null)
        println("Chained spec created with original: ${spec.originalSpec}")
    }
}
