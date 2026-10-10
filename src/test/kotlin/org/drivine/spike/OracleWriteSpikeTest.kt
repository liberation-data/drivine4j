package org.drivine.spike

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.model.Stamps
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.drivine.oracle.OracleTests
import org.testcontainers.oracle.OracleContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the stateless save contract can be kept on Oracle, where a SQL property graph is read
 * through `GRAPH_TABLE` and written as rows of the tables under it. No Drivine code runs here: each
 * save is the hand-written block in [OracleSpikeSql], run over plain JDBC, and held to the scenarios
 * of `StatelessSaveContract`, `StatelessSaveSoundnessContract` and `UncheckedWriteLockContract`.
 *
 * Run with `ORACLE_TESTS=true ./gradlew :test --tests '*OracleWriteSpikeTest'`.
 */
@OracleTests
class OracleWriteSpikeTest {

    private data class Person(val id: String, val name: String)

    /** What a view save returned: the root's stamp, and for each person by index the stamp found and the stamp left. */
    private data class Saved(val root: String, val people: Map<Int, Pair<String, String>>)

    private fun Connection.saveView(
        id: String, text: String, expected: String? = null, people: List<Person> = emptyList(), replace: Boolean = false,
    ): Saved? = prepareCall(OracleSpikeSql.SAVE_VIEW).use { call ->
        call.setString(1, id)
        call.setString(2, text)
        call.setString(3, expected)
        call.setString(4, Stamps.fresh())
        call.setString(5, json.writeValueAsString(people.map { mapOf("id" to it.id, "name" to it.name, "fresh" to Stamps.fresh()) }))
        call.setInt(6, if (replace) 1 else 0)
        call.registerOutParameter(7, Types.VARCHAR)
        call.execute()
        call.getString(7)?.split(',')?.let { parts ->
            Saved(parts.first(), parts.drop(1).associate { part ->
                val (key, stamp) = part.split('=', limit = 2)
                key.substringBefore('/').toInt() to (key.substringAfter('/') to stamp)
            })
        }
    }

    private fun Connection.saveEntity(id: String, name: String, labels: Set<String>, props: Map<String, Any?>): String =
        prepareCall(OracleSpikeSql.SAVE_ENTITY).use { call ->
            call.setString(1, id)
            call.setString(2, name)
            call.setString(3, json.writeValueAsString(labels))
            call.setString(4, json.writeValueAsString(props))
            call.setString(5, Stamps.fresh())
            call.registerOutParameter(6, Types.VARCHAR)
            call.execute()
            call.getString(6)
        }

