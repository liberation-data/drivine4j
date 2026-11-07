package org.drivine.connection

import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** A block of [PinnedConnections.pinned] runs every statement of its provider on one connection, and gives it back as it ends. */
class PinnedConnectionsTest {

    @Test
    fun `statements in a pinned block, and in a block within it, run on one connection, given back once at the end`() {
        val provider = CountingProvider()

        PinnedConnections.pinned(provider) {
            PinnedConnections.on(provider) { it.query(statement("one")) }
            PinnedConnections.pinned(provider) { PinnedConnections.on(provider) { it.query(statement("two")) } }
            PinnedConnections.on(provider) { it.query(statement("three")) }
            assertEquals(listOf("query:one", "query:two", "query:three"), provider.made.single().calls)
        }

        assertEquals(listOf("query:one", "query:two", "query:three", "release"), provider.made.single().calls)
        // Outside the block, each statement borrows a connection of its own again.
        PinnedConnections.on(provider) { it.query(statement("four")) }
        assertEquals(2, provider.made.size)
        assertEquals(listOf("query:four", "release"), provider.made.last().calls)
    }

    @Test
    fun `a block that refuses a command itself gives its connection back as sound, and the next block borrows another`() {
        val provider = CountingProvider()
        val failure = IllegalStateException("refused")

        val thrown = assertFailsWith<IllegalStateException> {
            PinnedConnections.pinned(provider) {
                PinnedConnections.on(provider) { it.query(statement("one")) }
                throw failure
            }
        }

        assertSame(failure, thrown)
        // No statement on it failed: it is not closed with an error, which the connection would log as a warning.
        assertEquals(listOf("query:one", "release"), provider.made.single().calls)
        PinnedConnections.pinned(provider) { PinnedConnections.on(provider) { it.query(statement("two")) } }
        assertEquals(listOf("query:two", "release"), provider.made.last().calls)
    }

    @Test
    fun `a statement that fails in a pinned block leaves the connection to the block, which gives it back`() {
        val provider = CountingProvider(failOn = "bad")

        assertFailsWith<IllegalStateException> {
            PinnedConnections.pinned(provider) { PinnedConnections.on(provider) { it.query(statement("bad")) } }
        }

        assertEquals(listOf("query:bad", "release:java.lang.IllegalStateException: bad failed"), provider.made.single().calls)
    }

    @Test
    fun `a statement that fails outside a pinned block gives its own connection back with the failure`() {
        val provider = CountingProvider(failOn = "bad")

        assertFailsWith<IllegalStateException> { PinnedConnections.on(provider) { it.query(statement("bad")) } }

        assertEquals(listOf("query:bad", "release:java.lang.IllegalStateException: bad failed"), provider.made.single().calls)
    }

    @Test
    fun `a provider that cannot connect fails the block before it runs, and leaves nothing pinned`() {
        val failure = IllegalStateException("no connection")
        val broken = object : ConnectionProvider by CountingProvider() {
            override fun connect(): Connection = throw failure
        }
        var ran = false

        assertSame(failure, assertFailsWith<IllegalStateException> { PinnedConnections.pinned(broken) { ran = true } })

        assertEquals(false, ran)
        val provider = CountingProvider()
        PinnedConnections.on(provider) { it.query(statement("after")) }
        assertEquals(listOf("query:after", "release"), provider.made.single().calls)
    }

    @Test
    fun `a connection is pinned for its provider alone, and on its thread alone`() {
        val pinned = CountingProvider()
        val other = CountingProvider()

        PinnedConnections.pinned(pinned) {
            PinnedConnections.on(other) { it.query(statement("other")) }
            thread { PinnedConnections.on(pinned) { it.query(statement("elsewhere")) } }.join()
        }

        assertEquals(listOf("query:other", "release"), other.made.single().calls)
        assertEquals(listOf(listOf("release"), listOf("query:elsewhere", "release")), pinned.made.map { it.calls }.sortedBy { it.size })
    }

    private fun statement(text: String) = QuerySpecification.withStatement(text)

    /** Makes a [RecordingConnection] for each connection asked for. */
    private class CountingProvider(private val failOn: String? = null) : ConnectionProvider {
        val made: MutableList<RecordingConnection> = java.util.Collections.synchronizedList(mutableListOf())
        override val name = "fake"
        override val type = DatabaseType.NEO4J
        override val subtypeRegistry: SubtypeRegistry? = null
        override fun connect(): Connection = RecordingConnection(failOn).also { made += it }
        override fun end() {}
    }

    /** Records what is asked of it. A query fails if it is [failOn]. */
    private class RecordingConnection(private val failOn: String?) : Connection {
        val calls = mutableListOf<String>()

        override fun sessionId() = "fake"

        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            val statement = spec.statement!!.text
            calls += "query:$statement"
            check(statement != failOn) { "$statement failed" }
            return emptyList()
        }

        override fun startTransaction() { calls += "start" }

        override fun commitTransaction() { calls += "commit" }

        override fun rollbackTransaction() { calls += "rollback" }

        override fun release(err: Throwable?) { calls += if (err == null) "release" else "release:$err" }
    }
}
