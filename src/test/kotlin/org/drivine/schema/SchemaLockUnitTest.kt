package org.drivine.schema

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SchemaLock] without a store (liberation-data/drivine4j#777): which errors of the lock's statement leave the DDL to run
 * unlocked, which are thrown, that the lock's connection is given back on every path, and that the warning of a lock not
 * had is given once for each graph; and that a plan takes the lock within its provider's gate, frees both on every path,
 * and queues at the gate while another plan of the provider holds it. SchemaLockTest drives it on Oracle.
 */
class SchemaLockUnitTest {

    @Test
    fun `the lock is a row of the graph's lock table, waited for ten seconds`() {
        assertEquals("SELECT name FROM g_lock2 WHERE name = 'schema' FOR UPDATE WAIT 10", SchemaLock.statement("g"))
    }

    @Test
    fun `a store other than Oracle, or one with no graph, takes no lock and borrows no connection`() {
        val postgres = FakeProvider("g", type = DatabaseType.POSTGRES)
        val graphless = FakeProvider(null)

        assertNull(SchemaLock.take(postgres))
        assertNull(SchemaLock.take(graphless))
        assertEquals(0, postgres.made.size + graphless.made.size)
    }

    @Test
    fun `a provider on Oracle has one gate, its own, and one off Oracle or with no graph has none`() {
        val one = FakeProvider("gate_one")
        val other = FakeProvider("gate_one")

        assertSame(SchemaLock.gate(one), SchemaLock.gate(one))
        assertNotNull(SchemaLock.gate(one))
        assertTrue(SchemaLock.gate(one) !== SchemaLock.gate(other), "a gate is a pool's, not a graph name's")
        assertNull(SchemaLock.gate(FakeProvider("g", type = DatabaseType.POSTGRES)))
        assertNull(SchemaLock.gate(FakeProvider(null)))
    }

    @Test
    fun `plans asking for a provider's gate at once, as replicas' first plans do, are given the one gate`() {
        val threads = 16
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            repeat(300) { round ->
                val provider = FakeProvider("gate_race_$round")
                val start = java.util.concurrent.CyclicBarrier(threads)
                val gates = List(threads) { pool.submit<Any?> { start.await(); SchemaLock.gate(provider) } }
                    .map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, gates.map { System.identityHashCode(it) }.toSet().size, "round $round: one gate for the provider")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the lock had holds its connection in a transaction until closed, which rolls back and gives it back`() {
        val provider = FakeProvider("held_ok")

        val lock = assertNotNull(SchemaLock.take(provider))
        assertEquals(listOf("start", "query:${SchemaLock.statement("held_ok")}"), provider.made.single().calls)
        lock.close()
        assertEquals(listOf("start", "query:${SchemaLock.statement("held_ok")}", "rollback", "release"), provider.made.single().calls)
    }

    @Test
    fun `a rollback that fails as the lock closes gives the connection back with its error and throws nothing`() {
        val provider = FakeProvider("held_dead", failRollback = true)

        SchemaLock.take(provider)!!.close()

        assertEquals("release:java.lang.IllegalStateException: session ended", provider.made.single().calls.last())
    }

    @Test
    fun `a connection that joins a transaction Drivine didn't begin takes no lock, so its end marks nothing of the application's to roll back`() {
        // A Spring or JTA transaction's connection: a lock taken on it is the application's transaction's, which the DDL
        // on its session commits, and the lock's rollback would mark it to roll back at its end (#724).
        val jdbc = org.mockito.Mockito.mock(java.sql.Connection::class.java)
        val joined = org.drivine.connection.JdbcConnection(jdbc, org.drivine.mapper.JdbcResultMapper()).also { it.joined = true }
        val provider = object : ConnectionProvider {
            override val name = "joined"
            override val type = DatabaseType.ORACLE
            override val graphName = "joined"
            override val subtypeRegistry: SubtypeRegistry? = null
            override fun connect(): Connection = joined
            override fun end() {}
        }

        val warned = warnings { assertNull(SchemaLock.take(provider)) }

        assertEquals(emptyList(), warned, "nothing is marked, nor warned of")
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).prepareStatement(org.mockito.ArgumentMatchers.anyString())
        org.mockito.Mockito.verify(jdbc).close()
    }

    @Test
    fun `a graph with no lock table runs its DDL unlocked, without a warning`() {
        val provider = FakeProvider("no_table", fails = DrivineException("wrapped", SQLException("ORA-00942: table or view does not exist", "42000", 942)))

        val warned = warnings { assertNull(SchemaLock.take(provider)) }

        assertEquals(emptyList(), warned)
        assertReleased(provider, withError = false)
    }

    @Test
    fun `a lock not had within its wait, on Oracle 23 or earlier, runs the DDL unlocked and warns once for each graph`() {
        val expired23 = SQLException("ORA-00054: resource busy and acquire with NOWAIT specified or timeout expired", "61000", 54)
        val expiredEarlier = SQLException("ORA-30006: resource busy; acquire with WAIT timeout expired", "61000", 30006)
        val first = FakeProvider("wait_one", fails = DrivineException("wrapped", expired23))
        val second = FakeProvider("wait_two", fails = expiredEarlier)

        val warned = warnings {
            repeat(3) { assertNull(SchemaLock.take(first)) }
            assertNull(SchemaLock.take(second))
        }

        assertEquals(2, warned.size, "once for each graph: $warned")
        assertTrue("schema lock of graph wait_one" in warned[0] && Regex("in \\d+ ms").containsMatchIn(warned[0]), warned[0])
        assertTrue("schema lock of graph wait_two" in warned[1], warned[1])
        first.made.forEach { assertEquals(listOf("rollback", "release"), it.calls.takeLast(2), "given back without the error, whose release would warn: ${it.calls}") }
        assertReleased(second, withError = false)
    }

    @Test
    fun `any other error of the lock's statement is thrown, its connection given back with it`() {
        val denied = SQLException("ORA-01031: insufficient privileges", "42000", 1031)
        val provider = FakeProvider("denied", fails = denied)

        assertSame(denied, assertFailsWith<SQLException> { SchemaLock.take(provider) })
        assertReleased(provider, withError = true)
    }

    @Test
    fun `a plan's DDL runs with the gate and the lock held, and frees the lock and then the gate however the plan ends`() {
        val denied = SQLException("ORA-01031: insufficient privileges", "42000", 1031)
        val cases = listOf<Pair<String, (String) -> Exception?>>(
            "made" to { _ -> null },
            "the DDL failed" to { statement -> if (statement.trimStart().startsWith("DECLARE")) RuntimeException("refused") else null },
            "the lock's statement failed" to { statement -> if ("FOR UPDATE" in statement) denied else null },
            "the lock wasn't had" to { statement -> if ("FOR UPDATE" in statement) SQLException("ORA-30006: resource busy", "61000", 30006) else null },
        )
        for ((case, fails) in cases) {
            val provider = PlanProvider("gate_$case".replace(' ', '_').replace("'", ""), fails)
            val gate = assertNotNull(SchemaLock.gate(provider))
            val driver = SchemaPlanDriver(SchemaExecutor(provider), provider.schemaGrammar, { null })

            runCatching { driver.item(org.drivine.cypher.store.plan.ItemCall.ENSURE, RangeIndexSpec("Gated", "k")) }

            val ddl = provider.ran.filter { it.statement.trimStart().startsWith("DECLARE") }
            assertTrue(ddl.isNotEmpty() || case == "the lock's statement failed", "$case: ${provider.ran}")
            ddl.forEach { assertTrue(it.gated, "$case: the DDL ran inside the gate") }
            val locks = provider.ran.filter { "FOR UPDATE" in it.statement }
            assertEquals(1, locks.size, "$case: the lock is taken once, before the first DDL: ${provider.ran}")
            assertTrue(locks.single().gated, "$case: the lock is taken inside the gate")
            assertEquals(provider.connected, provider.releasedCount, "$case: every connection is given back")
            assertEquals(listOf(true), provider.lockReleased, "$case: the lock's is given back before the gate is freed")
            assertTrue(!gate.isLocked, "$case: the gate is freed")
        }
    }

    @Test
    fun `a plan that runs no DDL, as a drop of what isn't there, takes neither the gate nor the lock`() {
        val provider = PlanProvider("gate_none") { _ -> null }
        val driver = SchemaPlanDriver(SchemaExecutor(provider), provider.schemaGrammar, { null })

        driver.item(org.drivine.cypher.store.plan.ItemCall.DROP, RangeIndexSpec("Absent", "k"))

        assertTrue(provider.ran.isNotEmpty(), "the plan read the catalog")
        assertTrue(provider.ran.none { "FOR UPDATE" in it.statement }, "no lock is taken: ${provider.ran}")
        assertTrue(provider.ran.none { it.gated }, "nothing ran inside the gate: ${provider.ran}")
        assertTrue(!SchemaLock.gate(provider)!!.isLocked)
        assertEquals(provider.connected, provider.releasedCount, "every connection is given back")
    }

    @Test
    fun `a plan of a provider waits at its gate, holding no connection for its lock, while another plan of it holds it`() {
        val inDdl = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val provider = PlanProvider("gate_queue") { statement ->
            if (statement.trimStart().startsWith("DECLARE") && inDdl.count > 0) {
                inDdl.countDown()
                finish.await()
            }
            null
        }
        val gate = assertNotNull(SchemaLock.gate(provider))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val plan = { SchemaPlanDriver(SchemaExecutor(provider), provider.schemaGrammar, { null }).item(org.drivine.cypher.store.plan.ItemCall.ENSURE, RangeIndexSpec("Queued", "k")) }
            val first = pool.submit(plan)
            assertTrue(inDdl.await(10, java.util.concurrent.TimeUnit.SECONDS), "the first plan runs its DDL")
            val second = pool.submit(plan)
            val deadline = System.nanoTime() + 10_000_000_000
            while (!gate.hasQueuedThreads() && System.nanoTime() < deadline) Thread.onSpinWait()
            assertTrue(gate.hasQueuedThreads(), "the second plan waits at the gate")
            assertEquals(1, provider.ran.count { "FOR UPDATE" in it.statement }, "and has taken no lock: ${provider.ran}")
            finish.countDown()
            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
            second.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2, provider.ran.count { "FOR UPDATE" in it.statement }, "each took the lock in turn")
            assertTrue(!gate.isLocked)
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * [provider]'s one connection was rolled back and given back, with the error where [withError]: only an error that is
     * thrown, as a release with one warns (JdbcConnection), and a lock not had warns once for each graph, a missing table never.
     */
    private fun assertReleased(provider: FakeProvider, withError: Boolean) {
        val calls = provider.made.single().calls
        assertEquals("rollback", calls[calls.size - 2], "$calls")
        if (withError) assertTrue(calls.last().startsWith("release:"), "$calls") else assertEquals("release", calls.last(), "$calls")
    }

    @Test
    fun `a pool of one, whose one connection the DDL needs, takes no lock, borrows nothing for it, and warns once`() {
        // A pool HikariCP makes only as it's first asked for a connection, so one asked for none never connects.
        val pool = com.zaxxer.hikari.HikariDataSource().apply { jdbcUrl = "jdbc:oracle:thin:@//nowhere.invalid:1521/none"; maximumPoolSize = 1 }
        val provider = org.drivine.connection.JdbcConnectionProvider("one", DatabaseType.ORACLE, pool, graphName = "pool_one")

        val warned = warnings { repeat(2) { assertNull(SchemaLock.take(provider)) } }

        assertEquals(1, warned.size, "once for the graph: $warned")
        assertTrue("schema lock of graph pool_one" in warned.single() && "pool holds 1 connection" in warned.single(), warned.single())
        assertTrue(pool.hikariPoolMXBean == null, "the pool was never started, so no connection was asked for")
    }

    @Test
    fun `a pool that gives the lock no connection within its timeout leaves the DDL unlocked, and warns once`() {
        val timedOut = DrivineException("wrapped", java.sql.SQLTransientConnectionException("Connection is not available, request timed out after 30000ms"))
        val provider = FakeProvider("pool_timeout", connectFails = timedOut)

        val warned = warnings { repeat(2) { assertNull(SchemaLock.take(provider)) } }

        assertEquals(1, warned.size, "once for the graph: $warned")
        assertTrue("schema lock of graph pool_timeout" in warned.single() && Regex("in \\d+ ms").containsMatchIn(warned.single()), warned.single())
    }

    @Test
    fun `a HikariCP pool of two that times out giving the lock a connection leaves the DDL unlocked, and warns once`() {
        // JdbcConnectionProvider.connect throws HikariCP's own SQLTransientConnectionException, unwrapped.
        val pool = com.zaxxer.hikari.HikariDataSource().apply {
            jdbcUrl = "jdbc:oracle:thin:@//nowhere.invalid:1521/none"
            maximumPoolSize = 2
            connectionTimeout = 250
            initializationFailTimeout = -1
        }
        val provider = org.drivine.connection.JdbcConnectionProvider("two", DatabaseType.ORACLE, pool, graphName = "pool_two_timeout")
        try {
            val warned = warnings { repeat(2) { assertNull(SchemaLock.take(provider)) } }

            assertEquals(1, warned.size, "once for the graph: $warned")
            assertTrue("schema lock of graph pool_two_timeout" in warned.single() && "gave no connection" in warned.single(), warned.single())
        } finally {
            pool.close()
        }
    }

    @Test
    fun `a connection the lock can't have for another reason is thrown`() {
        val refused = DrivineException("wrapped", SQLException("ORA-01017: invalid username/password; logon denied", "72000", 1017))
        val provider = FakeProvider("connect_refused", connectFails = refused)

        assertSame(refused, assertFailsWith<DrivineException> { SchemaLock.take(provider) })
    }

    /** The warnings [SchemaLock] logs while [block] runs. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SchemaLock::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    /** Makes a [FakeConnection] for each connection asked for, of an Oracle graph [graphName] unless [type] says else. */
    private class FakeProvider(
        override val graphName: String?,
        override val type: DatabaseType = DatabaseType.ORACLE,
        private val fails: Exception? = null,
        private val failRollback: Boolean = false,
        /** What [connect] throws, as a pool that gives no connection within its timeout does; null where it gives one. */
        private val connectFails: Exception? = null,
    ) : ConnectionProvider {
        val made = mutableListOf<FakeConnection>()
        override val name = "fake"
        override val subtypeRegistry: SubtypeRegistry? = null
        override fun connect(): Connection = connectFails?.let { throw it } ?: FakeConnection(fails, failRollback).also { made += it }
        override fun end() {}
    }

    /**
     * An Oracle graph [graphName]'s provider for a plan: each statement on any of its connections is kept in [ran], with
     * whether its thread held the provider's gate, and answered with no rows, or thrown what [fails] gives for it.
     * [lockReleased] keeps, for each lock's connection given back, whether its thread still held the gate.
     */
    private class PlanProvider(override val graphName: String, private val fails: (String) -> Exception?) : ConnectionProvider {
        class Ran(val statement: String, val gated: Boolean) {
            override fun toString() = "$statement${if (gated) " (gated)" else ""}"
        }

        val ran: MutableList<Ran> = java.util.Collections.synchronizedList(mutableListOf())
        val lockReleased: MutableList<Boolean> = java.util.Collections.synchronizedList(mutableListOf())
        var connected = 0
        var releasedCount = 0
        override val name = "plan"
        override val type = DatabaseType.ORACLE
        override val subtypeRegistry: SubtypeRegistry? = null
        override val schemaGrammar: org.drivine.schema.SchemaGrammar =
            org.drivine.backend.oracle.OracleSchemaGrammar(org.drivine.backend.oracle.OracleGraph(graphName))

        private fun gated() = SchemaLock.gate(this)!!.isHeldByCurrentThread

        override fun connect(): Connection {
            synchronized(this) { connected++ }
            return object : Connection {
                override fun sessionId() = "plan"

                override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
                    val text = spec.statement!!.text
                    ran += Ran(text, gated())
                    if ("FOR UPDATE" in text) locking = true
                    fails(text)?.let { throw it }
                    return emptyList()
                }

                override fun startTransaction() = Unit
                override fun commitTransaction() = Unit
                override fun rollbackTransaction() = Unit
                override fun release(err: Throwable?) {
                    synchronized(this@PlanProvider) { releasedCount++ }
                    if (locking) lockReleased += gated()
                }

                private var locking = false
            }
        }

        override fun end() {}
    }

    /** Records what is asked of it. A query throws [fails], where given; a rollback fails where [failRollback]. */
    private class FakeConnection(private val fails: Exception?, private val failRollback: Boolean) : Connection {
        val calls = mutableListOf<String>()

        override fun sessionId() = "fake"

        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            calls += "query:${spec.statement!!.text}"
            if (fails != null) throw fails
            return emptyList()
        }

        override fun startTransaction() { calls += "start" }

        override fun commitTransaction() { calls += "commit" }

        override fun rollbackTransaction() {
            calls += "rollback"
            check(!failRollback) { "session ended" }
        }

        override fun release(err: Throwable?) { calls += if (err == null) "release" else "release:$err" }
    }
}
