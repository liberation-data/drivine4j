package org.drivine.connection

import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.schema.UnsupportedSchemaGrammar
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.PrintWriter
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
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

    private val jdbc = Mockito.mock(java.sql.Connection::class.java)
    private val dataSource = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(jdbc) }

    // ----- The provider -----

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
    fun `a provider for another JDBC store has no graph to manage a schema for`() {
        val postgres = JdbcConnectionProvider("default", DatabaseType.POSTGRES, dataSource)

        assertIs<UnsupportedSchemaGrammar>(postgres.schemaGrammar)
        assertFalse(postgres.supportsSchemaManagement)
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
        verify(dataSource, times(2)).connection
        assertSame(dataSource, provider.nativeDriver)
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
    fun `a provider that is registered already is the one handed back, and nothing is built or asked for`() {
        val registry = DatabaseRegistry(DataSourceMap())
        val registered = JdbcConnectionProvider("ora", DatabaseType.ORACLE, dataSource)
        registry.register(registered)

        // Neither a host nor a user is given: an Oracle provider could not be built from this.
        assertSame(registered, ConnectionProviderBuilder(registry).withType(DatabaseType.ORACLE).register("ora"))
    }
}
