package org.drivine.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.drivine.schema.SchemaExecutor
import org.drivine.schema.SchemaStatement
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A schema index build or drop `CONCURRENTLY` on a connection a Spring transaction holds (#724), which Postgres refuses
 * (`25001`): it runs within a savepoint of its own, so the transaction outlives the refusal, and the build or drop the
 * schema planner runs next without `CONCURRENTLY` runs in it, and commits with the application's work (#845). Without
 * the savepoint the refusal ends the transaction, and the next statement fails with `25P02`.
 */
@PostgresTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOnlineIndexBuildInTransactionTest {

    private lateinit var pool: HikariDataSource
    private lateinit var provider: JdbcConnectionProvider
    private lateinit var executor: SchemaExecutor
    private lateinit var template: TransactionTemplate
    private lateinit var app: JdbcTemplate

    private val run = "online-" + UUID.randomUUID().toString().take(8)
    private val index = "${GRAPH}_ix_online"

    @BeforeAll
    fun setUp() {
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $TABLE", "CREATE TABLE $TABLE (run text NOT NULL, name text NOT NULL)")
        pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = PostgresTestContainer.getJdbcUrl()
                username = PostgresTestContainer.getConnectionUsername()
                password = PostgresTestContainer.getConnectionPassword()
                maximumPoolSize = 4
            },
        )
        provider = JdbcConnectionProvider("onlineBuild", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(pool), graphName = GRAPH)
        // The graph's tables are made on the provider's first connection, which is had outside a Spring transaction.
        provider.connect().release(null)
        executor = SchemaExecutor(provider)
        template = TransactionTemplate(DataSourceTransactionManager(pool))
        app = JdbcTemplate(pool)
    }

    @AfterEach
    fun clean() {
        PostgresTestDatabase.run("DROP INDEX IF EXISTS $index", "DELETE FROM $TABLE")
    }

    @AfterAll
    fun tearDown() {
        provider.end()
        pool.close()
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $TABLE")
    }

    private fun ddl(statement: String) = executor.execute(SchemaStatement.Cypher(statement))

    private fun indexed(): Boolean = PostgresTestDatabase.pm.query(
        QuerySpecification.withStatement(sqlStatement("SELECT count(*) FROM pg_indexes WHERE indexname = \$name")).bind(mapOf("name" to index)).transform(Long::class.java),
    ).single() == 1L

    private fun committed(): List<String> = PostgresTestDatabase.pm.query(
        QuerySpecification.withStatement(sqlStatement("SELECT name FROM $TABLE WHERE run = \$run ORDER BY name")).bind(mapOf("run" to run)).transform(String::class.java),
    )

    private fun appWrite(name: String) {
        app.update("INSERT INTO $TABLE (run, name) VALUES (?, ?)", run, name)
    }

    @Test
    fun `a build refused CONCURRENTLY in a Spring transaction leaves it going, so the plain build after it runs and commits with it`() {
        val refusals = mutableListOf<String?>()
        template.executeWithoutResult {
            appWrite("before")
            refusals += runCatching { ddl("CREATE INDEX CONCURRENTLY IF NOT EXISTS $index ON $TABLE (name)") }.exceptionOrNull()?.let(::sqlState)
            ddl("CREATE INDEX IF NOT EXISTS $index ON $TABLE (name)")
            appWrite("after")
        }
        assertEquals(listOf<String?>("25001"), refusals)
        assertTrue(indexed())
        assertEquals(listOf("after", "before"), committed())
    }

    @Test
    fun `a drop refused CONCURRENTLY in a Spring transaction leaves it going, so the plain drop after it runs and commits with it`() {
        PostgresTestDatabase.run("CREATE INDEX $index ON $TABLE (name)")
        val refusals = mutableListOf<String?>()
        template.executeWithoutResult {
            appWrite("before")
            refusals += runCatching { ddl("DROP INDEX CONCURRENTLY IF EXISTS $index") }.exceptionOrNull()?.let(::sqlState)
            ddl("DROP INDEX IF EXISTS $index")
            appWrite("after")
        }
        assertEquals(listOf<String?>("25001"), refusals)
        assertTrue(!indexed())
        assertEquals(listOf("after", "before"), committed())
    }

    /** The SQL state of the first SQL error [e] holds. */
    private fun sqlState(e: Throwable): String? =
        generateSequence(e) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState

    private companion object {
        const val GRAPH = "online_tx"
        const val TABLE = "online_tx_app"
    }
}
