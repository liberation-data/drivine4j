package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.NonAtomicBatchException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.DrivineTransactionObject
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    // ----- A store that cannot roll back -----

    @Test
    fun `on a store that cannot roll back, a batch of one statement runs with no transaction`() {
        val connection = RecordingConnection(onStart = { throw UnsupportedOperationException("refused") })

        val rows = managerOver(connection, atomicBatches = false).queryBatch(listOf(statement("RETURN 1")))

        assertEquals(listOf(listOf<Any?>("RETURN 1")), rows)
        assertEquals(listOf("query:RETURN 1", "release"), connection.calls)
    }

    @Test
    fun `on a store that cannot roll back and refuses such a batch, a batch of more is refused before a connection is taken`() {
        val connection = RecordingConnection()
        val provider = RecordingProvider(connection, atomicBatches = false, refusesNonAtomicBatches = true)

        val thrown = assertFailsWith<NonAtomicBatchException> {
            managerOver(provider).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))
        }

        assertEquals(2, thrown.statements)
        assertEquals(0, provider.connections)
        assertEquals(emptyList(), connection.calls)
    }

    @Test
    fun `on a store that cannot roll back and refuses such a batch, a batch of one runs`() {
        val connection = RecordingConnection()

        managerOver(RecordingProvider(connection, atomicBatches = false, refusesNonAtomicBatches = true))
            .queryBatch(listOf(statement("RETURN 1")))

        assertEquals(listOf("query:RETURN 1", "release"), connection.calls)
    }

    @Test
    fun `on a store that cannot roll back and does not say it refuses, a refused transaction is passed on as it was`() {
        val refusal = UnsupportedOperationException("refused")
        val connection = RecordingConnection(onStart = { throw refusal })

        val thrown = assertFailsWith<UnsupportedOperationException> {
            managerOver(connection, atomicBatches = false).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))
        }

        assertSame(refusal, thrown)
    }

    @Test
    fun `on a store that cannot roll back and runs a transaction through, a batch of more runs statement by statement`() {
        val connection = RecordingConnection()

        managerOver(connection, atomicBatches = false).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))

        assertEquals(listOf("start", "query:RETURN 1", "query:RETURN 2", "commit", "release"), connection.calls)
    }

    @Test
    fun `a store that rolls back keeps the refusal of a transaction as it was`() {
        val connection = RecordingConnection(onStart = { throw UnsupportedOperationException("refused") })

        val thrown = assertFailsWith<UnsupportedOperationException> {
            managerOver(connection).queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))
        }

        assertFalse(thrown is NonAtomicBatchException)
    }

    private fun statement(cypher: String) = QuerySpecification.withStatement(cypher)

    private fun managerOver(connection: RecordingConnection, atomicBatches: Boolean = true) =
        managerOver(RecordingProvider(connection, atomicBatches))

    private fun managerOver(provider: RecordingProvider) =
        NonTransactionalPersistenceManager(provider, "fake", DatabaseType.NEO4J, SubtypeRegistry())
}

/**
 * A batch on a [TransactionalPersistenceManager] runs on the caller's transaction, and says whether
 * the store can undo it as the provider that transaction connects through says.
 */
class TransactionalBatchTest {

    @Test
    fun `a transactional manager says a store cannot roll back as its provider says, with no schema source`() {
        assertFalse(transactionalOver(RecordingConnection(), atomicBatches = false).manager.atomicBatches)
        assertTrue(transactionalOver(RecordingConnection(), atomicBatches = true).manager.atomicBatches)
    }

    @Test
    fun `in a transaction on a store that cannot roll back, a batch of more runs statement by statement and leaves the commit to the caller`() {
        val connection = RecordingConnection()
        val (manager, holder) = transactionalOver(connection, atomicBatches = false)
        holder.currentTransaction = DrivineTransactionObject()

        val rows = manager.queryBatch(listOf(statement("RETURN 1"), statement("RETURN 2")))

        assertEquals(listOf(listOf<Any?>("RETURN 1"), listOf<Any?>("RETURN 2")), rows)
        assertEquals(listOf("start", "query:RETURN 1", "query:RETURN 2"), connection.calls)
    }

    @Test
    fun `in a transaction on a store that refuses one, a batch of one is refused as the store refuses it, before it runs`() {
        val refusal = UnsupportedOperationException("refused")
        val connection = RecordingConnection(onStart = { throw refusal })
        val (manager, holder) = transactionalOver(connection, atomicBatches = false)
        holder.currentTransaction = DrivineTransactionObject()

        val thrown = assertFailsWith<UnsupportedOperationException> { manager.queryBatch(listOf(statement("RETURN 1"))) }

        assertSame(refusal, thrown)
        assertEquals(listOf("start"), connection.calls)
    }

    private data class Transactional(val manager: TransactionalPersistenceManager, val holder: TransactionContextHolder)

    private fun statement(cypher: String) = QuerySpecification.withStatement(cypher)

    private fun transactionalOver(connection: RecordingConnection, atomicBatches: Boolean): Transactional {
        val registry = DatabaseRegistry(DataSourceMap(mutableMapOf()))
        registry.register(RecordingProvider(connection, atomicBatches))
        val holder = TransactionContextHolder(registry)
        val manager = TransactionalPersistenceManager(holder, "fake", DatabaseType.NEO4J, SubtypeRegistry(), CypherDialect.NEO4J_5.grammar())
        return Transactional(manager, holder)
    }
}

/** Hands out one [connection], counting how often it is asked. */
private class RecordingProvider(
    private val connection: RecordingConnection,
    override val atomicBatches: Boolean = true,
    override val refusesNonAtomicBatches: Boolean = false,
) : ConnectionProvider {
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
