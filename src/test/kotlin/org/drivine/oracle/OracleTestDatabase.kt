package org.drivine.oracle

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.oracle.OracleContainer

/**
 * Marks a test class that needs an Oracle database. They run only with `ORACLE_TESTS=true`: the
 * image is large, and the database wants two gigabytes beside the graph engines' containers.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
annotation class OracleTests

/** One Oracle Free container for the test JVM, started when a test first asks for it. */
object OracleTestDatabase {

    val container: OracleContainer by lazy { OracleContainer("gvenzl/oracle-free:23-slim-faststart").also { it.start() } }

    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.pooled("oracle", DatabaseType.ORACLE, container.jdbcUrl, container.username, container.password)
    }

    val pm: NonTransactionalPersistenceManager by lazy {
        NonTransactionalPersistenceManager(provider, "oracle", DatabaseType.ORACLE, SubtypeRegistry())
    }

    /** Runs each statement on its own, committed as it runs. */
    fun run(vararg statements: String) = statements.forEach { pm.execute(QuerySpecification.withStatement(it.trimIndent())) }

    /** Drops what [names] name, when it is there: `TABLE x`, `PROPERTY GRAPH g`. A graph goes before its tables. */
    fun drop(vararg names: String) = names.forEach {
        val kind = it.substringBeforeLast(' ')
        run("DROP $kind IF EXISTS ${it.substringAfterLast(' ')}${if (kind == "TABLE") " CASCADE CONSTRAINTS PURGE" else ""}")
    }
}
