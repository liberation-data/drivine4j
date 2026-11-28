package org.drivine.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * jbang-catalog.json, at the repository's root, whose alias runs this module's jar from Maven Central:
 * `jbang drivine@liberation-data/drivine4j <flags>`. Each alias runs the jar of this build's version, on the JDK the
 * module is built for, with the options `installDist`'s script runs it with, and any arguments it fixes are an
 * invocation the CLI takes.
 */
class JbangCatalogTest {

    private val catalog: JsonNode = ObjectMapper().readTree(File(System.getProperty("drivine.cli.jbangCatalog")))
    private val aliases = catalog.path("aliases").properties().associate { it.key to it.value }

    private val version = Properties().apply {
        Cli::class.java.getResourceAsStream("drivine-cli.properties")!!.use { load(it) }
    }.getProperty("version")

    @Test
    fun `the drivine alias runs the CLI with no arguments of its own, so it takes any`() {
        val drivine = aliases.getValue("drivine")
        assertTrue(drivine.path("arguments").isMissingNode, "the drivine alias fixes arguments: ${drivine["arguments"]}")
    }

    @Test
    fun `every alias runs this version's jar, on the module's JDK, with the options its script has`() {
        val javaOptions = System.getProperty("drivine.cli.jvmArgs").split(' ').filter { it.isNotEmpty() }
        val java = System.getProperty("drivine.cli.java")
        for ((name, alias) in aliases) {
            assertEquals("org.drivine:drivine4j-cli:$version", alias.path("script-ref").asText(), "$name's script-ref")
            assertEquals("$java+", alias.path("java").asText(), "$name's java")
            assertEquals(javaOptions, alias.path("java-options").map { it.asText() }, "$name's java-options")
        }
    }

    /** Args.parse throws BadInvocation for flags the CLI doesn't take, so an alias a release would ship broken fails here. */
    @Test
    fun `an alias's fixed arguments are an invocation the CLI takes`() {
        for ((name, alias) in aliases) {
            val arguments = alias.path("arguments").map { it.asText() }
            runCatching { Args.parse(arguments) }.onFailure { throw AssertionError("$name's arguments $arguments: ${it.message}", it) }
        }
    }
}
