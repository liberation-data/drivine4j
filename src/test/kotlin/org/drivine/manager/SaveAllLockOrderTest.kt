package org.drivine.manager

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sql.WritePlan
import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.atomic.Bookmark
import sample.atomic.ChunkHash
import sample.atomic.Ledger
import sample.atomic.StampedTagged
import sample.atomic.Tagged
import kotlin.test.assertEquals

/**
 * The order a `saveAll` writes its fragments in, whatever statements it takes: the classes in the
 * order of their names, and the fragments of each in the order of their ids, so that two batches over
 * the same nodes take their locks in the same order. The objects still come back in the order given.
 */
class SaveAllLockOrderTest {

    private val chunk = BatchSaveOperations.UNWIND_CHUNK_SIZE

    /** The stamp the store leaves on the node with the id. */
    private fun stampOf(id: Any?): String = "%016x:%016x".format(id.hashCode(), id.hashCode())

    /** A Neo4j store that answers a stamped `UNWIND` with a row for each of its rows, each handed the stamp of its id. */
    private fun neo4j() = RecordingStore(DatabaseType.NEO4J) { ran ->
        ran.parameters.filterKeys { it.startsWith("rows") }.values.flatMap { rows -> rows as List<*> }
            .map { it as Map<*, *> }
            .filter { it.containsKey("i") }
            .map { "${it["i"]}/=${stampOf(it["id"])}" }
    }

    private fun managerOver(store: RecordingStore) = StatelessGraphObjectManager(store, Neo4jObjectMapper.instance, SubtypeRegistry())

    @Suppress("UNCHECKED_CAST")
    private fun RecordingStore.Ran.rows(name: String = "rows"): List<Map<String, Any?>> = parameters.getValue(name) as List<Map<String, Any?>>

    @Test
    fun `the rows of one unstamped class go in the order of their ids, whatever order they are given in`() {
        val store = neo4j()
        managerOver(store).saveAll(listOf(Ledger("l2", 2), Ledger("l3", 3), Ledger("l1", 1)))

        assertEquals(listOf("l1", "l2", "l3"), store.queried.single().rows().map { it["id"] })
    }

    @Test
    fun `the rows of one stamped class go in the order of their ids, each under the index it was given`() {
        val store = neo4j()
        managerOver(store).saveAll(listOf(Bookmark("b2", "c-2"), Bookmark("b3", "c-3"), Bookmark("b1", "c-1")))

        val rows = store.queried.single().rows()
        assertEquals(listOf("b1", "b2", "b3"), rows.map { it["id"] })
        assertEquals(listOf("2", "0", "1"), rows.map { it["i"] })
    }

    @Test
    fun `the rows are put in order before they are split into statements, so each statement holds the next ids`() {
        val ids = (0..chunk).map { "l%04d".format(it) }
        val store = neo4j()
        managerOver(store).saveAll(ids.reversed().map { Ledger(it, 1) })

        assertEquals(listOf(ids.take(chunk), ids.drop(chunk)), store.queried.map { ran -> ran.rows().map { it["id"] } })
    }

    @Test
    fun `classes too many for one statement go in the order of their names, unstamped before stamped, each class's rows in the order of their ids`() {
        val hashes = (0..chunk).map { ChunkHash("h%04d".format(it), "c$it") }
        val store = neo4j()
        managerOver(store).saveAll(hashes.reversed() + listOf(Bookmark("b2", "c-2"), Bookmark("b1", "c-1")))

        val written = store.queried.map { ran -> ran.statement.substringAfter("MERGE (n:").substringBefore(" ") to ran.rows().map { it["id"] } }
        assertEquals(
            listOf("ChunkHash" to hashes.take(chunk).map { it.id }, "ChunkHash" to listOf(hashes.last().id), "Bookmark" to listOf("b1", "b2")),
            written,
        )
    }

    @Test
    fun `classes an UNWIND cannot write take their locks first whether the rest fit in one statement or not`() {
        /** The labels of the nodes the statements merge, each run of one label as one. */
        fun RecordingStore.labels(): List<String> = queried
            .flatMap { ran -> Regex("""MERGE \(\w+:(\w+)""").findAll(ran.statement).map { it.groupValues[1] }.toList() }
            .fold(emptyList()) { runs, label -> if (runs.lastOrNull() == label) runs else runs + label }

        val few = neo4j()
        managerOver(few).saveAll(listOf(Ledger("l1", 1), ChunkHash("h1", "c"), Tagged("t1")))
        val many = neo4j()
        managerOver(many).saveAll(listOf(Ledger("l1", 1), Tagged("t1")) + (0..chunk).map { ChunkHash("h%04d".format(it), "c") })

        assertEquals(listOf("Tagged", "ChunkHash", "Ledger"), few.labels())
        assertEquals(few.labels(), many.labels())
    }

