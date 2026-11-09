package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.schema.UnsupportedSchemaGrammar
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.PrintWriter
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a [JdbcConnectionProvider] says of itself and does with its data source, and what the builder asks before it makes one for Oracle. */
class JdbcConnectionProviderTest {

    /** A data source that can be closed, as a pool can, and says whether it was. */
    private class ClosableDataSource(private val connection: java.sql.Connection) : DataSource, AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }

        override fun getConnection(): java.sql.Connection = connection
        override fun getConnection(username: String?, password: String?): java.sql.Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private val statement = Mockito.mock(java.sql.Statement::class.java)
    private val jdbc = Mockito.mock(java.sql.Connection::class.java).also { `when`(it.createStatement()).thenReturn(statement) }
    private val dataSource = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(jdbc) }

    // ----- The provider -----

    @Test
    fun `a session of the application's Oracle data source is set to make no adaptive plan, once`() {
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource)

        provider.connect()
        provider.connect()

        // The data source hands out the same session twice, as a pool does: it is set the first time only.
        verify(statement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
        verify(statement, times(1)).close()
    }

    @Test
    fun `a session of the application's Oracle data source that cannot be set is closed, and not given out`() {
        val refused = SQLException("ORA-02248: invalid option for ALTER SESSION")
        `when`(statement.execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)).thenThrow(refused)

        assertSame(refused, assertFailsWith<SQLException> { JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource).connect() })
        verify(jdbc).close()
    }

    @Test
    fun `a session of a pool the provider made, or of another store, is not set again on connect`() {
        JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, ownsDataSource = true).connect()
        JdbcConnectionProvider("default", DatabaseType.POSTGRES, dataSource).connect()
        JdbcConnectionProvider("default", DatabaseType.SQLITE, dataSource).connect()

        verify(jdbc, never()).createStatement()
    }

    @Test
    fun `a session of the application's Oracle data source is left as it is when the application asks for that`() {
        JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, setsNoAdaptivePlans = false).connect()

        verify(jdbc, never()).createStatement()
    }

    @Test
    fun `a session of the application's Oracle data source is set when the application asks for that, as by default`() {
        JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, setsNoAdaptivePlans = true).connect()

        verify(statement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
    }

    @Test
    fun `a session a pool hands out under a new handle is known by the driver's connection behind it, and set once`() {
        val handles = List(2) { Mockito.mock(java.sql.Connection::class.java) }
        handles.forEach {
            `when`(it.unwrap(java.sql.Connection::class.java)).thenReturn(jdbc)
            `when`(it.createStatement()).thenReturn(statement)
        }
        val pool = Mockito.mock(DataSource::class.java)
        `when`(pool.connection).thenReturn(handles[0], handles[1])
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, pool)

        provider.connect()
        provider.connect()

        verify(statement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
    }

    @Test
    fun `a session that could not be set, handed out again by the application's pool, is set on the next connect`() {
        `when`(statement.execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)).thenThrow(SQLException("ORA-03113")).thenReturn(false)
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource)

        assertFailsWith<SQLException> { provider.connect() }
        provider.connect()
        provider.connect()

        verify(statement, times(2)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
    }

    @Test
    fun `a session of the application's HikariCP pool that already runs the statement as it opens one is not set again`() {
        val pool = object : com.zaxxer.hikari.HikariDataSource() {
            override fun getConnection(): java.sql.Connection = jdbc
        }.apply { connectionInitSql = JdbcConnectionProvider.NO_ADAPTIVE_PLANS }

        JdbcConnectionProvider("default", DatabaseType.ORACLE, pool).connect()

        verify(jdbc, never()).createStatement()
    }

    @Test
    fun `the thin-driver URL of an Oracle service names the host, the port and the service`() {
        assertEquals("jdbc:oracle:thin:@//db.example.com:1521/FREEPDB1", JdbcConnectionProvider.oracleUrl("db.example.com", 1521, "FREEPDB1"))
        assertEquals("jdbc:oracle:thin:@//localhost:11521/orcl", JdbcConnectionProvider.oracleUrl("localhost", 11521, "orcl"))
    }

    @Test
    fun `a provider for Oracle manages a schema with Oracle's grammar, over the graph it names or the default one`() {
        val named = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, graphName = "ledger")
        val defaulted = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource)

        assertIs<OracleSchemaGrammar>(named.schemaGrammar)
        assertTrue(named.supportsSchemaManagement)
        assertEquals("ledger", named.graphName)
        assertEquals("drivine", defaulted.graphName)
        // The grammar writes for the graph the provider names: its statements make that graph first.
        assertContains(named.schemaGrammar.versionMarker("M", "scope").prepare.orEmpty(), "graph_name = 'LEDGER'")
        assertContains(defaulted.schemaGrammar.versionMarker("M", "scope").prepare.orEmpty(), "graph_name = 'DRIVINE'")
        assertSame(named.schemaGrammar, named.schemaGrammar)
    }

    @Test
    fun `a provider for Postgres manages the schema of the graph it names`() {
        val postgres = JdbcConnectionProvider("default", DatabaseType.POSTGRES, dataSource, graphName = "g")

        assertIs<PostgresSchemaGrammar>(postgres.schemaGrammar)
        assertTrue(postgres.supportsSchemaManagement)
    }

    @Test
    fun `a provider whose graph name cannot name tables manages no schema, says why, and gives no grammar`() {
        listOf(DatabaseType.ORACLE to "Oracle", DatabaseType.POSTGRES to "Postgres", DatabaseType.SQLITE to "SQLite").forEach { (type, engine) ->
            val provider = JdbcConnectionProvider("default", type, dataSource, graphName = "my-graph")

            val refusal = provider.schemaRefusal
            assertTrue(refusal.orEmpty().startsWith("'my-graph' cannot name a graph on $engine: it names tables"), refusal)
            assertFalse(provider.supportsSchemaManagement)
            assertEquals(refusal, assertFailsWith<IllegalArgumentException> { provider.schemaGrammar }.message)
        }
        assertNull(JdbcConnectionProvider("default", DatabaseType.POSTGRES, dataSource, graphName = "g").schemaRefusal)
        assertNull(JdbcConnectionProvider("default", DatabaseType.NEO4J, dataSource, graphName = "my-graph").schemaRefusal)
    }

    @Test
    fun `a provider for a store that keeps no graph over JDBC has none to manage a schema for`() {
        val other = JdbcConnectionProvider("default", DatabaseType.NEO4J, dataSource)

        assertIs<UnsupportedSchemaGrammar>(other.schemaGrammar)
        assertFalse(other.supportsSchemaManagement)
    }

    @Test
    fun `a graph name Oracle cannot take is refused when the grammar is first asked for, and names the graph`() {
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, graphName = "my-graph")

        val refused = assertFailsWith<IllegalArgumentException> { provider.schemaGrammar }
        assertContains(refused.message.orEmpty(), "'my-graph' cannot name a graph on Oracle")
    }

    @Test
    fun `each connection is a new one from the data source, which is the provider's native driver`() {
        // Connections whose statements run: tables that fail to be made would be tried again on the next connection.
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenAnswer { recording() } }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        val first = provider.connect()
        val second = provider.connect()

        assertIs<JdbcConnection>(first)
        assertNotSame(first, second)
        // And one before them, which the graph's tables are made on.
        verify(source, times(3)).connection
        assertSame(source, provider.nativeDriver)
    }

    // ----- The graph's tables, made before the first connection -----

    /** A connection whose statements run and return nothing, and which records them; an Oracle session it sets is set. */
    private fun recording(): java.sql.Connection = Mockito.mock(java.sql.Connection::class.java).also { connection ->
        `when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { Mockito.mock(PreparedStatement::class.java) }
        `when`(connection.createStatement()).thenAnswer { Mockito.mock(java.sql.Statement::class.java) }
    }

    private fun statementsOn(connection: java.sql.Connection): List<String> =
        Mockito.mockingDetails(connection).invocations.filter { it.method.name == "prepareStatement" }.map { it.getArgument(0) }

    @Test
    fun `the graph's tables are made once, on a connection of its own, before the first connection is handed out`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source, graphName = "ledger")

        repeat(3) { provider.connect().release() }

        // The statement as it is sent: trimmed of the blank its text begins with.
        assertEquals(listOf(OracleGraph("ledger").ensuring.trim()), statementsOn(ensuring))
        verify(ensuring).close()
        assertEquals(emptyList(), statementsOn(handedOut))
        verify(source, times(4)).connection
    }

    @Test
    fun `on the application's Oracle data source the session the tables are made on is set to make no adaptive plan first, as is the one handed out`() {
        val ensuringStatement = Mockito.mock(java.sql.Statement::class.java)
        val handedOutStatement = Mockito.mock(java.sql.Statement::class.java)
        val ensuring = recording().also { `when`(it.createStatement()).thenReturn(ensuringStatement) }
        val handedOut = recording().also { `when`(it.createStatement()).thenReturn(handedOutStatement) }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }

        JdbcConnectionProvider("default", DatabaseType.ORACLE, source).connect()

        val order = Mockito.inOrder(ensuringStatement, ensuring)
        order.verify(ensuringStatement).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
        order.verify(ensuring).prepareStatement(Mockito.anyString())
        verify(handedOutStatement, times(1)).execute(JdbcConnectionProvider.NO_ADAPTIVE_PLANS)
    }

    @Test
    fun `on SQLite the tables are looked for, and made only when they are not all there`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }

        JdbcConnectionProvider("default", DatabaseType.SQLITE, source, graphName = "g").connect()

        val graph = SqliteGraph("g")
        assertEquals(listOf(graph.made) + graph.ddl, statementsOn(ensuring))
        assertContains(graph.made, "'g_node', 'g_edge', 'g_schema', 'g_edge_src', 'g_edge_dst'")
    }

    @Test
    fun `the tables are made for the graph the provider names, on Postgres as on Oracle`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }

        JdbcConnectionProvider("default", DatabaseType.POSTGRES, source, graphName = "tenant_a").connect()

        assertEquals(listOf(PostgresGraph("tenant_a").ensuring.trim()), statementsOn(ensuring))
    }

    @Test
    fun `threads that first connect at once make the tables once`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            (1..8).map { pool.submit { start.await(); provider.connect().release() } }.also { start.countDown() }.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, statementsOn(ensuring).size)
        verify(source, times(9)).connection
    }

    /** The graph is not taken as made after a failure, so the provider's next connection makes the tables (#450). */
    @Test
    fun `tables that cannot be made are logged, the connection is handed out, and the next connection makes them`() {
        val refusing = Mockito.mock(java.sql.Connection::class.java).also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(SQLException("ORA-04021: timeout occurred while waiting to lock object", "61000", 4021))
            `when`(it.createStatement()).thenAnswer { Mockito.mock(java.sql.Statement::class.java) }
        }
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(refusing, handedOut, ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        assertIs<JdbcConnection>(provider.connect())
        // A failure that is not another session's is not run again within the connect.
        verify(refusing, times(1)).prepareStatement(Mockito.anyString())
        verify(refusing).close()

        provider.connect()
        provider.connect()

        assertEquals(listOf(OracleGraph(JdbcConnectionProvider.DEFAULT_GRAPH).ensuring.trim()), statementsOn(ensuring))
        verify(ensuring).close()
        assertEquals(emptyList(), statementsOn(handedOut))
        verify(source, times(5)).connection
    }

    /**
     * A user that may not make tables, or a store that may not have them made (a read-only database, a hot standby), is
     * refused alike on every connection: the block is not run again on each, and later connects cost a read of a flag.
     */
    @Test
    fun `tables the user may not make, or the store may not have made, are not tried again`() {
        val refusals = listOf(
            DatabaseType.ORACLE to SQLException("ORA-01031: insufficient privileges", "42000", 1031),
            DatabaseType.ORACLE to SQLException("ORA-16000: database or pluggable database open for read-only access", "72000", 16000),
            DatabaseType.POSTGRES to SQLException("ERROR: permission denied for schema public", "42501"),
            DatabaseType.POSTGRES to SQLException("ERROR: cannot execute CREATE TABLE in a read-only transaction", "25006"),
        )
        for ((type, refusal) in refusals) {
            val refusing = recording().also { `when`(it.prepareStatement(Mockito.anyString())).thenThrow(refusal) }
            val handedOut = recording()
            val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(refusing, handedOut) }
            val provider = JdbcConnectionProvider("default", type, source)

            repeat(3) { provider.connect().release() }

            verify(refusing, times(1)).prepareStatement(Mockito.anyString())
            assertEquals(emptyList(), statementsOn(handedOut), refusal.message)
            verify(source, times(4)).connection
        }
    }

    /** As when the DDL of one pool's first connection and other pools' first writes each wait on the other's lock (#450). */
    @Test
    fun `tables turned away by another session's on Oracle are made again on the same connection, and once made are not made again`() {
        val deadlock = SQLException("ORA-04020: deadlock detected while trying to lock object DRIVINE.DRIVINE_NODE", "61000", 4020)
        val ensuring = recording().also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(deadlock).thenAnswer { Mockito.mock(PreparedStatement::class.java) }
        }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        repeat(3) { provider.connect().release() }

        assertEquals(List(2) { OracleGraph(JdbcConnectionProvider.DEFAULT_GRAPH).ensuring.trim() }, statementsOn(ensuring))
        verify(ensuring).close()
        verify(source, times(4)).connection
    }

    /**
     * A busy lock (`ORA-00054`) the block has waited out for as long as it allows is run again by no retry around it:
     * neither the object manager's own making of the tables nor a save's. Run again, each would wait as long again.
     */
    @Test
    fun `a first connection whose block found a lock busy is run again by no retry around it`() {
        val busy = SQLException("ORA-00054: resource busy and acquire with NOWAIT specified or timeout expired", "61000", 54)
        val busyAlways = recording().also { `when`(it.prepareStatement(Mockito.anyString())).thenThrow(busy) }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(busyAlways) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        // The object manager's making: the provider's block once on its first connection, then the object manager's own once.
        val schema = org.drivine.schema.SchemaExecutor(provider)
        assertFailsWith<SQLException> { OracleGraph(JdbcConnectionProvider.DEFAULT_GRAPH).ensure { schema.query(it) } }
        assertEquals(2, statementsOn(busyAlways).size)

        // A save whose backend would run a busy lock again: the provider's block fails within the connect and is logged,
        // so the busy lock never reaches the save's retry; only the save's own statement, on the connection handed out, does.
        val missing = SQLException("ORA-00942: table or view does not exist", "42000", 942)
        val ensuring = recording().also { `when`(it.prepareStatement(Mockito.anyString())).thenThrow(busy) }
        val handedOut = recording().also { `when`(it.prepareStatement(Mockito.anyString())).thenThrow(missing) }
        val saving = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val pm = org.drivine.manager.NonTransactionalPersistenceManager(
            JdbcConnectionProvider("default", DatabaseType.ORACLE, saving), "default", DatabaseType.ORACLE, org.drivine.mapper.SubtypeRegistry(),
        )
        val backend = Mockito.mock(org.drivine.backend.GraphBackend::class.java).also {
            `when`(it.contended(Mockito.any() ?: busy)).thenAnswer { call -> (call.getArgument<Throwable>(0) as? SQLException)?.errorCode in setOf(54, 60) }
        }
        assertFails {
            org.drivine.manager.SaveExecutor(pm, backend).whenNotContended { pm.query(QuerySpecification.withStatement(sqlStatement("SELECT 1 FROM drivine_node"))) }
        }
        assertEquals(1, statementsOn(ensuring).size)
    }

    /**
     * An object manager makes the tables itself, through a retry of its own, on connections the provider makes them for
     * first (see [OracleGraph.ensured]): where every block deadlocks, the provider's runs once within each of the object
     * manager's attempts, and the attempts don't multiply.
     */
    @Test
    fun `a first connection asked for within a retry of making the tables runs its block once per attempt`() {
        val deadlocked = recording().also {
            `when`(it.prepareStatement(Mockito.anyString()))
                .thenThrow(SQLException("ORA-04020: deadlock detected while trying to lock object DRIVINE.DRIVINE_NODE", "61000", 4020))
        }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(deadlocked) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)
        val schema = org.drivine.schema.SchemaExecutor(provider)

        val thrown = assertFailsWith<SQLException> { OracleGraph(JdbcConnectionProvider.DEFAULT_GRAPH).ensure { schema.query(it) } }

        assertEquals(4020, thrown.errorCode)
        // Each attempt: the provider's block on its first connection, once, then the object manager's own.
        assertEquals(2 * org.drivine.backend.sql.DdlContention.ATTEMPTS, statementsOn(deadlocked).size)
        assertTrue(org.drivine.backend.sql.DdlContention.gaveUp(thrown))
    }

    @Test
    fun `tables turned away by a deadlock on Postgres are made again on the same connection`() {
        val deadlock = SQLException("ERROR: deadlock detected", "40P01")
        val ensuring = recording().also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(deadlock).thenAnswer { Mockito.mock(PreparedStatement::class.java) }
        }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source, graphName = "tenant_a")

        repeat(2) { provider.connect().release() }

        assertEquals(List(2) { PostgresGraph("tenant_a").ensuring.trim() }, statementsOn(ensuring))
        verify(source, times(3)).connection
    }

    /**
     * Threads that wait while the first makes the tables are each handed a connection whether it failed or not, and after
     * a failure the first of them makes the tables: the graph is never taken as made after a failure (#450).
     */
    @Test
    fun `threads waiting on a first connection whose tables failed are handed out, and the first of them makes the tables`() {
        val shared = recording().also {
            `when`(it.prepareStatement(Mockito.anyString()))
                .thenThrow(SQLException("ORA-04021: timeout occurred while waiting to lock object", "61000", 4021))
                .thenAnswer { Mockito.mock(PreparedStatement::class.java) }
        }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(shared) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            (1..8).map { pool.submit { start.await(); provider.connect().release() } }.also { start.countDown() }.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        provider.connect().release()

        // Once failed, once made; then a flag is read.
        assertEquals(2, statementsOn(shared).size)
        verify(source, times(11)).connection
    }

    @Test
    fun `a data source that gives no connection for the tables fails the connect, and the next connect makes them`() {
        val refused = SQLException("ORA-12541: no listener")
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenThrow(refused).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        assertSame(refused, assertFailsWith<SQLException> { provider.connect() })
        provider.connect()

        assertEquals(1, statementsOn(ensuring).size)
    }

    @Test
    fun `a connection the tables were made on that cannot be closed fails the connect, and the next connect makes them again`() {
        val broken = SQLException("closed by the network")
        val unclosable = recording().also { Mockito.doThrow(broken).`when`(it).close() }
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(unclosable, ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        assertSame(broken, assertFailsWith<SQLException> { provider.connect() })
        provider.connect()

        assertEquals(1, statementsOn(unclosable).size)
        assertEquals(1, statementsOn(ensuring).size)
        assertEquals(emptyList(), statementsOn(handedOut))
    }

    @Test
    fun `a graph name that cannot name tables leaves every connection working, as it did before the provider made tables, and is not tried again`() {
        listOf(DatabaseType.ORACLE, DatabaseType.POSTGRES, DatabaseType.SQLITE).forEach { type ->
            val handedOut = recording()
            val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(handedOut) }
            val provider = JdbcConnectionProvider("default", type, source, graphName = "my-graph")

            assertIs<JdbcConnection>(provider.connect())
            assertIs<JdbcConnection>(provider.connect())

            // No connection is taken for tables that cannot be named.
            verify(source, times(2)).connection
            assertEquals(emptyList(), statementsOn(handedOut))
        }
    }

    @Test
    fun `the tables are made committing each statement on a data source whose connections commit none, and the connection is put back as it was`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        `when`(ensuring.autoCommit).thenReturn(false)

        JdbcConnectionProvider("default", DatabaseType.POSTGRES, source).connect()

        val order = Mockito.inOrder(ensuring)
        order.verify(ensuring).autoCommit = true
        order.verify(ensuring).prepareStatement(Mockito.anyString())
        order.verify(ensuring).autoCommit = false
        order.verify(ensuring).close()
    }

    @Test
    fun `the tables are made on a connection that commits each statement already, which is left so`() {
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        `when`(ensuring.autoCommit).thenReturn(true)

        JdbcConnectionProvider("default", DatabaseType.POSTGRES, source).connect()

        verify(ensuring).prepareStatement(Mockito.anyString())
        verify(ensuring, never()).autoCommit = Mockito.anyBoolean()
    }

    @Test
    fun `a connection whose mode cannot be set for the tables fails the connect, is closed, and the next connect makes them`() {
        val broken = SQLException("closed by the network")
        val unsettable = recording().also { Mockito.doThrow(broken).`when`(it).autoCommit = true }
        val ensuring = recording().also { `when`(it.autoCommit).thenReturn(true) }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(unsettable, ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source)

        assertSame(broken, assertFailsWith<SQLException> { provider.connect() })
        verify(unsettable).close()
        assertEquals(emptyList(), statementsOn(unsettable))
        provider.connect()

        assertEquals(1, statementsOn(ensuring).size)
    }

    @Test
    fun `tables that cannot be made on a connection that commits none put it back as it was, and the next connection tries again`() {
        val refusing = recording().also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(SQLException("ERROR: canceling statement due to statement timeout", "57014"))
        }
        val ensuring = recording()
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(refusing, handedOut, ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source)

        assertIs<JdbcConnection>(provider.connect())
        provider.connect()

        val order = Mockito.inOrder(refusing)
        order.verify(refusing).autoCommit = true
        order.verify(refusing).prepareStatement(Mockito.anyString())
        order.verify(refusing).autoCommit = false
        order.verify(refusing).close()
        assertEquals(1, statementsOn(ensuring).size)
        verify(source, times(4)).connection
    }

    @Test
    fun `a connection that cannot be put back as it was after the tables fails the connect, is closed, and the next connect makes them`() {
        val broken = SQLException("closed by the network")
        val unrestorable = recording().also { Mockito.doThrow(broken).`when`(it).autoCommit = false }
        val ensuring = recording().also { `when`(it.autoCommit).thenReturn(true) }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(unrestorable, ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source)

        assertSame(broken, assertFailsWith<SQLException> { provider.connect() })
        verify(unrestorable).close()
        provider.connect()

        assertEquals(1, statementsOn(unrestorable).size)
        assertEquals(1, statementsOn(ensuring).size)
        assertEquals(emptyList(), statementsOn(handedOut))
    }

    @Test
    fun `a provider for a store that keeps no graph over JDBC makes no tables`() {
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(handedOut) }

        JdbcConnectionProvider("default", DatabaseType.NEO4J, source).connect()

        assertEquals(emptyList(), statementsOn(handedOut))
        verify(source, times(1)).connection
    }

    @Test
    fun `a data source that cannot give a connection fails the connect with its own error`() {
        val refused = SQLException("ORA-12541: no listener")
        val down = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenThrow(refused) }

        assertSame(refused, assertFailsWith<SQLException> { JdbcConnectionProvider("default", DatabaseType.ORACLE, down).connect() })
    }

    @Test
    fun `ending the provider closes a data source it made, and leaves the application's own open`() {
        val applications = ClosableDataSource(jdbc)
        val own = ClosableDataSource(jdbc)

        JdbcConnectionProvider("default", DatabaseType.ORACLE, applications).end()
        JdbcConnectionProvider("default", DatabaseType.ORACLE, own, ownsDataSource = true).end()

        assertFalse(applications.closed)
        assertTrue(own.closed)
        verify(jdbc, never()).close()
    }

    @Test
    fun `ending a provider that owns a data source which cannot be closed does nothing`() {
        JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource, ownsDataSource = true).end()

        Mockito.verifyNoInteractions(dataSource)
    }

    // ----- Connections whose pool gives them committing nothing of themselves (#395) -----

    /** A [recording] connection whose mode is as it was last set, from [committing]. */
    private fun moded(committing: Boolean): java.sql.Connection = recording().also { connection ->
        var mode = committing
        `when`(connection.autoCommit).thenAnswer { mode }
        Mockito.doAnswer { mode = it.getArgument(0); null }.`when`(connection).autoCommit = Mockito.anyBoolean()
    }

    /** A provider over [connections] in turn, for a graph whose name makes no tables, so no connection is taken for them. */
    private fun handingOut(vararg connections: java.sql.Connection): JdbcConnectionProvider {
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(connections.first(), *connections.drop(1).toTypedArray()) }
        return JdbcConnectionProvider("default", DatabaseType.POSTGRES, source, graphName = "my-graph")
    }

    private val insert = QuerySpecification.withStatement(sqlStatement("INSERT INTO t VALUES (1)"))

    @Test
    fun `a connection its pool gives committing nothing commits each statement run outside a transaction, and is given back as the pool gave it`() {
        val handedOut = moded(committing = false)

        handingOut(handedOut).connect().apply {
            query(insert)
            release()
        }

        val order = Mockito.inOrder(handedOut)
        order.verify(handedOut).autoCommit = true
        order.verify(handedOut).prepareStatement(Mockito.anyString())
        order.verify(handedOut).autoCommit = false
        order.verify(handedOut).close()
    }

    @Test
    fun `a connection that commits each statement already is handed out and given back as it is`() {
        val handedOut = moded(committing = true)

        handingOut(handedOut).connect().apply {
            query(insert)
            release()
        }

        verify(handedOut).prepareStatement(Mockito.anyString())
        verify(handedOut, never()).autoCommit = Mockito.anyBoolean()
        verify(handedOut).close()
    }

    @Test
    fun `a Drivine transaction on a connection its pool gives committing nothing is committed by Drivine, and the connection given back as the pool gave it`() {
        val handedOut = moded(committing = false)

        handingOut(handedOut).connect().apply {
            startTransaction()
            query(insert)
            commitTransaction()
            release()
        }

        val order = Mockito.inOrder(handedOut)
        order.verify(handedOut).autoCommit = true
        order.verify(handedOut).autoCommit = false
        order.verify(handedOut).prepareStatement(Mockito.anyString())
        order.verify(handedOut).commit()
        order.verify(handedOut).autoCommit = true
        order.verify(handedOut).autoCommit = false
        order.verify(handedOut).close()
    }

    @Test
    fun `a connection that refuses to commit each statement, as one in a distributed transaction does, is left to that transaction`() {
        val enlisted = recording().also {
            `when`(it.autoCommit).thenReturn(false)
            Mockito.doThrow(SQLException("Cannot set autocommit during a global transaction")).`when`(it).autoCommit = true
        }
        val provider = handingOut(enlisted, enlisted)

        repeat(2) {
            provider.connect().apply {
                query(insert)
                release()
            }
        }

        verify(enlisted, times(2)).prepareStatement(Mockito.anyString())
        verify(enlisted, never()).autoCommit = false
        verify(enlisted, never()).commit()
        verify(enlisted, times(2)).close()
    }

    @Test
    fun `a connection whose mode cannot be read fails the connect with its error, and is closed`() {
        val broken = SQLException("closed by the network")
        val unreadable = recording().also { `when`(it.autoCommit).thenThrow(broken) }

        assertSame(broken, assertFailsWith<SQLException> { handingOut(unreadable).connect() })
        verify(unreadable).close()
    }

    @Test
    fun `a connection that cannot be given back committing nothing is closed all the same`() {
        val handedOut = moded(committing = false).also { Mockito.doThrow(SQLException("closed by the network")).`when`(it).autoCommit = false }

        handingOut(handedOut).connect().apply {
            query(insert)
            release()
        }

        verify(handedOut).close()
    }

    /**
     * Spring's transaction-aware data source hands out the connection a Spring transaction on the thread holds. It is not
     * turned to commit each statement, nor are the tables made on it: either would commit the transaction partway through.
     * Once the transaction is over, the proxy hands out a connection of the pool's, which is.
     */
    @Test
    fun `a Spring transaction's connection from a transaction-aware data source is left to the transaction, and the tables are made after it`() {
        val held = moded(committing = false)
        val fresh = moded(committing = false)
        val ensuring = moded(committing = false)
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, fresh) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(source))

        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(source, ConnectionHolder(held))
        try {
            provider.connect().apply {
                query(insert)
                release()
            }
        } finally {
            TransactionSynchronizationManager.unbindResource(source)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }

        verify(held, never()).autoCommit = Mockito.anyBoolean()
        verify(held, never()).commit()
        verify(held, never()).close()
        assertEquals(listOf("INSERT INTO t VALUES (1)"), statementsOn(held))

        provider.connect().apply {
            query(insert)
            release()
        }
        assertEquals(1, statementsOn(ensuring).size)
        val order = Mockito.inOrder(fresh)
        order.verify(fresh).autoCommit = true
        order.verify(fresh).prepareStatement(Mockito.anyString())
        order.verify(fresh).autoCommit = false
        order.verify(fresh).close()
    }

    /** [inner] in a wrapper of another library's, as a statement logger wraps a data source's connections, which unwraps to it. */
    private fun wrapping(inner: java.sql.Connection): java.sql.Connection =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            when (method.name) {
                "isWrapperFor" -> (args[0] as Class<*>).isInstance(inner) || inner.isWrapperFor(args[0] as Class<*>)
                "unwrap" -> if ((args[0] as Class<*>).isInstance(inner)) inner else inner.unwrap(args[0] as Class<*>)
                else -> try {
                    method.invoke(inner, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.cause!!
                }
            }
        } as java.sql.Connection

    @Test
    fun `a Spring transaction's connection wrapped again by another data source is left to the transaction`() {
        val held = moded(committing = false)
        val source = Mockito.mock(DataSource::class.java)
        val aware = TransactionAwareDataSourceProxy(source)
        val wrapped = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenAnswer { wrapping(aware.connection) } }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, wrapped, graphName = "my-graph")

        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(source, ConnectionHolder(held))
        try {
            provider.connect().apply {
                query(insert)
                release()
            }
        } finally {
            TransactionSynchronizationManager.unbindResource(source)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }

        verify(held, never()).autoCommit = Mockito.anyBoolean()
        verify(held, never()).commit()
        verify(held, never()).close()
        assertEquals(listOf("INSERT INTO t VALUES (1)"), statementsOn(held))
    }

    // ----- The Oracle version, read on the first connection -----

    /** Metadata of an Oracle whose driver gives [major] and [minor], and [full] in its product version. */
    private fun oracle(major: Int, minor: Int, full: String): DatabaseMetaData = Mockito.mock(DatabaseMetaData::class.java).also {
        `when`(it.databaseMajorVersion).thenReturn(major)
        `when`(it.databaseMinorVersion).thenReturn(minor)
        `when`(it.databaseProductVersion).thenReturn("Oracle Database 23ai Free Release $full - Develop, Learn, and Run for Free\nVersion $full")
    }

    @Test
    fun `an Oracle older than 23_9 is refused with the version it is and why, and 23_9 or later is not`() {
        assertEquals(
            "Drivine needs Oracle 23.9 or later (it uses UUID()); this database is 23.8.0.25.04. " +
                "Upgrade, or use an Oracle Free image 23.9 or newer. Plain SQL over this provider still runs; Cypher does not.",
            JdbcConnectionProvider.oracleRefusal(oracle(23, 8, "23.8.0.25.04")),
        )
        assertContains(JdbcConnectionProvider.oracleRefusal(oracle(19, 0, "19.3.0.0.0")).orEmpty(), "this database is 19.3.0.0.0")
        assertNull(JdbcConnectionProvider.oracleRefusal(oracle(23, 9, "23.9.0.25.07")))
        // As the driver gives Oracle Free 23.26.3: minor version 26.
        assertNull(JdbcConnectionProvider.oracleRefusal(oracle(23, 26, "23.26.3.0.0")))
        assertNull(JdbcConnectionProvider.oracleRefusal(oracle(26, 1, "26.1.0.0.0")))
        // A product version with no number in it names the version by the driver's major and minor.
        val bare = oracle(21, 3, "").also { `when`(it.databaseProductVersion).thenReturn(null) }
        assertContains(JdbcConnectionProvider.oracleRefusal(bare).orEmpty(), "this database is 21.3.")
    }

    @Test
    fun `on an Oracle older than 23_9 a connection is handed out and runs SQL, and Cypher is refused with why, the version read once`() {
        val old = oracle(23, 8, "23.8.0.25.04")
        val ensuring = recording().also { `when`(it.metaData).thenReturn(old) }
        val handedOut = recording().also { `when`(it.metaData).thenReturn(old) }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        val connection = provider.connect()
        assertEquals(emptyList(), connection.query(QuerySpecification.withStatement(sqlStatement("SELECT 1 FROM dual"))))
        val refused = assertFailsWith<DrivineException> { connection.query(QuerySpecification.withStatement(cypherStatement("RETURN randomUUID()"))) }
        assertContains(refused.message.orEmpty(), "Drivine needs Oracle 23.9 or later (it uses UUID()); this database is 23.8.0.25.04.")
        assertFailsWith<DrivineException> { provider.connect().query(QuerySpecification.withStatement(cypherStatement("RETURN 1"))) }

        verify(ensuring, times(1)).metaData
        verify(handedOut, never()).metaData
    }

    @Test
    fun `an Oracle whose metadata cannot be read is not refused Cypher`() {
        val ensuring = recording().also { `when`(it.metaData).thenThrow(SQLException("closed")) }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(ensuring, handedOut) }

        JdbcConnectionProvider("default", DatabaseType.ORACLE, source).connect().query(QuerySpecification.withStatement(cypherStatement("RETURN 1")))

        verify(handedOut).prepareStatement(Mockito.anyString())
    }

    // ----- The builder -----

    private fun builder() = ConnectionProviderBuilder(DatabaseRegistry(DataSourceMap())).withType(DatabaseType.ORACLE)

    @Test
    fun `Oracle is not built without a host, a user and the service to connect to, each asked for by name`() {
        val noHost = assertFailsWith<IllegalArgumentException> { builder().userName("u").databaseName("FREEPDB1").register() }
        val noUser = assertFailsWith<IllegalArgumentException> { builder().host("localhost").databaseName("FREEPDB1").register() }
        val noService = assertFailsWith<IllegalArgumentException> { builder().host("localhost").userName("u").register() }

        assertEquals("Host config is required", noHost.message)
        assertEquals("Oracle requires a username", noUser.message)
        assertEquals("Oracle requires a databaseName: the service to connect to, such as FREEPDB1", noService.message)
    }

    @Test
    fun `Postgres is not built without a user and the database to connect to, each asked for by name`() {
        val postgres = { ConnectionProviderBuilder(DatabaseRegistry(DataSourceMap())).withType(DatabaseType.POSTGRES).host("localhost") }
        val noUser = assertFailsWith<IllegalArgumentException> { postgres().databaseName("drivine").register() }
        val noDatabase = assertFailsWith<IllegalArgumentException> { postgres().userName("u").register() }

        assertEquals("Postgres requires a username", noUser.message)
        assertEquals("Postgres requires a databaseName: the database to connect to", noDatabase.message)
    }

    @Test
    fun `a provider that is registered already is the one handed back, and nothing is built or asked for`() {
        val registry = DatabaseRegistry(DataSourceMap())
        val registered = JdbcConnectionProvider("ora", DatabaseType.ORACLE, dataSource)
        registry.register(registered)

        // Neither a host nor a user is given: an Oracle provider could not be built from this.
        assertSame(registered, ConnectionProviderBuilder(registry).withType(DatabaseType.ORACLE).register("ora"))
    }
}
