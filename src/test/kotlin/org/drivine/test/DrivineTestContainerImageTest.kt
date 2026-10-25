package org.drivine.test

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The image [DrivineTestContainer] runs when told to run another. This does not start a container.
 */
class DrivineTestContainerImageTest {

    @Test
    fun `test neo4j image names the image in place of the default`() {
        val before = System.getProperty("test.neo4j.image")
        try {
            System.setProperty("test.neo4j.image", "neo4j:5.26-community")
            assertEquals("neo4j:5.26-community", DrivineTestContainer.image())
        } finally {
            if (before == null) System.clearProperty("test.neo4j.image") else System.setProperty("test.neo4j.image", before)
        }
    }
}
