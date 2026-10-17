package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.atomic.Bookmark
import sample.atomic.ChunkHash
import sample.atomic.Ledger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a `saveAll` of fragments of more than one class sends: one statement where it can. */
class SaveAllTogetherTest {

    private val store = RecordingStore(DatabaseType.NEO4J)
    private val stateless = StatelessGraphObjectManager(store, Neo4jObjectMapper.instance, SubtypeRegistry())

    @Test
    fun `fragments of three classes are saved by one statement that writes each class`() {
        stateless.saveAll(listOf(ChunkHash("h1", "one"), Bookmark("b1", "c-1"), Ledger("l1", 1), ChunkHash("h2", "two")))

        val statement = store.queried.single()
        listOf("MERGE (n:ChunkHash", "MERGE (n:Ledger", "MERGE (n:Bookmark").forEach { assertContains(statement.statement, it) }
        assertEquals(setOf("rows0", "rows1", "rows2"), statement.parameters.keys)
        assertEquals(2, (statement.parameters.getValue("rows0") as List<*>).size)
    }

    @Test
    fun `fragments of one class are saved by the statement they always were`() {
        stateless.saveAll(listOf(Bookmark("b1", "c-1"), Bookmark("b2", "c-2")))

        val statement = store.queried.single()
        assertTrue(statement.statement.startsWith("UNWIND \$rows AS row"))
        assertEquals(setOf("rows"), statement.parameters.keys)
    }

    @Test
    fun `fragments of two classes with more rows than one statement takes are saved by a statement for each class and chunk`() {
        val hashes = (0..BatchSaveOperations.UNWIND_CHUNK_SIZE).map { ChunkHash("h$it", "c$it") }

        stateless.saveAll(hashes + Bookmark("b1", "c-1"))

        assertEquals(3, store.queried.size)
    }

    @Test
    fun `fragments of two classes as many rows as one statement takes, counted across the classes, are one statement`() {
        val hashes = (1 until BatchSaveOperations.UNWIND_CHUNK_SIZE).map { ChunkHash("h$it", "c$it") }

        stateless.saveAll(hashes + Bookmark("b1", "c-1"))

        assertEquals(1, store.queried.size)
    }

    @Test
    fun `fragments of two classes each fewer than one statement takes, but more together, are a statement for each class`() {
        val half = BatchSaveOperations.UNWIND_CHUNK_SIZE / 2
        val hashes = (0..half).map { ChunkHash("h$it", "c$it") }
        val ledgers = (0..half).map { Ledger("l$it", it) }

        stateless.saveAll(hashes + ledgers)

        assertEquals(listOf(listOf("rows"), listOf("rows")), store.queried.map { it.parameters.keys.toList() })
    }
}
