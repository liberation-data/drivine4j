package org.drivine.standalone

import org.drivine.Drivine
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * These tests run on the core's own runtime classpath. Its Spring support is in drivine4j-spring and the
 * starter, so a class from each Spring jar the core once brought along is not on it.
 */
class CoreDependenciesTest {

    @Test
    fun `the core brings no Spring module of its own`() {
        listOf(
            "org.springframework.core.SpringVersion",
            "org.springframework.context.ApplicationContext",
            "org.springframework.transaction.PlatformTransactionManager",
            "org.springframework.boot.SpringApplication",
            "org.springframework.test.context.TestContext",
            "org.aspectj.weaver.Advice",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test
    fun `the JDBC stores' classes load without SQLite's driver, which the application adds`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.sqlite.Function") }
        listOf(
            "org.drivine.connection.JdbcConnectionProvider",
            "org.drivine.connection.JdbcConnection",
            "org.drivine.mapper.JdbcResultMapper",
            "org.drivine.backend.GraphBackends",
            "org.drivine.connection.ConnectionProviderBuilder",
        ).forEach { Class.forName(it) }
    }

    @Test
    fun `the Cypher compiler, a module of its own, compiles on the core's classpath with the runtime it brings`() {
        // Its parser's runtime (antlr-kotlin), its JSON (kotlinx.serialization) and its numbers (bignum) are runtime
        // dependencies of the compiler's, not drivine4j's: each statement below needs them, and its parameters their JSON.
        val parameters = mapOf("name" to "Ada", "props" to mapOf("born" to 1815, "score" to 1.5))
        val cypher = "MATCH (n:Person {name: \$name}) SET n += \$props RETURN n.name AS name"
        assertTrue(PostgresCypherCompiler("g").compile(cypher, parameters).sql.isNotBlank())
        assertTrue(SqliteCypherCompiler("g").compile(cypher, parameters).sql.isNotBlank())
        assertTrue(OracleCypherCompiler("g").compile(cypher, parameters).sql.isNotBlank())
    }

    @Test
    fun `the core's classes load on its own classpath`() {
        Class.forName(Drivine::class.java.name)
        Class.forName(Transactions::class.java.name)
        Class.forName("org.drivine.manager.PersistenceManagerFactory")
    }
}
