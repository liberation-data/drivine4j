package org.drivine.testimages

import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/**
 * Runs an image a test names by a moving tag as the image `gradle/test-images.txt` pins for it, so
 * that an engine's new release does not change what the suite runs until the pin is moved. The build
 * hands each pin to the tests as a system property; an image with no pin is run as named, as every
 * image is when the tests are not run by Gradle.
 */
class PinnedImages : ImageNameSubstitutor() {

    override fun apply(original: DockerImageName): DockerImageName =
        System.getProperty(PREFIX + original.asCanonicalNameString())
            ?.let { DockerImageName.parse(it).asCompatibleSubstituteFor(original) }
            ?: original

    override fun getDescription(): String = "the images pinned in gradle/test-images.txt"

    private companion object {
        const val PREFIX = "drivine.test.image."
    }
}
