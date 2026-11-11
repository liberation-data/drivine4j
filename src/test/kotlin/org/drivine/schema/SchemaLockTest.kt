package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.store.StoreIdentityResolver
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SchemaLock] on Oracle (liberation-data/drivine4j#777): managers making one graph's schema at once take turns, so none
 * waits out the library-cache deadlock their DDL can meet together; a lock held past its wait fails with a message that
 * names the graph; and a graph with no lock table runs its DDL unlocked.
 */
@OracleTests
class SchemaLockTest {

    private fun open(graph: String): PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager(graph))

    private fun connect() = DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword())

    @Test
    fun `the lock is a row of the graph's lock table, waited for ten seconds`() {
        assertEquals("SELECT name FROM g_lock2 WHERE name = 'schema' FOR UPDATE WAIT 10", SchemaLock.statement("g"))
    }

    @Test
    fun `eight managers making a graph's schema at once take turns, so no round waits out a deadlock`() {
        val graph = "lock_race"
        val slowest = (1..20).map {
            open(graph).constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
            OracleTestDatabase.clear(OracleTestDatabase.graphManager(graph))
            val managers = List(8) { open(graph) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val t0 = System.nanoTime()
                val ids = managers.map { manager -> pool.submit<String> { start.await(); manager.storeIdentity.id } }
                    .also { start.countDown() }
                    .map { it.get(60, TimeUnit.SECONDS) }
                assertEquals(1, ids.toSet().size, "the managers agree on one identity")
                (System.nanoTime() - t0) / 1_000_000
            } finally {
                pool.shutdownNow()
            }
        }
        // Unlocked, about one round in twenty-five waited out a deadlock of about 9 s: each such wait is 9 s or more.
        assertTrue(slowest.all { it < 5_000 }, "each round's slowest manager, ms: $slowest")
    }

    @Test
    fun `a lock another session holds past the wait fails with a message that names the graph, and a boot behind it doesn't hang`() {
        val graph = "lock_held"
        open(graph).constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
        OracleTestDatabase.clear(OracleTestDatabase.graphManager(graph))
        val provider = JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph)
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("SELECT name FROM ${graph}_lock2 WHERE name = 'schema' FOR UPDATE").close() }
            try {
                val started = System.nanoTime()
                val failure = assertThrows<DrivineException> { SchemaLock.take(provider) }
                assertContains(failure.message!!, "making the schema of graph $graph")
                assertContains(failure.message!!, "past ${SchemaLock.WAIT_SECONDS} seconds")
                assertTrue((System.nanoTime() - started) / 1_000_000 >= SchemaLock.WAIT_SECONDS * 1_000L - 500)
                // A boot behind the held lock gives up on it after the wait, as its schema fails, rather than hang.
                val boot = System.nanoTime()
                runCatching { open(graph).storeIdentity.id }
                assertTrue((System.nanoTime() - boot) / 1_000_000 < (SchemaLock.WAIT_SECONDS + 20) * 1_000L)
            } finally {
                holder.rollback()
            }
        }
        // Freed, the lock is had at once.
        SchemaLock.take(provider)!!.close()
        provider.end()
    }

    @Test
    fun `a graph with no lock table, as one made before layout 2, makes its schema unlocked`() {
        val graph = "lock_none"
        open(graph).constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
        connect().use { c -> c.createStatement().use { it.execute("DROP TABLE ${graph}_lock2") } }
        try {
            OracleTestDatabase.clear(OracleTestDatabase.graphManager(graph))
            open(graph).storeIdentity.id
        } finally {
            // Made again with the graph, for the next run.
            OracleTestDatabase.graphManager(graph)
        }
    }
}
