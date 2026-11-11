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
 * had is given once for each graph. SchemaLockTest drives it on Oracle.
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
    fun `a graph with no lock table runs its DDL unlocked, without a warning`() {
        val provider = FakeProvider("no_table", fails = DrivineException("wrapped", SQLException("ORA-00942: table or view does not exist", "42000", 942)))

        val warned = warnings { assertNull(SchemaLock.take(provider)) }

        assertEquals(emptyList(), warned)
        assertReleasedWithError(provider)
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
        first.made.forEach { assertTrue(it.calls.last().startsWith("release:"), "${it.calls}") }
        assertReleasedWithError(second)
    }

    @Test
    fun `any other error of the lock's statement is thrown, its connection given back with it`() {
        val denied = SQLException("ORA-01031: insufficient privileges", "42000", 1031)
        val provider = FakeProvider("denied", fails = denied)

        assertSame(denied, assertFailsWith<SQLException> { SchemaLock.take(provider) })
        assertReleasedWithError(provider)
    }

    private fun assertReleasedWithError(provider: FakeProvider) {
        val calls = provider.made.single().calls
        assertEquals("rollback", calls[calls.size - 2], "$calls")
        assertTrue(calls.last().startsWith("release:"), "$calls")
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
    ) : ConnectionProvider {
        val made = mutableListOf<FakeConnection>()
        override val name = "fake"
        override val subtypeRegistry: SubtypeRegistry? = null
        override fun connect(): Connection = FakeConnection(fails, failRollback).also { made += it }
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