    @Test
    fun `fragments an UNWIND cannot write go in the order of their ids, the unstamped first and the stamped last`() {
        val store = neo4j()
        // The store hands no stamp back for a fragment saved alone, so the call fails once all is sent.
        runCatching {
            managerOver(store).saveAll(listOf(StampedTagged("s2"), Tagged("t2"), Ledger("l1", 1), StampedTagged("s1"), Tagged("t1"), Bookmark("b1", "c-1")))
        }

        val written = store.queried.map { ran ->
            val labels = Regex("""MERGE \(\w+:(\w+)""").findAll(ran.statement).map { it.groupValues[1] }.joinToString("+")
            labels to (ran.parameters["id"] ?: ran.parameters["p0_id"])
        }
        assertEquals(
            listOf("Tagged" to "t1", "Tagged" to "t2", "Ledger+Bookmark" to null, "StampedTagged" to "s1", "StampedTagged" to "s2"),
            written,
        )
    }

    @Test
    fun `only and except leave the lock order as it is`() {
        val store = neo4j()
        managerOver(store).saveAllFields(listOf(Ledger("l2", 2), Ledger("l1", 1)), only = setOf("total"))
        managerOver(store).saveAllFields(listOf(Ledger("l2", 2), Ledger("l1", 1)), except = setOf("total"))

        assertEquals(listOf(listOf("l1", "l2"), listOf("l1", "l2")), store.queried.map { ran -> ran.rows().map { it["id"] } })
    }

    @Test
    fun `rows of one id keep the order they were given in, so the last is still written last`() {
        val store = neo4j()
        managerOver(store).saveAll(listOf(Ledger("l2", 1), Ledger("l1", 1), Ledger("l1", 2)))

        @Suppress("UNCHECKED_CAST")
        val written = store.queried.single().rows().map { it["id"] to (it["props"] as Map<String, Any?>)["total"] }
        assertEquals(listOf("l1" to 1, "l1" to 2, "l2" to 1), written)
    }

    @Test
    fun `the objects come back in the order given, each with its own stamp`() {
        val objects = listOf(Bookmark("b3", "c-3"), Ledger("l1", 1), Bookmark("b1", "c-1"), Bookmark("b2", "c-2"))

        val saved = managerOver(neo4j()).saveAll(objects)

        assertEquals(listOf("b3", "l1", "b1", "b2"), saved.map { (it as? Bookmark)?.id ?: (it as Ledger).id })
        assertEquals(listOf("b3", "b1", "b2").map(::stampOf), saved.filterIsInstance<Bookmark>().map { it.stamp })
    }

    @Test
    fun `the objects of a batch too many for one statement come back in the order given, each with its own stamp`() {
        val objects = (0..chunk).map { Bookmark("b%04d".format(it), "c$it") }.reversed()

        val saved = managerOver(neo4j()).saveAll(objects)

        assertEquals(objects.map { it.id }, saved.map { it.id })
        assertEquals(objects.map { stampOf(it.id) }, saved.map { it.stamp })
    }

    // ----- The SQL stores -----

    private fun oracle() = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(OracleGraph("g")), graphName = "g")

    private fun postgres() = RecordingStore(DatabaseType.POSTGRES, ddl = PostgresSchemaGrammar(PostgresGraph("g")), graphName = "g")

    /** The labels and the id of each node a statement's plan saves, in the order of its steps. */
    @Suppress("UNCHECKED_CAST")
    private fun RecordingStore.Ran.steps(): List<Pair<String, Any?>> =
        (parameters.getValue(WritePlan.PLAN) as List<Map<String, Any?>>).map { (it["labels"] as List<*>).joinToString(":") to it["id"] }

    @Test
    fun `on Oracle and Postgres the steps go class by class in the order of their names, and in the order of their ids`() {
        listOf(oracle(), postgres()).forEach { store ->
            managerOver(store).saveAll(listOf(Ledger("l2", 2), Bookmark("b2", "c-2"), Ledger("l1", 1), Bookmark("b1", "c-1")))

            assertEquals(listOf("Bookmark" to "b1", "Bookmark" to "b2", "Ledger" to "l1", "Ledger" to "l2"), store.queried.single().steps(), "${store.type}")
        }
    }

    @Test
    fun `on Oracle and Postgres fragments an UNWIND could not write and stamped ones are steps in the same order as the rest`() {
        listOf(oracle(), postgres()).forEach { store ->
            managerOver(store).saveAll(listOf(StampedTagged("s2"), Tagged("t2"), Ledger("l1", 1), StampedTagged("s1"), Tagged("t1"), Bookmark("b1", "c-1")))

            assertEquals(
                listOf("Bookmark" to "b1", "Ledger" to "l1", "StampedTagged" to "s1", "StampedTagged" to "s2", "Tagged" to "t1", "Tagged" to "t2"),
                store.queried.single().steps(),
                "${store.type}",
            )
        }
    }

    @Test
    fun `on Oracle and Postgres the steps are put in order before they are split into statements`() {
        listOf(oracle(), postgres()).forEach { store ->
            val ids = (0..SQL_BATCH).map { "l%04d".format(it) }
            managerOver(store).saveAll(ids.reversed().map { Ledger(it, 1) })

            assertEquals(listOf(ids.take(SQL_BATCH), ids.drop(SQL_BATCH)), store.queried.map { ran -> ran.steps().map { it.second } }, "${store.type}")
        }
    }

    private companion object {
        /** The fragments of a statement on Oracle and Postgres: `SqlBackend.BATCH`. */
        const val SQL_BATCH = 500
    }
}