    private fun Connection.document(sql: String, id: String): JsonNode? = prepareStatement(sql).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) json.readTree(rows.getString(1)) else null }
    }

    private fun Connection.loadView(id: String): JsonNode? = document(OracleSpikeSql.LOAD_VIEW, id)

    private fun JsonNode.people(): Map<String, String> =
        this["people"]?.takeIf { it.isArray }?.associate { it["id"].asText() to it["name"].asText() }.orEmpty()

    private fun JsonNode.stampOf(person: String): String = this["people"].single { it["id"].asText() == person }["stamp"].asText()

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun nodeToken(stamp: String) = stamp.substringBefore(':')
    private fun linksToken(stamp: String) = stamp.substringAfter(':')

    @BeforeEach
    fun clean() = OracleSpikeSql.CLEAN.forEach { db.run(it) }

    // ----- The stamp -----

    @Test
    fun `a save returns a new stamp, and loading through the graph gives the same one`() {
        val saved = assertNotNull(db.saveView("c1", "Ada founded Acme", people = listOf(Person("ada", "Ada"))))

        val loaded = assertNotNull(db.loadView("c1"))
        assertEquals("Ada founded Acme", loaded["text"].asText())
        assertEquals(saved.root, loaded["stamp"].asText())
        assertEquals(mapOf("ada" to "Ada"), loaded.people())
        assertEquals(saved.people.getValue(0).second, loaded.stampOf("ada"))
        assertEquals("", saved.people.getValue(0).first, "the person was not there before the save")
    }

    @Test
    fun `a checked save is refused when another writer changed the node, and writes nothing`() {
        val saved = assertNotNull(db.saveView("c1", "Ada founded Acme", people = listOf(Person("ada", "Ada"))))
        other.saveView("c1", "changed elsewhere")

        assertNull(db.saveView("c1", "mine", expected = saved.root, people = listOf(Person("ada", "Ada"), Person("bob", "Bob"))))

        val loaded = assertNotNull(db.loadView("c1"))
        assertEquals("changed elsewhere", loaded["text"].asText())
        assertEquals(mapOf("ada" to "Ada"), loaded.people(), "the root is checked first, so nothing else was written")
    }

    @Test
    fun `a checked save is refused when another writer deleted the node`() {
        val saved = assertNotNull(db.saveView("c1", "Ada founded Acme"))
        other.run("DELETE FROM claim WHERE id = 'c1'")

        assertNull(db.saveView("c1", "mine", expected = saved.root))
        assertNull(db.loadView("c1"), "a checked save does not make the node again")
    }

    @Test
    fun `a save that changes nothing keeps every stamp`() {
        val people = listOf(Person("ada", "Ada"))
        val first = assertNotNull(db.saveView("c1", "Ada founded Acme", people = people))

        val second = assertNotNull(db.saveView("c1", "Ada founded Acme", expected = first.root, people = people))

        assertEquals(first.root, second.root)
        assertEquals(first.people.getValue(0).second, second.people.getValue(0).second)
    }

    @Test
    fun `a change to a node replaces its first token, a relationship the second at both ends`() {
        val first = assertNotNull(db.saveView("c1", "one", people = listOf(Person("ada", "Ada"))))
        val adaFirst = first.people.getValue(0).second

        val renamed = assertNotNull(db.saveView("c1", "two", expected = first.root, people = listOf(Person("ada", "Ada"))))
        assertNotEquals(nodeToken(first.root), nodeToken(renamed.root))
        assertEquals(linksToken(first.root), linksToken(renamed.root))
        assertEquals(adaFirst, renamed.people.getValue(0).second, "a person the save did not change keeps its stamp")

        val joined = assertNotNull(
            db.saveView("c1", "two", expected = renamed.root, people = listOf(Person("ada", "Ada"), Person("bob", "Bob")))
        )
        assertEquals(nodeToken(renamed.root), nodeToken(joined.root))
        assertNotEquals(linksToken(renamed.root), linksToken(joined.root))
        assertEquals(adaFirst, joined.people.getValue(0).second)
    }

    @Test
    fun `a save hands back the stamp a related node carried before another writer changed it`() {
        val first = assertNotNull(db.saveView("c1", "one", people = listOf(Person("ada", "Ada"))))
        val carried = first.people.getValue(0).second
        other.saveView("c2", "another claim", people = listOf(Person("ada", "Ada Lovelace")))

        val second = assertNotNull(db.saveView("c1", "one", expected = first.root, people = listOf(Person("ada", "Ada"))))

        val (found, left) = second.people.getValue(0)
        assertNotEquals(carried, found, "the save reports what it found, so the caller can keep its own token")
        assertEquals(found.substringAfter(':'), left.substringAfter(':'))
    }

    // ----- Relationships -----

    @Test
    fun `a list that is the whole list removes the relationships it no longer holds, and no node`() {
        val first = assertNotNull(db.saveView("c1", "one", people = listOf(Person("ada", "Ada"), Person("bob", "Bob"))))
        val bobBefore = assertNotNull(db.loadView("c1")).stampOf("bob")

        val second = assertNotNull(
            db.saveView("c1", "one", expected = first.root, people = listOf(Person("ada", "Ada")), replace = true)
        )

        assertEquals(mapOf("ada" to "Ada"), assertNotNull(db.loadView("c1")).people())
        assertNotEquals(linksToken(first.root), linksToken(second.root), "the root is marked")
        val bob = db.prepareStatement("SELECT stamp FROM human WHERE id = 'bob'").use { s ->
            s.executeQuery().use { rows -> assertTrue(rows.next(), "bob is still there"); rows.getString(1) }
        }
        assertEquals(nodeToken(bobBefore), nodeToken(bob))
        assertNotEquals(linksToken(bobBefore), linksToken(bob), "the node at the other end is marked")
    }

    @Test
    fun `a list that only adds keeps another writer's relationship`() {
        val first = assertNotNull(db.saveView("c1", "one", people = listOf(Person("ada", "Ada"))))
        other.saveView("c1", "one", people = listOf(Person("cy", "Cy")))

        // The other writer marked the root's relationships, not its data, so this save is not stale.
        assertNotNull(db.saveView("c1", "one", expected = first.root, people = listOf(Person("bob", "Bob"))))

        assertEquals(setOf("ada", "bob", "cy"), assertNotNull(db.loadView("c1")).people().keys)
    }

    // ----- One statement, whole or not at all -----

    @Test
    fun `a save that fails part way writes nothing, with no transaction around it`() {
        assertTrue(db.autoCommit)
        val first = assertNotNull(db.saveView("c1", "one", people = listOf(Person("ada", "Ada"))))

        assertFailsWith<SQLException> {
            // 'zed' sorts last, so the root and two people are written before the block fails.
            db.saveView(
                "c1", "two", expected = first.root, replace = true,
                people = listOf(Person("bob", "Bob"), Person("cy", "Cy"), Person("zed", "FAIL on the check constraint")),
            )
        }

        val loaded = assertNotNull(other.loadView("c1"))
        assertEquals("one", loaded["text"].asText())
        assertEquals(first.root, loaded["stamp"].asText())
        assertEquals(mapOf("ada" to "Ada"), loaded.people())
    }

    @Test
    fun `a save in a transaction is read back through the graph before it commits, and is gone when rolled back`() {
        db.autoCommit = false
        try {
            db.saveView("c1", "one", people = listOf(Person("ada", "Ada")))

            assertEquals(mapOf("ada" to "Ada"), assertNotNull(db.loadView("c1")).people())
            assertNull(other.loadView("c1"), "no other session sees it yet")
            db.rollback()
        } finally {
            db.autoCommit = true
        }
        assertNull(db.loadView("c1"))
    }

    @Test
    fun `a save in a transaction that read the node before another writer changed it is refused, and writes nothing`() {
        val first = assertNotNull(db.saveView("c1", "one"))
        db.autoCommit = false
        try {
            assertEquals("one", assertNotNull(db.loadView("c1"))["text"].asText())
            other.saveView("c1", "changed elsewhere")

            assertNull(db.saveView("c1", "mine", expected = first.root, people = listOf(Person("ada", "Ada"))))
            db.commit()
        } finally {
            db.autoCommit = true
        }
        val loaded = assertNotNull(db.loadView("c1"))
        assertEquals("changed elsewhere", loaded["text"].asText())
        assertTrue(loaded.people().isEmpty())
    }

    // ----- Writers at once -----

    @Test
    fun `of several writers saving the same loaded object at once, exactly one succeeds`() = writers(8) { connections, at ->
        repeat(25) { round ->
            val id = "race-$round"
            val loaded = assertNotNull(db.saveView(id, "start"))

            val saved = at { writer -> connections[writer].saveView(id, "writer $writer", expected = loaded.root)?.let { writer to it } }
                .filterNotNull().map { it as Pair<*, *> }

            assertEquals(1, saved.size, "round $round")
            val (winner, stamps) = saved.single()
            val now = assertNotNull(db.loadView(id))
            assertEquals("writer $winner", now["text"].asText())
            assertEquals((stamps as Saved).root, now["stamp"].asText())
        }
    }

    @Test
    fun `two unchecked saves of a node at once are not handed one token for two texts`() = writers(8) { connections, at ->
        repeat(25) { round ->
            val id = "unchecked-$round" // not there yet: the writers race to make it as well
            val saved = at { writer -> "writer $writer" to assertNotNull(connections[writer].saveView(id, "writer $writer")) }
                .map { it as Pair<*, *> }

            val textByToken = saved.groupBy({ nodeToken((it.second as Saved).root) }, { it.first })
            textByToken.forEach { (token, texts) -> assertEquals(1, texts.toSet().size, "round $round: $token speaks for $texts") }
            val now = assertNotNull(db.loadView(id))
            assertEquals(listOf(now["text"].asText()), textByToken.getValue(nodeToken(now["stamp"].asText())).distinct())
        }
    }

    @Test
    fun `views that save the same people in opposite orders at once do not turn each other away`() = writers(8) { connections, at ->
        repeat(25) { round ->
            at { writer ->
                val people = listOf(Person("p1", "one $round $writer"), Person("p2", "two $round $writer"), Person("p3-$round", "three"))
                connections[writer].saveView("view-$writer", "round $round", people = if (writer % 2 == 0) people else people.reversed())
            }
        }
        assertEquals(setOf("p1", "p2") + (0 until 25).map { "p3-$it" }, assertNotNull(db.loadView("view-0")).people().keys)
    }

    // ----- A named entity: labels and properties that are data -----

    @Test
    fun `an entity save adds labels and removes none, and merges its properties`() {
        val first = db.saveEntity("e1", "Ada", setOf("Person"), mapOf("born" to 1815, "field" to "maths"))
        val second = db.saveEntity("e1", "Ada", setOf("Mathematician"), mapOf("field" to null, "died" to 1852))

        val loaded = assertNotNull(db.document(OracleSpikeSql.LOAD_ENTITY, "e1"))
        assertEquals(listOf("Mathematician", "Person"), loaded["labels"].map { it.asText() })
        assertEquals(mapOf("born" to 1815, "died" to 1852), json.convertValue(loaded["props"], Map::class.java))
        assertNotEquals(nodeToken(first), nodeToken(second))
        assertEquals(second, db.saveEntity("e1", "Ada", setOf("Person"), mapOf("born" to 1815)), "nothing changed, so the stamp is kept")
    }

    @Test
    fun `entities are found through the graph by a label and a property that are data`() {
        db.saveEntity("ada", "Ada", setOf("Person", "Mathematician"), mapOf("born" to 1815))
        db.saveEntity("alan", "Alan", setOf("Person", "Mathematician"), mapOf("born" to 1912))
        db.saveEntity("acme", "Acme", setOf("Company"), mapOf("born" to 1800))

        val found = db.prepareStatement(OracleSpikeSql.FIND_ENTITIES).use { statement ->
            statement.setString(1, "Mathematician")
            statement.setInt(2, 1900)
            statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }

        assertEquals(listOf("ada"), found)
    }

    @Test
    fun `entity saves at once each keep the label and the property the other added`() = writers(8) { connections, at ->
        repeat(10) { round ->
            val id = "entity-$round"
            at { writer -> connections[writer].saveEntity(id, "Ada", setOf("L$writer"), mapOf("p$writer" to writer)) }

            val loaded = assertNotNull(db.document(OracleSpikeSql.LOAD_ENTITY, id))
            assertEquals((0 until 8).map { "L$it" }, loaded["labels"].map { it.asText() }, "round $round")
            assertEquals((0 until 8).associate { "p$it" to it }, json.convertValue(loaded["props"], Map::class.java), "round $round")
        }
    }

    /**
     * Gives [body] one connection for each of [count] writers and `at`, which runs a piece of work
     * on every writer at the same moment and returns what each made of it.
     */
    private fun writers(count: Int, body: (List<Connection>, ((Int) -> Any?) -> List<Any?>) -> Unit) {
        val connections = List(count) { connect() }
        val pool = Executors.newFixedThreadPool(count)
        try {
            body(connections) { work ->
                val start = CountDownLatch(1)
                val outcomes = (0 until count).map { writer -> pool.submit<Any?> { start.await(); work(writer) } }
                start.countDown()
                outcomes.map { it.get(60, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdown()
            connections.forEach { it.close() }
        }
    }

    companion object {
        private val json = ObjectMapper()
        private val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        private lateinit var db: Connection
        private lateinit var other: Connection

        private fun connect(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

        @JvmStatic
        @BeforeAll
        fun setup() {
            container.start()
            db = connect()
            other = connect()
            db.createStatement().use { statement -> OracleSpikeSql.SCHEMA.forEach { statement.execute(it.trimIndent()) } }
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            db.close()
            other.close()
            container.stop()
        }
    }
}
