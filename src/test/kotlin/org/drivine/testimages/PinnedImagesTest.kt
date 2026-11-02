package org.drivine.testimages

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The image [PinnedImages] runs for the one a test names: the pin the build hands it, or, with no pin, as
 * named. The build hands none when run with `-Ptest.images.unpinned=true`, as the nightly workflow runs
 * it, but for a moving tag that isn't the engine's latest release. This does not start a container.
 */
class PinnedImagesTest {

    private val named = DockerImageName.parse("example/engine:latest")
    private val property = "drivine.test.image.example/engine:latest"

    @Test
    fun `an image with a pin is run as the pin`() {
        System.setProperty(property, "example/engine:1.2.3")
        try {
            assertEquals("example/engine:1.2.3", PinnedImages().apply(named).asCanonicalNameString())
        } finally {
            System.clearProperty(property)
        }
    }

    @Test
    fun `an image with no pin is run as named`() {
        assertEquals(named, PinnedImages().apply(named))
    }

    @Test
    fun `the build hands the pins unless told to run unpinned`() {
        val neo4j = System.getProperty("drivine.test.image.neo4j:latest")
        if (System.getProperty("drivine.test.images.unpinned") == "true") {
            assertNull(neo4j, "an unpinned run hands no pin")
            assertEquals("neo4j:latest", PinnedImages().apply(DockerImageName.parse("neo4j:latest")).asCanonicalNameString())
            // Docker Hub's falkordb/falkordb:latest isn't always FalkorDB's latest release: see build.gradle.
            assertEquals(
                "ghcr.io/falkordb/falkordb:latest",
                PinnedImages().apply(DockerImageName.parse("falkordb/falkordb:latest")).asCanonicalNameString(),
            )
        } else {
            assumeTrue(neo4j != null, "not run by Gradle, which hands the pins")
            assertEquals(neo4j, PinnedImages().apply(DockerImageName.parse("neo4j:latest")).asCanonicalNameString())
        }
    }
}
