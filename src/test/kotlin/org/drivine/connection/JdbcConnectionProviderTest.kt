package org.drivine.connection

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.schema.UnsupportedSchemaGrammar
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.PrintWriter
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, dataSource)

        val first = provider.connect()
        val second = provider.connect()

        assertIs<JdbcConnection>(first)
        assertNotSame(first, second)
        // And one before them, which the graph's tables are made on.
        verify(dataSource, times(3)).connection
        assertSame(dataSource, provider.nativeDriver)
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

    @Test
    fun `tables that cannot be made are logged and not tried again, and the connection is handed out`() {
        val handedOut = recording()
        val refusing = Mockito.mock(java.sql.Connection::class.java).also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(SQLException("ORA-01031: insufficient privileges"))
            `when`(it.createStatement()).thenAnswer { Mockito.mock(java.sql.Statement::class.java) }
        }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(refusing, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.ORACLE, source)

        assertIs<JdbcConnection>(provider.connect())
        provider.connect()

        verify(refusing, times(1)).prepareStatement(Mockito.anyString())
        verify(refusing).close()
        verify(source, times(3)).connection
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
    fun `tables that cannot be made on a connection that commits none put it back as it was, and are not tried again`() {
        val refusing = recording().also {
            `when`(it.prepareStatement(Mockito.anyString())).thenThrow(SQLException("permission denied for schema public"))
        }
        val handedOut = recording()
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(refusing, handedOut) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source)

        assertIs<JdbcConnection>(provider.connect())
        provider.connect()

        val order = Mockito.inOrder(refusing)
        order.verify(refusing).autoCommit = true
        order.verify(refusing).prepareStatement(Mockito.anyString())
        order.verify(refusing).autoCommit = false
        order.verify(refusing).close()
        verify(source, times(3)).connection
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
