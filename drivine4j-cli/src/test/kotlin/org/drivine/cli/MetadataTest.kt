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
        assertTrue(registered("java.io.Console", "isTerminal"), "java.io.Console's isTerminal isn't registered")
    }

    /**
     * Each method of a driver's class the core calls by reflection, as it doesn't compile against the driver, is registered.
     * A trace keeps only what the tests reached, and these are reached on paths a run seldom takes. A native image without
     * one finds no such method:
     * - Oracle's JSON reader (JdbcResultMapper.oracleCall) fails to read the value (#900);
     * - Postgres's catalog check (StoreIdentityResolver.inPostgresCatalog) quietly answers no, so a first boot whose
     *   making of the store's key constraint loses the race in the catalog to another's isn't read as made meanwhile:
     *   it doesn't ensure the constraint again, and, finding the winner's not yet in force, warns and writes its stamp
     *   best-effort (#901).
     */
    @Test
    fun `each driver method the core calls by reflection is kept`() {
        val called = listOf(
            "oracle.sql.json.OracleJsonValue" to "getOracleJsonType",
            "oracle.sql.json.OracleJsonString" to "getString",
            "oracle.sql.json.OracleJsonNumber" to "doubleValue",
            "oracle.sql.json.OracleJsonNumber" to "bigDecimalValue",
            "org.postgresql.util.PSQLException" to "getServerErrorMessage",
            "org.postgresql.util.ServerErrorMessage" to "getSchema",
        )
        val missing = called.filterNot { (type, method) -> registered(type, method) }
        assertTrue(missing.isEmpty(), "Not registered: $missing")
    }

    /** Whether [type]'s method [name] of no parameters is registered for reflection. */
    private fun registered(type: String, name: String): Boolean =
        com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata)["reflection"].any { entry ->
            entry["type"]?.asText() == type &&
                entry["methods"]?.any { it["name"]?.asText() == name && it["parameterTypes"]?.isEmpty == true } == true
        }
}
