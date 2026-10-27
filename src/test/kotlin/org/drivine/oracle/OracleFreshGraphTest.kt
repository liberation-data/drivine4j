package org.drivine.oracle

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleStoredFunctions
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.SchemaVersionStore
import org.drivine.store.StoreIdentityResolver
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drivine's own nodes on a graph nothing has made yet: an application that enforces its schema, or
 * reads the store's identity, before it builds an object manager speaks to a graph with no tables.
 */
@OracleTests
class OracleFreshGraphTest {

    private val graph = "fresh"

    /** A manager for [graph], which is dropped first and made by nothing here. */
    private val manager = NonTransactionalPersistenceManager(
        JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = graph), graph, DatabaseType.ORACLE, SubtypeRegistry(),
    )

    @BeforeEach
    fun dropGraph() {
        // The provider makes the graph's tables, and its function, before its first connection: that one
        // is had first, so the graph is dropped after it, and what is asked here finds no tables, as it
        // would through a provider of the application's own.
        manager.query(QuerySpecification.withStatement("SELECT 1 FROM dual"))
        OracleTestDatabase.drop("PROPERTY GRAPH $graph", "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema", "FUNCTION ${graph}_nulls_equal")
    }

    @Test
    fun `the schema marker is read and kept on a graph nothing has made`() {
        val store = SchemaVersionStore(manager)
        assertNull(store.storedVersion())
        store.ensureSingleton()
        store.record("v1", listOf("a", "b"))
        assertEquals("v1", store.storedVersion())
        assertEquals(listOf("a", "b"), store.storedInventory())
    }

    /**
     * What a startup does for an owner whose catalog has no version: the marker's key is not
     * constrained first, so nothing indexes the scope the marker is found by.
     */
    @Test
    fun `an owner with no version records its marker, and records it again, on a graph nothing has made`() {
        val store = SchemaVersionStore(manager, "an-owner")
        assertNull(store.storedInventory())

        store.record(null, listOf("a"))
        assertNull(store.storedVersion())
        assertEquals(listOf("a"), store.storedInventory())

        store.record("v1", listOf("a", "b"))
        assertEquals("v1", store.storedVersion())
        assertEquals(listOf("a", "b"), store.storedInventory())
        assertNull(SchemaVersionStore(manager).storedInventory(), "another owner's marker is its own")
    }

    @Test
    fun `the graph is made with the function a Cypher statement compares values that may hold a null by`() {
        OracleGraph(graph).ensure(manager)
        val f = OracleGraph(graph).nullsEqual
        val compared = "NVL(TO_CHAR($f(JSON('[null]'), JSON('[1]'))), 'null') || ',' || TO_CHAR($f(JSON('[1, null]'), JSON('[2, null]'))) || ',' || TO_CHAR($f(JSON('{\"x\": [1]}'), JSON('{\"x\": [1]}')))"
        assertEquals(listOf("null,0,1"), manager.query(QuerySpecification.withStatement("SELECT $compared AS r FROM DUAL")))
    }

    @Test
    fun `a function of the graph's name that is not valid is made again`() {
        val f = OracleGraph(graph).nullsEqual
        OracleTestDatabase.run("BEGIN EXECUTE IMMEDIATE 'CREATE OR REPLACE FUNCTION $f RETURN NUMBER IS BEGIN RETURN nope; END;'; EXCEPTION WHEN OTHERS THEN NULL; END;")
        OracleGraph(graph).ensure(manager)
        assertEquals(listOf("0"), manager.query(QuerySpecification.withStatement("SELECT TO_CHAR($f(JSON('[1, null]'), JSON('[2, null]'))) AS r FROM DUAL")))
    }

    @Test
    fun `a user who may not make a function has the graph made without it, and a statement compiled to call none`() {
        // #270: making a function takes CREATE PROCEDURE, which this user lacks (ORA-01031).
        val user = "drivine_no_procedure"
        val password = OracleTestContainer.getConnectionPassword()
        fun asSystem(vararg statements: String) = DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", password).use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
        asSystem(
            "DROP USER IF EXISTS $user CASCADE",
            "CREATE USER $user IDENTIFIED BY \"$password\" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users",
            "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROPERTY GRAPH TO $user",
        )
        val provider = JdbcConnectionProvider.pooled("no-procedure", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(), user, password, graphName = graph)
        try {
            val restricted = NonTransactionalPersistenceManager(provider, graph, DatabaseType.ORACLE, SubtypeRegistry())
            OracleGraph(graph).ensure(restricted)
            fun query(cypher: String) = restricted.query(QuerySpecification.withStatement(CypherStatement(cypher)))
            assertEquals(listOf(listOf(false, "null")), query("CREATE (n:CyWrite) WITH n, [null] AS a, [1] AS b, [null] AS c RETURN [coalesce(a = b, 'null'), coalesce(a = c, 'null')]"))
            assertEquals(listOf(listOf("null", "null")), query("WITH [null] AS a, [1] AS b, [null] AS c RETURN [coalesce(a = b, 'null'), coalesce(a = c, 'null')]"))
        } finally {
            provider.end()
            asSystem("DROP USER IF EXISTS $user CASCADE")
        }
    }

    /** A connection of the test user's own, as a statement's session is, and [graph]'s function made by nothing. */
    private fun withSession(graph: String, block: (java.sql.Connection) -> Unit) {
        OracleTestDatabase.drop("FUNCTION ${OracleGraph(graph).nullsEqual}")
        val clock = OracleStoredFunctions.clock
        try {
            DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword()).use(block)
        } finally {
            OracleStoredFunctions.clock = clock
            OracleTestDatabase.drop("PROPERTY GRAPH $graph", "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema", "FUNCTION ${OracleGraph(graph).nullsEqual}")
        }
    }

    @Test
    fun `a session found to call no function is not asked again until a minute passes`() = withSession("fresh_absent") { connection ->
        // #270: a store without the function would otherwise pay a query and a second compile on every statement.
        val g = "fresh_absent"
        assertFalse(OracleStoredFunctions.nullsEqual(connection, g))
        connection.createStatement().use { it.execute(OracleGraph(g).functions.single()) }
        assertFalse(OracleStoredFunctions.nullsEqual(connection, g), "a session found to call none is remembered")
        val now = System.nanoTime()
        OracleStoredFunctions.clock = { now + OracleStoredFunctions.ABSENT_FOR.toNanos() + 1 }
        assertTrue(OracleStoredFunctions.nullsEqual(connection, g), "and asked again once the minute has passed")
    }

    @Test
    fun `a session found to call no function is asked again once the graph is ensured`() = withSession("fresh_ensured") { connection ->
        val g = "fresh_ensured"
        assertFalse(OracleStoredFunctions.nullsEqual(connection, g))
        val ensuring = NonTransactionalPersistenceManager(
            JdbcConnectionProvider(g, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = g), g, DatabaseType.ORACLE, SubtypeRegistry(),
        )
        OracleGraph(g).ensure(ensuring)
        assertTrue(OracleStoredFunctions.nullsEqual(connection, g))
    }

    @Test
    fun `a function that is not valid is taken as missing`() = withSession("fresh_invalid") { connection ->
        // Calling it gives ORA-06575.
        connection.createStatement().use {
            it.execute("CREATE OR REPLACE FUNCTION ${OracleGraph("fresh_invalid").nullsEqual}(a JSON, b JSON) RETURN NUMBER IS BEGIN RETURN nope; END;")
        }
        assertFalse(OracleStoredFunctions.nullsEqual(connection, "fresh_invalid"))
    }

    @Test
    fun `a function that fails for another reason than not being there is not taken as missing`() = withSession("fresh_failing") { connection ->
        // Only the errors of a function there is none of (ORA-00904, ORA-06575…) mean none: this one, like a connection lost, is reported.
        val g = "fresh_failing"
        connection.createStatement().use {
            it.execute("CREATE OR REPLACE FUNCTION ${OracleGraph(g).nullsEqual}(a JSON, b JSON) RETURN NUMBER IS BEGIN RAISE_APPLICATION_ERROR(-20001, 'failed'); END;")
        }
        assertEquals(20001, assertFailsWith<SQLException> { OracleStoredFunctions.nullsEqual(connection, g) }.errorCode)
    }

    @Test
    fun `sessions that make the function at once, while others call it, all succeed`() {
        // #270: two pools first ensuring a graph whose function is missing both run CREATE OR REPLACE FUNCTION.
        OracleGraph(graph).ensure(manager)
        val f = OracleGraph(graph).nullsEqual
        val ensuring = OracleGraph(graph).ensuring
        repeat(15) {
            OracleTestDatabase.drop("FUNCTION $f")
            val start = CountDownLatch(1)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val threads = (0 until 6).map { i ->
                thread {
                    try {
                        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword()).use { connection ->
                            start.await()
                            if (i % 2 == 0) {
                                connection.createStatement().use { it.execute(ensuring) }
                            } else repeat(20) {
                                try {
                                    connection.createStatement().use { s -> s.executeQuery("SELECT $f(JSON('[1, null]'), JSON('[2, null]')) FROM DUAL").use { r -> r.next() } }
                                } catch (e: SQLException) {
                                    if (e.errorCode !in setOf(904, 6575)) throw e
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
            start.countDown()
            threads.forEach(Thread::join)
            assertTrue(errors.isEmpty(), errors.joinToString())
            assertEquals(listOf("0"), manager.query(QuerySpecification.withStatement("SELECT TO_CHAR($f(JSON('[1, null]'), JSON('[2, null]'))) AS r FROM DUAL")))
        }
    }

    @Test
    fun `the store's identity is minted on a graph nothing has made`() {
        val identity = StoreIdentityResolver(manager).resolve()
        assertEquals(identity.id, StoreIdentityResolver(manager).resolve().id)
    }
}
