package org.drivine.postgres

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Marks a test class that needs a Postgres database. They run only with `POSTGRES_TESTS=true` while
 * the Postgres backend is being built: see `docs/postgres-sql-backend-plan.md`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "true")
annotation class PostgresTests

/** The test JVM's Postgres, from [PostgresTestContainer]: a container started when a test first asks for it, or a local instance. */
object PostgresTestDatabase {

    /** One pool for the test JVM, closed when it ends. */
    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.pooled(
            "postgres", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
            PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
        ).also { pool -> Runtime.getRuntime().addShutdownHook(Thread { pool.end() }) }
    }

    val pm: NonTransactionalPersistenceManager by lazy {
        NonTransactionalPersistenceManager(provider, "postgres", DatabaseType.POSTGRES, SubtypeRegistry())
    }

    /** Runs each statement on its own, committed as it runs. */
    fun run(vararg statements: String) = statements.forEach { pm.execute(QuerySpecification.withStatement(it.trimIndent())) }
}
