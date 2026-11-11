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

    /**
     * `Console.isTerminal`, which the CLI calls by reflection to tell a pipe on stdin from a terminal, is registered: a
     * native image without it would take a pipe for a terminal wherever a console is there, and refuse stdin's statements.
     */
    @Test
    fun `Console's isTerminal, which the CLI calls by reflection, is kept`() {
        val console = com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata)["reflection"]
            .firstOrNull { it["type"]?.asText() == "java.io.Console" }
        assertNotNull(console, "java.io.Console isn't registered")
        assertTrue(console["methods"].any { it["name"].asText() == "isTerminal" && it["parameterTypes"].isEmpty })
    }
}
