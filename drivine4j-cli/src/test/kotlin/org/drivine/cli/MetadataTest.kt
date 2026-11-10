package org.drivine.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reachability metadata the native binary is built with, as the trace job's output is merged into it (see
 * README.md): nothing of one platform, as the binaries for macOS and Windows build from it too, and nothing of the JVM
 * the agent ran on that the CLI doesn't reach.
 */
class MetadataTest {

    private val metadata = assertNotNull(
        javaClass.getResourceAsStream("/META-INF/native-image/org.drivine/drivine4j-cli/reachability-metadata.json"),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `sqlite-jdbc's native library is its own feature's to add, for the platform the binary is built on`() {
        assertTrue("org/sqlite/native/" !in metadata)
    }

    @Test
    fun `JFR, which the CLI doesn't run, is left out`() {
        assertTrue("jdk.jfr" !in metadata && "jdk/jfr/" !in metadata && "jdk.management.jfr" !in metadata)
    }

    @Test
    fun `the resource --version reads is kept`() {
        assertTrue("org/drivine/cli/drivine-cli.properties" in metadata)
    }
}
