package org.drivine.test

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The image [DrivineTestContainer] runs, by default and when told to run another. This does not start a container.
 */
class DrivineTestContainerImageTest {

    @Test
    fun `with no override the image is neo4j latest`() {
        assumeTrue(System.getenv("NEO4J_TEST_IMAGE") == null, "NEO4J_TEST_IMAGE names another image")
        val before = System.getProperty("test.neo4j.image")
        try {
            System.clearProperty("test.neo4j.image")
            assertEquals("neo4j:latest", DrivineTestContainer.image())
        } finally {
            if (before != null) System.setProperty("test.neo4j.image", before)
        }
    }

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
