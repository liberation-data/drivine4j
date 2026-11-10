package org.drivine.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.test.PostgresTestContainer
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import java.io.PrintWriter
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Drivine transaction on the connection of a Spring transaction, which Spring's `TransactionAwareDataSourceProxy` hands
 * Drivine, joins it (#724): it commits nothing and rolls back nothing of itself, and leaves the connection committing
 * nothing of itself, so the application's work before it and after it stays the Spring transaction's. One that rolls
 * back marks the Spring transaction to roll back. The Spring transaction is a [DataSourceTransactionManager]'s over a
 * HikariCP pool, and the Drivine one a block of [Transactions] over a provider given the proxy, as the issue has it.
 */
@PostgresTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSpringTransactionJoinTest {

    private lateinit var pool: HikariDataSource
    private lateinit var provider: JdbcConnectionProvider
    private lateinit var transactions: Transactions
    private lateinit var drivine: PersistenceManager
    private lateinit var template: TransactionTemplate
    private lateinit var app: JdbcTemplate

    /** Reads what is committed, on a connection of the test JVM's own pool, which commits each statement. */
    private lateinit var reader: NonTransactionalPersistenceManager

    private val run = "join-" + UUID.randomUUID().toString().take(8)

    @BeforeAll
    fun setUp() {
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $APP", "CREATE TABLE $APP (run text NOT NULL, name text NOT NULL)")
        pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = PostgresTestContainer.getJdbcUrl()
                username = PostgresTestContainer.getConnectionUsername()
                password = PostgresTestContainer.getConnectionPassword()
                maximumPoolSize = 4
                addDataSourceProperty("stringtype", "unspecified")
            },
        )
        provider = JdbcConnectionProvider("springJoin", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(pool), graphName = GRAPH)
        // The graph's tables are made on the provider's first connection, which is had outside a Spring transaction.
        provider.connect().release(null)
        val drivineSide = drivineOver(provider)
        transactions = drivineSide.first
        drivine = drivineSide.second
        template = TransactionTemplate(DataSourceTransactionManager(pool))
        app = JdbcTemplate(pool)
        reader = PostgresTestDatabase.graphManager(GRAPH)
    }

    @AfterEach
    fun clean() {
        reader.execute(QuerySpecification.withStatement(cypherStatement("MATCH (n:Note {run: \$run}) DELETE n")).bind(mapOf("run" to run)))
        PostgresTestDatabase.pm.execute(QuerySpecification.withStatement(sqlStatement("DELETE FROM $APP WHERE run = \$run")).bind(mapOf("run" to run)))
    }

    @AfterAll
    fun tearDown() {
        provider.end()
        pool.close()
        PostgresTestDatabase.drop(GRAPH)
        PostgresTestDatabase.run("DROP TABLE IF EXISTS $APP")
    }

    /** Drivine's transactions, and a transactional manager whose statements run in them, over [provider]. */
    private fun drivineOver(provider: JdbcConnectionProvider): Pair<Transactions, PersistenceManager> {
        val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
        val holder = TransactionContextHolder(registry)
        return Transactions(holder) to PersistenceManagerFactory(registry, holder).get(provider.name, PersistenceManagerType.TRANSACTIONAL)
    }

    /** An application statement, on the Spring transaction's connection where one is open. */
    private fun appWrite(name: String) {
        app.update("INSERT INTO $APP (run, name) VALUES (?, ?)", run, name)
    }

    /** A note written by [pm], in whatever transaction of Drivine's is open. */
    private fun note(pm: PersistenceManager, name: String) = pm.execute(
        QuerySpecification.withStatement(cypherStatement("CREATE (:Note {run: \$run, name: \$name})")).bind(mapOf("run" to run, "name" to name)),
    )

    /** A Drivine transaction of its own that writes a note and commits. */
    private fun drivineWrite(name: String) = transactions.transaction { note(drivine, name) }

    /** What is committed, read on another connection: `app:` rows of the application's table and `drivine:` notes, in order. */
    private fun committed(): List<String> {
        val rows = PostgresTestDatabase.pm.query(
            QuerySpecification.withStatement(sqlStatement("SELECT name FROM $APP WHERE run = \$run")).bind(mapOf("run" to run)).transform(String::class.java),
        )
        val notes = reader.query(
            QuerySpecification.withStatement(cypherStatement("MATCH (n:Note {run: \$run}) RETURN n.name")).bind(mapOf("run" to run)).transform(String::class.java),
        )
        return (rows.map { "app:$it" } + notes.map { "drivine:$it" }).sorted()
    }

    /**
     * Runs [block] in a Spring transaction and gives what it threw, or null: the tests check what was committed before
     * they look at that, so a run that commits what it should not says what it committed.
     */
    private fun inSpring(block: (TransactionStatus) -> Unit): Throwable? = runCatching { template.executeWithoutResult(block) }.exceptionOrNull()

    @Test
    fun `a Drivine transaction inside a Spring transaction set to roll back is rolled back with it, and so is the application's work around it`() {
        val failed = inSpring { status ->
            appWrite("before")
            drivineWrite("drivine")
            appWrite("after")
            status.setRollbackOnly()
        }

        assertEquals(emptyList(), committed())
        assertNull(failed)
    }

    @Test
    fun `a Drivine transaction inside a Spring transaction that commits is committed at the Spring commit, with the application's work, and not before`() {
        var afterDrivine: List<String>? = null
        var afterApp: List<String>? = null
        val failed = inSpring {
            appWrite("before")
            drivineWrite("drivine")
            afterDrivine = committed()
            appWrite("after")
            afterApp = committed()
        }

        assertEquals(emptyList(), afterDrivine, "Drivine's commit committed none of the Spring transaction")
        assertEquals(emptyList(), afterApp, "the application's next statement was not committed as it ran")
        assertNull(failed)
        assertEquals(listOf("app:after", "app:before", "drivine:drivine"), committed())
    }

    @Test
    fun `a Drivine transaction that commits inside a Spring transaction that then rolls back, marked or failing, persists nothing`() {
        val marked = inSpring { status ->
            appWrite("before")
            drivineWrite("drivine")
            appWrite("after")
            status.setRollbackOnly()
        }
        assertEquals(emptyList(), committed())
        assertNull(marked)

        val failing = inSpring {
            appWrite("before")
            drivineWrite("drivine")
            appWrite("after")
            error("the application fails after Drivine committed")
        }
        assertEquals(emptyList(), committed())
        assertIs<IllegalStateException>(failing)
    }

    @Test
    fun `a Drivine transaction that fails inside a Spring transaction marks it to roll back, so Spring rolls it back where it was to commit`() {
        var marked: Boolean? = null
        val failed = inSpring { status ->
            appWrite("before")
            // The application catches Drivine's failure and carries on, as a caller of a failed @Transactional method may.
            assertFailsWith<IllegalStateException> {
                transactions.transaction {
                    note(drivine, "drivine")
                    error("Drivine's block fails")
                }
            }
            marked = status.isRollbackOnly
            appWrite("after")
        }

        assertEquals(emptyList(), committed())
        assertEquals(true, marked, "Drivine's rollback marked the Spring transaction to roll back")
        assertIs<UnexpectedRollbackException>(failed)
        // The holder's mark is read before the commit, so the synchronization that fails the commit for managers that
        // don't read it (see PostgresJpaTransactionJoinTest) never runs here: the exception is Spring's own.
        assertEquals("Transaction rolled back because it has been marked as rollback-only", failed.message)
    }

    @Test
    fun `a Drivine transaction that fails inside a NESTED Spring transaction rolls back to its savepoint with it, and the outer transaction commits the rest`() {
        val nested = TransactionTemplate(template.transactionManager!!).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }
        var nestedFailed: Throwable? = null
        val failed = inSpring {
            appWrite("before")
            nestedFailed = runCatching {
                nested.executeWithoutResult {
                    appWrite("nested")
                    assertFailsWith<IllegalStateException> {
                        transactions.transaction {
                            note(drivine, "drivine")
                            error("Drivine's block fails")
                        }
                    }
                }
            }.exceptionOrNull()
            appWrite("after")
        }

        // The savepoint's rollback clears the holder's mark, so the veto lets the outer transaction commit.
        assertEquals(listOf("app:after", "app:before"), committed())
        assertIs<UnexpectedRollbackException>(nestedFailed)
        assertNull(failed)
    }

    @Test
    fun `without a Spring transaction a Drivine transaction on the proxy commits, or rolls back, by itself, and gives its connection back committing each statement`() {
        val given = RecordingDataSource(TransactionAwareDataSourceProxy(pool))
        val own = JdbcConnectionProvider("springJoinOwn", DatabaseType.POSTGRES, given, graphName = GRAPH)
        try {
            val (ownTransactions, pm) = drivineOver(own)

            ownTransactions.transaction { note(pm, "drivine") }
            assertEquals(listOf("drivine:drivine"), committed())

            assertFailsWith<IllegalStateException> {
                ownTransactions.transaction {
                    note(pm, "rolled back")
                    error("Drivine's block fails")
                }
            }
            assertEquals(listOf("drivine:drivine"), committed())

            // Every connection Drivine had went back to the pool committing each statement, as the pool gave it.
            assertTrue(given.closedCommitting.isNotEmpty())
            assertEquals(List(given.closedCommitting.size) { true }, given.closedCommitting)
        } finally {
            own.end()
        }
    }

    /** Hands out [target]'s connections, noting of each as it is closed whether it commits each statement. */
    private class RecordingDataSource(private val target: DataSource) : DataSource by target {
        val closedCommitting = mutableListOf<Boolean>()

        override fun getConnection(): java.sql.Connection = recording(target.connection)

        override fun getConnection(username: String?, password: String?): java.sql.Connection = recording(target.getConnection(username, password))

        override fun getLogWriter(): PrintWriter? = target.logWriter

        override fun getParentLogger(): Logger = target.parentLogger

        private fun recording(inner: java.sql.Connection): java.sql.Connection =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                when (method.name) {
                    "isWrapperFor" -> (args[0] as Class<*>).isInstance(inner) || inner.isWrapperFor(args[0] as Class<*>)
                    "unwrap" -> if ((args[0] as Class<*>).isInstance(inner)) inner else inner.unwrap(args[0] as Class<*>)
                    else -> {
                        if (method.name == "close") closedCommitting += inner.autoCommit
                        try {
                            method.invoke(inner, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.cause!!
                        }
                    }
                }
            } as java.sql.Connection
    }

    private companion object {
        const val GRAPH = "spring_join"
        const val APP = "spring_join_app"
    }
}
