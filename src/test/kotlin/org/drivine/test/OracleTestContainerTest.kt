package org.drivine.test

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where [OracleTestContainer] says a local instance is, when it is told to use one. Nothing here
 * asks for the container, so none is started: `instance` is decided once for the JVM, and is left
 * for the tests that want it.
 */
class OracleTestContainerTest {

    private val names = listOf("test.oracle.use-local", "test.oracle.local.host", "test.oracle.local.port")

    /** Runs [block] with the system properties [set], and puts back what was there before. */
    private fun <T> with(vararg set: Pair<String, String>, block: () -> T): T {
        val before = names.associateWith { System.getProperty(it) }
        try {
            names.forEach { System.clearProperty(it) }
            set.forEach { (name, value) -> System.setProperty(name, value) }
            return block()
        } finally {
            before.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }

    @Test
    fun `a local instance is used when the system property says so, and only then`() {
        assertTrue(with("test.oracle.use-local" to "true") { OracleTestContainer.useLocal() })
        assertTrue(with("test.oracle.use-local" to "TRUE") { OracleTestContainer.useLocal() })
        // With the property unset or false, the answer is the environment's, which a test cannot set.
        val environment = System.getenv("USE_LOCAL_ORACLE")?.toBoolean() ?: false
        assertEquals(environment, with { OracleTestContainer.useLocal() })
        assertEquals(environment, with("test.oracle.use-local" to "no") { OracleTestContainer.useLocal() })
    }

    @Test
    fun `a local instance is at the host and port the system properties give`() {
        with("test.oracle.use-local" to "true", "test.oracle.local.host" to "db.internal", "test.oracle.local.port" to "1522") {
            assertEquals("db.internal", OracleTestContainer.getConnectionHost())
            assertEquals(1522, OracleTestContainer.getConnectionPort())
            assertEquals("jdbc:oracle:thin:@//db.internal:1522/FREEPDB1", OracleTestContainer.getJdbcUrl())
        }
    }

    @Test
    fun `a local instance with nothing said of it is at the environment's host and port, or localhost and 1521`() {
        with("test.oracle.use-local" to "true") {
            assertEquals(System.getenv("ORACLE_LOCAL_HOST") ?: "localhost", OracleTestContainer.getConnectionHost())
            assertEquals((System.getenv("ORACLE_LOCAL_PORT") ?: "1521").toInt(), OracleTestContainer.getConnectionPort())
        }
    }
}
