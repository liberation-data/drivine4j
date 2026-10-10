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
import kotlin.test.assertSame

/**
 * A batch on a [NonTransactionalPersistenceManager] whose commit fails reports it as it reports a
 * failing statement: as a [DrivineException] that carries the engine's error, which is what a caller
 * that runs a contended batch again looks for.
 */
class BatchCommitFailureTest {

    @Test
    fun `a commit that fails is reported as a DrivineException, rolled back and released once`() {
        val refusal = IllegalStateException("conflicting transactions")
        val connection = CommitFailingConnection(refusal)

        val thrown = assertFailsWith<DrivineException> { managerOver(connection).queryBatch(listOf(statement("RETURN 1"))) }

        assertSame(refusal, thrown.rootCause)
        assertSame(refusal, thrown.cause)
        assertEquals(listOf("start", "query:RETURN 1", "commit", "rollback", "release:$thrown"), connection.calls)
    }

    @Test
    fun `executeBatch reports a commit that fails the same way`() {
        val refusal = IllegalStateException("conflicting transactions")
        val connection = CommitFailingConnection(refusal)

        val thrown = assertFailsWith<DrivineException> { managerOver(connection).executeBatch(listOf(statement("RETURN 1"))) }

        assertSame(refusal, thrown.rootCause)
        assertEquals(1, connection.calls.count { it.startsWith("release") })
    }

    private fun statement(cypher: String) = QuerySpecification.withStatement(cypher)

    private fun managerOver(connection: Connection) = NonTransactionalPersistenceManager(
        object : ConnectionProvider {
            override val name = "fake"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry: SubtypeRegistry? = null
            override fun connect(): Connection = connection
            override fun end() {}
        },
        "fake", DatabaseType.NEO4J, SubtypeRegistry(),
    )
}

/** Records what is asked of it, and fails its commit with [refusal]. */
private class CommitFailingConnection(private val refusal: Exception) : Connection {
    val calls = mutableListOf<String>()

    override fun sessionId() = "fake"

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val statement = spec.statement!!.text
        calls += "query:$statement"
        return listOf(statement) as List<T>
    }

    override fun startTransaction() { calls += "start" }

    override fun commitTransaction() {
        calls += "commit"
        throw refusal
    }

    override fun rollbackTransaction() { calls += "rollback" }

    override fun release(err: Throwable?) { calls += if (err == null) "release" else "release:$err" }
}
