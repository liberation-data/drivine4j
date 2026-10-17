package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.atomic.Bookmark
import sample.atomic.ChunkHash
import sample.atomic.Keyed
import sample.atomic.Ledger
import sample.atomic.Tagged
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

    @Test
    fun `an unstamped fragment an UNWIND cannot write is saved by a statement of its own, and the other classes are still one`() {
        stateless.saveAll(listOf(ChunkHash("h1", "one"), Tagged("t1", mapOf("a" to 1)), Ledger("l1", 1), Bookmark("b1", "c-1")))

        val (tagged, together) = store.queried
        assertContains(tagged.statement, "MERGE (n:Tagged")
        listOf("MERGE (n:ChunkHash", "MERGE (n:Ledger", "MERGE (n:Bookmark").forEach { assertContains(together.statement, it) }
        assertEquals(setOf("rows0", "rows1", "rows2"), together.parameters.keys)
        assertEquals(2, store.queried.size)
    }

    @Test
    fun `the classes of one statement are written in the order of their names, unstamped before stamped, whatever order they are given in`() {
        stateless.saveAll(listOf(Ledger("l1", 1), Bookmark("b1", "c-1"), ChunkHash("h1", "one")))
        stateless.saveAll(listOf(ChunkHash("h2", "two"), Bookmark("b2", "c-2"), Ledger("l2", 2)))

        val orders = store.queried.map { statement ->
            listOf("ChunkHash", "Ledger", "Bookmark").sortedBy { statement.statement.indexOf("MERGE (n:$it") }
        }
        assertEquals(listOf(listOf("ChunkHash", "Ledger", "Bookmark"), listOf("ChunkHash", "Ledger", "Bookmark")), orders)
    }

    @Test
    fun `the rows of each class of one statement go in the order of their ids`() {
        stateless.saveAll(listOf(ChunkHash("h2", "two"), Ledger("l1", 1), ChunkHash("h1", "one"), ChunkHash("h3", "three")))

        val rows = store.queried.single().parameters.getValue("rows0") as List<*>
        assertEquals(listOf("h1", "h2", "h3"), rows.map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun `a fragment whose id is stored under a name of its own is merged on that name in one statement`() {
        stateless.saveAll(listOf(Keyed("k1", "one"), ChunkHash("h1", "one")))

        assertContains(store.queried.single().statement, "MERGE (n:Keyed {keyed_id: row.id})")
    }
}
