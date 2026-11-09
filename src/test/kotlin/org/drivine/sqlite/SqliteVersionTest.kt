package org.drivine.sqlite

import org.drivine.DrivineException
import org.drivine.backend.sqlite.SqliteVersion
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.drivine.schema.SchemaVersionStore
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compiled Cypher needs SQLite 3.53.2 (#565): an older one, which the application's sqlite-jdbc may be, refuses Cypher alone. */
class SqliteVersionTest {

    private fun refused(version: String) =
        "Compiled Cypher needs SQLite 3.53.2 or later; this is SQLite $version. " +
            "Upgrade sqlite-jdbc (org.xerial:sqlite-jdbc 3.53.2.0 or later). Plain SQL over this provider still runs; Cypher does not."

    /**
     * Connections of the test JVM's SQLite that answer `sqlite_version()` with [version], as an older sqlite-jdbc's would:
     * every other statement runs on the real one. [reads] counts the times the version is asked for. A null [version]
     * cannot be read: asking for it fails.
     */
    private class OlderSqlite(private val version: String?, val reads: AtomicInteger = AtomicInteger()) : DataSource {
        private val real = SqliteTestDatabase.provider.nativeDriver

        override fun getConnection(): java.sql.Connection {
            val connection = real.connection
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                val given = args ?: emptyArray()
                if (method.name == "prepareStatement" && given.firstOrNull() == SqliteVersion.QUERY) {
                    reads.incrementAndGet()
                    if (version == null) throw java.sql.SQLException("The version cannot be read.")
                    given[0] = "select '$version'"
                }
                try {
                    method.invoke(connection, *given)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as java.sql.Connection
        }

        override fun getConnection(username: String?, password: String?): java.sql.Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
        override fun <T : Any?> unwrap(iface: Class<T>): T = throw java.sql.SQLException("Not a wrapper.")
        override fun isWrapperFor(iface: Class<*>): Boolean = false
    }

    private fun manager(source: DataSource, graph: String) =
        NonTransactionalPersistenceManager(JdbcConnectionProvider(graph, DatabaseType.SQLITE, source, graphName = graph), graph, DatabaseType.SQLITE, SubtypeRegistry())

    private fun cypher(pm: NonTransactionalPersistenceManager, statement: String): List<Any> =
        pm.query(QuerySpecification.withStatement(CypherStatement(statement)))

    @Test
    fun `a SQLite older than 3_53_2 refuses compiled Cypher, saying which and what to upgrade, and 3_53_2 or later refuses nothing`() {
        // 3.53.0 and 3.53.1, as sqlite-jdbc 3.53.0.0 and 3.53.1.0 bring them, still write a float to 15 digits.
        listOf("3.45.0", "3.46.1", "3.47.0", "3.49.1", "3.52.9", "2.8.17", "3.53.0", "3.53.1", "3.53", "3.46.1-rc1").forEach {
            assertEquals(refused(it), SqliteVersion.refusal(it), it)
        }
        listOf("3.53.2", "3.53.2.1", "3.53.2-beta", "3.53.4", "3.54.0", "3.54.1", "3.100.0", "4.0.0", " 3.53.2\n").forEach { assertNull(SqliteVersion.refusal(it), it) }
        // A version that is no version refuses nothing: the statement says what is wrong as it runs.
        listOf("", "3", "unknown", "3.x.1").forEach { assertNull(SqliteVersion.refusal(it), it) }
    }

    @Test
    fun `on SQLite 3_46_1 a Cypher statement is refused before it is compiled, and the graph's tables and plain SQL still work`() {
        val source = OlderSqlite("3.46.1")
        val pm = manager(source, "oldsqlite")
        SqliteTestDatabase.drop("oldsqlite")

        // `last()` and a negative index compile to the `-> -1` 3.46.1 refuses (#565): refused with why, not with SQLite's error.
        listOf("RETURN last([1, 2, 3]) AS v", "RETURN [1, 2, 3][-1] AS v", "CREATE (:Old {name: 'Ada'})", "MATCH (n) RETURN count(n)").forEach {
            val refusal = assertFailsWith<DrivineException> { cypher(pm, it) }
            assertEquals(refused("3.46.1"), refusal.message, it)
        }
        // The connection is handed out, its graph's tables made, and plain SQL runs over them.
        assertEquals(listOf(0L), pm.query(QuerySpecification.withStatement("SELECT count(*) FROM oldsqlite_node")))
        assertEquals(listOf(3L), pm.query(QuerySpecification.withStatement("SELECT json_extract('[1,2,3]', '\$[#-1]')")))
        // Nothing of the refused write was run.
        assertEquals(listOf(0L), pm.query(QuerySpecification.withStatement("SELECT count(*) FROM oldsqlite_node")))
        // The version is read once for the provider, not on each connection or statement.
        assertEquals(1, source.reads.get())
    }

    @Test
    fun `on SQLite 3_46_1 the schema manager, schema DDL and the object managers, which write SQL of their own, still work`() {
        val source = OlderSqlite("3.46.1")
        SqliteTestDatabase.drop("oldschema")
        val provider = JdbcConnectionProvider("oldschema", DatabaseType.SQLITE, source, graphName = "oldschema")
        val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
        val pm = factory.get("oldschema", PersistenceManagerType.NON_TRANSACTIONAL)
        val app = RangeIndexSpec("Claim", "text")
        val schema = SchemaManager(factory, registry, listOf(SchemaCatalog.of(app).named("app")))

        schema.enforce()
        schema.ensure("runtime", RangeIndexSpec("Claim", "id"))
        // A remove reads the other owners' markers, which a Cypher read would have been refused for.
        schema.remove("runtime", RangeIndexSpec("Claim", "id"))
        assertNull(pm.indexes.find(RangeIndexSpec("Claim", "id")))
        assertEquals(listOf(app.inventoryKey), SchemaVersionStore(pm, "app").storedInventory())
        // A manager not given the app's catalog, as another process's, finds the app's marker in that read, and leaves its item.
        SchemaManager(factory, registry, emptyList()).run {
            ensure("lib", app)
            remove("lib", app)
        }
        assertNotNull(pm.indexes.find(app))
        // Schema DDL, which Drivine runs itself, and a DROP of it, which is a remove.
        pm.execute(QuerySpecification.withStatement(CypherStatement("CREATE INDEX claim_kind IF NOT EXISTS FOR (c:Claim) ON (c.kind)")))
        pm.execute(QuerySpecification.withStatement(CypherStatement("DROP INDEX claim_kind")))
        assertNull(pm.indexes.find(RangeIndexSpec("Claim", "kind")))

        val stateless = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        stateless.save(Claim("old-1", "kept"))
        assertEquals("kept", stateless.load("old-1", Claim::class.java)?.text)
        // Cypher of the application's own is still refused.
        assertEquals(refused("3.46.1"), assertFailsWith<DrivineException> { cypher(pm as NonTransactionalPersistenceManager, "MATCH (n) RETURN n") }.message)
    }

    @Test
    fun `a connection made on its own reads its SQLite's version itself, and refuses Cypher on an older one`() {
        OlderSqlite("3.49.1").connection.let { jdbc ->
            val connection = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.SQLITE)
            try {
                val refusal = assertFailsWith<DrivineException> { connection.query(QuerySpecification.withStatement(CypherStatement("RETURN 1 AS one"))) }
                assertEquals(refused("3.49.1"), refusal.message)
                assertEquals(listOf(1L), connection.query(QuerySpecification.withStatement("SELECT 1")))
            } finally {
                connection.release()
            }
        }
    }

    @Test
    fun `where the version cannot be read, Cypher runs, and the version is read again on the next statement`() {
        val source = OlderSqlite(null)
        val pm = manager(source, "unreadsqlite")
        SqliteTestDatabase.drop("unreadsqlite")
        assertEquals(listOf(1L), cypher(pm, "RETURN 1 AS one"))
        assertEquals(listOf(2L), cypher(pm, "RETURN 2 AS two"))
        // Not kept: a version that could not be read once, as on a connection that failed, is read on the next.
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `on the SQLite the tests run, 3_53_2 or later, compiled Cypher runs as before, last() and a negative index with it`() {
        val version = SqliteTestDatabase.pm.query(QuerySpecification.withStatement("SELECT sqlite_version()").transform(String::class.java)).single()
        val (major, minor, patch) = version.split('.').map(String::toInt)
        assertTrue(major > 3 || (major == 3 && (minor > 53 || (minor == 53 && patch >= 2))), version)
        assertNull(SqliteVersion.refusal(version))

        val source = OlderSqlite(version)
        val pm = manager(source, "newsqlite")
        SqliteTestDatabase.drop("newsqlite")
        assertEquals(listOf(listOf(3L, 3L, 2L)), cypher(pm, "RETURN last([1, 2, 3]) AS a, [1, 2, 3][-1] AS b, [1, 2, 3][-2] AS c"))
        cypher(pm, "CREATE (:New {name: 'Ada'})")
        assertEquals(listOf("Ada"), cypher(pm, "MATCH (n:New) RETURN n.name"))
        assertEquals(1, source.reads.get())
    }

    @Test
    fun `on the SQLite the tests run, the other owners' scopes read as SQL are those the Cypher read gave`() {
        SqliteTestDatabase.drop("scopesqlite")
        val pm = SqliteTestDatabase.graphManager("scopesqlite")
        val store = SchemaVersionStore(pm, "a")
        // No marker: nothing, as the Cypher read gives on a graph with no node.
        assertEquals(emptyList(), store.otherScopes())
        // Several owners, one with two markers, one of a node of two labels, and one with no scope; nodes of another label are not markers.
        cypher(
            pm,
            "CREATE (:`_DrivineSchema` {scope: 'a'}), (:`_DrivineSchema` {scope: 'b'}), (:`_DrivineSchema` {scope: 'b'}), " +
                "(:Other:`_DrivineSchema` {scope: 'c'}), (:`_DrivineSchema` {version: '1'}), (:`_DrivineSchemaX` {scope: 'd'}), (:Other {scope: 'e'})"
        )
        val cypher = pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (m:`_DrivineSchema`) RETURN DISTINCT m.scope")).transform(String::class.java))
        assertEquals(setOf("b", "c", null), store.otherScopes().toSet<String?>())
        assertEquals(cypher.filter { it != "a" }.toSet(), store.otherScopes().toSet())
        assertEquals(cypher.size - 1, store.otherScopes().size)
    }
}
