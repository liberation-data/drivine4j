package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A batch on a [NonTransactionalPersistenceManager] takes a connection of its own, and gives it back
 * however the batch ends — also when the transaction cannot be started, as on FalkorDB in `STRICT`
 * mode.
 */
class NonTransactionalBatchTest {

    @Test
    fun `a batch whose transaction cannot be started releases its connection`() {
        val refusal = UnsupportedOperationException("no multi-statement transactions")
        val connection = RecordingConnection(onStart = { throw refusal })

        val thrown = assertFailsWith<UnsupportedOperationException> {
            managerOver(connection).queryBatch(listOf(statement("RETURN 1")))
        }

        assertSame(refusal, thrown)
        assertEquals(listOf("start", "release:$refusal"), connection.calls)
    }

    @Test
    fun `executeBatch releases its connection when the transaction cannot be started`() {
        val connection = RecordingConnection(onStart = { throw UnsupportedOperationException("refused") })

        assertFailsWith<UnsupportedOperationException> { managerOver(connection).executeBatch(listOf(statement("RETURN 1"))) }

        assertEquals("release:java.lang.UnsupportedOperationException: refused", connection.calls.last())
        assertEquals(1, connection.calls.count { it.startsWith("release") })
    }

    @Test
    fun `a batch commits once after its last statement and releases its connection`() {
        val connection = RecordingConnection()

        val rows = managerOver(connection).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))

        assertEquals(listOf(listOf<Any?>("RETURN 1"), listOf<Any?>("RETURN 2")), rows)
        assertEquals(listOf("start", "query:RETURN 1", "query:RETURN 2", "commit", "release"), connection.calls)
    }

    @Test
    fun `a batch that fails midway rolls back, runs nothing after the failure and releases its connection`() {
        val connection = RecordingConnection(failOn = "RETURN 2")

        val thrown = assertFailsWith<DrivineException> {
            managerOver(connection).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2"), statement("RETURN 3")))
        }

        assertIs<IllegalStateException>(thrown.rootCause)
        assertEquals(listOf("start", "query:RETURN 1", "query:RETURN 2", "rollback", "release:$thrown"), connection.calls)
    }

    @Test
    fun `an empty batch takes no connection`() {
        val provider = RecordingProvider(RecordingConnection())
        val manager = NonTransactionalPersistenceManager(provider, "fake", DatabaseType.NEO4J, SubtypeRegistry())

        assertEquals(emptyList(), manager.queryBatch(emptyList()))
        manager.executeBatch(emptyList())

        assertEquals(0, provider.connections)
    }

    private fun statement(cypher: String) = QuerySpecification.withStatement(cypher)

    private fun managerOver(connection: RecordingConnection) =
        NonTransactionalPersistenceManager(RecordingProvider(connection), "fake", DatabaseType.NEO4J, SubtypeRegistry())
}

/** Hands out one [connection], counting how often it is asked. */
private class RecordingProvider(private val connection: RecordingConnection) : ConnectionProvider {
    var connections = 0
    override val name = "fake"
    override val type = DatabaseType.NEO4J
    override val subtypeRegistry: SubtypeRegistry? = null
    override fun connect(): Connection = connection.also { connections++ }
    override fun end() {}
}

/** Records what is asked of it. A query returns its own statement, or fails if it is [failOn]. */
private class RecordingConnection(
    private val onStart: () -> Unit = {},
    private val failOn: String? = null,
) : Connection {
    val calls = mutableListOf<String>()

    override fun sessionId() = "fake"

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val statement = spec.statement!!.text
        calls += "query:$statement"
        check(statement != failOn) { "$statement failed" }
        return listOf(statement) as List<T>
    }

    override fun startTransaction() {
        calls += "start"
        onStart()
    }

    override fun commitTransaction() { calls += "commit" }

    override fun rollbackTransaction() { calls += "rollback" }

    override fun release(err: Throwable?) { calls += if (err == null) "release" else "release:$err" }
}
