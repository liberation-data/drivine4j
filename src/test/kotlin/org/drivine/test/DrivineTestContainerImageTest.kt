package org.drivine.test

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * The image [DrivineTestContainer] runs: the build's pin for `neo4j:latest`, a fallback where the pin
 * is lost, and the override. None of these starts a container.
 */
class DrivineTestContainerImageTest {

    @Test
    fun `the default image is the pin gradle test-images txt gives neo4j latest`() {
        val pin = File("gradle/test-images.txt").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")) }
            .single { it[0] == "neo4j:latest" }[1]
        assertEquals(pin, DrivineTestContainer.DEFAULT_IMAGE)
    }

    @Test
    fun `the default image is read from the build's resource`() {
        assertEquals("neo4j:1.2.3", DrivineTestContainer.defaultImage("neo4j=neo4j:1.2.3\n".byteInputStream()))
    }

    @Test
    fun `a classpath without the build's resource falls back to neo4j latest`() {
        assertEquals("neo4j:latest", DrivineTestContainer.defaultImage(null))
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
