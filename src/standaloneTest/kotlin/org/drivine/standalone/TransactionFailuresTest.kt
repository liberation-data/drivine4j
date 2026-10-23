package org.drivine.standalone

import org.drivine.Drivine
import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A connection that records what is asked of it, and fails where it is told to. */
private class FakeConnection(
    val failStart: Boolean = false,
    val failCommit: Boolean = false,
    val failRollback: Boolean = false,
) : Connection {
    val calls = mutableListOf<String>()

    override fun sessionId() = "fake"
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList()
    override fun startTransaction() = record("start", failStart)
    override fun commitTransaction() = record("commit", failCommit)
    override fun rollbackTransaction() = record("rollback", failRollback)
    override fun release(err: Throwable?) {
        calls += "release"
    }

    private fun record(call: String, fail: Boolean) {
        calls += call
        if (fail) error("$call fails")
    }
}

private class FakeProvider(
    private val connection: FakeConnection,
    override val name: String = "fake",
    private val failEnd: Boolean = false,
) : ConnectionProvider {
    override val type = DatabaseType.NEO4J
    override val subtypeRegistry: SubtypeRegistry? = null
    var ended = false
    override fun connect(): Connection = connection
    override fun end() {
        ended = true
        if (failEnd) error("end fails")
    }
}

/** What a block does when the database fails it: at the transaction's start, its commit, or its rollback. */
class TransactionFailuresTest {

    private fun drivine(connection: FakeConnection) =
        Drivine(DataSourceMap()).also { it.databaseRegistry.register(FakeProvider(connection)) }

    /** Opens the connection in the block's transaction, as a statement does. */
    private fun Drivine.touch() = transactions.current!!.getOrCreateConnection("fake", transactionContextHolder)

    @Test
    fun `a commit that fails is rolled back, released and thrown`() {
        val connection = FakeConnection(failCommit = true)
        val drivine = drivine(connection)

        val thrown = assertFailsWith<DrivineException> { drivine.transaction { drivine.touch() } }

        assertIs<IllegalStateException>(thrown.cause)
        assertEquals(listOf("start", "commit", "rollback", "release"), connection.calls)
        assertNull(drivine.transactions.current)
    }

    @Test
    fun `a rollback that fails does not hide the throw that caused it`() {
        val connection = FakeConnection(failRollback = true)
        val drivine = drivine(connection)
        val cause = IllegalArgumentException("the block fails")

        val thrown = assertFailsWith<IllegalArgumentException> {
            drivine.transaction {
                drivine.touch()
                throw cause
            }
        }

        assertSame(cause, thrown)
        assertTrue(thrown.suppressed.single() is DrivineException)
        assertEquals(listOf("start", "rollback", "release"), connection.calls)
        assertNull(drivine.transactions.current)
    }

    @Test
    fun `a connection whose transaction can't begin is released, and not kept`() {
        val connection = FakeConnection(failStart = true)
        val drivine = drivine(connection)

        drivine.transaction {
            assertFailsWith<IllegalStateException> { drivine.touch() }
            assertTrue(drivine.transactions.current!!.connections.isEmpty())
        }

        assertEquals(listOf("start", "release"), connection.calls)
    }

    @Test
    fun `close ends every database, though one fails to`() {
        val failing = FakeProvider(FakeConnection(), name = "first", failEnd = true)
        val second = FakeProvider(FakeConnection(), name = "second")
        val drivine = Drivine(DataSourceMap()).also {
            it.databaseRegistry.register(failing)
            it.databaseRegistry.register(second)
        }

        assertFailsWith<IllegalStateException> { drivine.close() }

        assertTrue(failing.ended)
        assertTrue(second.ended)
    }
}
