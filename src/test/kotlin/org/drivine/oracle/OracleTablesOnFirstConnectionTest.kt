package org.drivine.oracle

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.backend.oracle.OracleStoredFunctions
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.TablesOnFirstConnectionContract
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph's tables made on an Oracle provider's first connection, in a schema made for each test: a
 * user of its own, made by the container's administrator.
 */
@OracleTests
class OracleTablesOnFirstConnectionTest : TablesOnFirstConnectionContract() {

    private val user = "fresh_${users.incrementAndGet()}"

    /** Runs [statements] as the container's administrator, through no provider. */
    private fun administer(vararg statements: String) =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.PASSWORD).use { c ->
            c.createStatement().use { s -> statements.forEach(s::execute) }
        }

    /** Drops a user an earlier run left and makes this test's, on one connection: see [provider] for why sessions are few. */
    @BeforeEach
    fun makeUser() {
        administer(
            "DROP USER IF EXISTS $user CASCADE",
            "CREATE USER $user IDENTIFIED BY $PASSWORD QUOTA UNLIMITED ON users",
            "GRANT DB_DEVELOPER_ROLE TO $user",
        )
    }

    @AfterEach
    fun dropUser() {
        endAll()
        administer("DROP USER IF EXISTS $user CASCADE")
    }

    /**
     * A pool of [POOL_MAX] sessions, not Hikari's ten: the tests need several pools connecting at once, not many sessions
     * each. With ten each the class opened about 150 sessions in a few seconds (about 80 with two), and CI's listener,
     * which counts the sessions it hands out until the instance next reports its load, then turned connections away
     * (`ORA-12516`, #384), as it did for `OracleFreshGraphTest` (#337).
     */
    override fun provider(graph: String): JdbcConnectionProvider =
        JdbcConnectionProvider.pooled("fresh", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(), user, PASSWORD, poolMax = POOL_MAX, graphName = graph)

    /** Oracle commits a schema statement as it runs it, whatever the session does of itself. */
    override fun notCommitting(graph: String): JdbcConnectionProvider = JdbcConnectionProvider(
        "fresh",
        DatabaseType.ORACLE,
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = OracleTestContainer.getJdbcUrl()
                username = user
                password = PASSWORD
                maximumPoolSize = POOL_MAX
                isAutoCommit = false
            },
        ),
        ownsDataSource = true,
        graphName = graph,
    )

    /** The same user, with what it may make taken from it: it may connect, and read what it owns. */
    override fun readOnly(graph: String): JdbcConnectionProvider {
        administer("REVOKE DB_DEVELOPER_ROLE FROM $user", "GRANT CREATE SESSION TO $user")
        return provider(graph)
    }

    override fun tables(graph: String): List<String> =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { c ->
            c.prepareStatement("SELECT lower(table_name) FROM user_tables WHERE table_name LIKE ? ESCAPE '\\' ORDER BY 1").use { s ->
                s.setString(1, "${graph.uppercase()}\\_%")
                s.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }

    /** The property graph, made last, is there. */
    override fun whole(graph: String): Boolean =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM user_property_graphs WHERE graph_name = ?").use { s ->
                s.setString(1, graph.uppercase())
                s.executeQuery().use { it.next(); it.getInt(1) == 1 }
            }
        }

    /**
     * A statement that another session's lock turns away (`ORA-00054`) is tried again until the lock is
     * let go, and not given up on: the index the block makes waits for the session that holds its table.
     */
    @Test
    fun `a statement turned away by another session's lock is tried again until the lock is let go`() {
        open().connect().release()
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { holder ->
            holder.createStatement().use {
                it.execute("DROP PROPERTY GRAPH drivine")
                it.execute("DROP INDEX drivine_edge_src")
            }
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE drivine_edge IN EXCLUSIVE MODE") }

            val making = CompletableFuture.runAsync { open().connect().release() }
            Thread.sleep(2000)
            assertFalse(making.isDone, "the block waits for the lock rather than giving up")
            holder.rollback()
            making.get(1, TimeUnit.MINUTES)
        }
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM user_indexes WHERE index_name = 'DRIVINE_EDGE_SRC'").use { it.next(); assertEquals(1, it.getInt(1)) }
                s.executeQuery("SELECT COUNT(*) FROM user_property_graphs WHERE graph_name = 'DRIVINE'").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
    }

    /**
     * A store an earlier version made has its tables and not the function a compiled Cypher statement compares values
     * that may hold a null by: a provider's first connection makes it, and a session found to call none is asked again.
     */
    @Test
    fun `a store with its tables and not its function gains the function on a provider's first connection`() {
        open().connect().release()
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { session ->
            session.createStatement().use { it.execute("DROP FUNCTION drivine_nulls_equal") }
            assertFalse(OracleStoredFunctions.nullsEqual(session, "drivine"))

            open().connect().release()
            assertTrue(OracleStoredFunctions.nullsEqual(session, "drivine"), "asked again once the provider has ensured the graph")
        }
    }

    /**
     * The test image is Oracle 23.9 or later, which has `UUID()`: a user with no grant beyond the developer role reads the
     * version from its session's metadata, and its provider runs Cypher's `randomUUID()`.
     */
    @Test
    fun `an Oracle of 23_9 or later, read by a user with no grant to read the instance, runs Cypher's randomUUID`() {
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), user, PASSWORD).use { session ->
            assertNull(JdbcConnectionProvider.oracleRefusal(session.metaData), session.metaData.databaseProductVersion)
        }
        val connection = open().connect()
        try {
            val uuid = connection.query(QuerySpecification.withStatement(cypherStatement("RETURN randomUUID() AS u")).transform(String::class.java)).single()
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uuid), uuid)
        } finally {
            connection.release()
        }
    }

    private companion object {
        const val PASSWORD = "fresh"

        /** The sessions each test pool holds. */
        const val POOL_MAX = 2

        /** Names each test's user apart from the others'. */
        val users = AtomicInteger()
    }
}
