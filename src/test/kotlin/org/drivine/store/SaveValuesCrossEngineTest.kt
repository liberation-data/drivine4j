package org.drivine.store

import org.drivine.DrivineException
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.nodeRef
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@NodeFragment(labels = ["SavedValue"])
data class SavedValue(
    @NodeId val id: String,
    val d: Double? = null,
    val l: List<Double>? = null,
    val m: Map<String, Any?>? = null,
    val b: ByteArray? = null,
    val day: LocalDate? = null,
    val at: ZonedDateTime? = null,
)

/** A node whose id is a float, which Oracle holds as a binary double. */
@NodeFragment(labels = ["SavedGauge"])
data class SavedGauge(@NodeId val id: Double, val name: String)

/**
 * A value a save writes is held as a `CREATE` given it holds it, and read back by Cypher as the same value
 * (liberation-data/drivine4j#830): NaN and the infinities as the store's float, or refused with the `CREATE`'s message where
 * its JSON holds them as no number; a whole float as a float, not an integer; bytes as the list of their signed values.
 */
abstract class SaveValuesContract {

    abstract val pm: NonTransactionalPersistenceManager
    abstract fun clear()

    /** Of NaN, +Infinity and -Infinity, those the store's JSON holds as no number, which a save and a `CREATE` refuse. */
    abstract val refused: Set<Double>

    private val manager by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    @BeforeEach
    fun clean() = clear()

    private fun cypher(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(statement)).bind(parameters))

    private fun read(id: String, expression: String): Any? =
        cypher("MATCH (n:SavedValue) WHERE n.id = \$id RETURN $expression", mapOf("id" to id)).single()

    /** Each non-finite float, alone, beside a date and beside a datetime. */
    private val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).flatMap { d ->
        listOf(
            SavedValue("alone-$d", d = d),
            SavedValue("day-$d", d = d, day = LocalDate.of(2024, 1, 1)),
            SavedValue("at-$d", d = d, at = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2))),
        )
    }

    @Test
    fun `NaN and the infinities saved read back by Cypher as floats, or are refused with the message a CREATE given them is`() {
        for (saved in nonFinite) {
            val d = saved.d!!
            if (d in refused) {
                val save = assertFailsWith<DrivineException>("${saved.id}: the save is refused") { manager.save(saved) }
                val props = listOfNotNull("d" to d, saved.day?.let { "day" to it }, saved.at?.let { "at" to it }).toMap()
                val create = assertFailsWith<DrivineException>("${saved.id}: the CREATE is refused") {
                    pm.execute(QuerySpecification.withStatement(CypherStatement("CREATE (n:SavedValue {id: \$id}) SET n += \$props")).bind(mapOf("id" to "c-${saved.id}", "props" to props)))
                }
                assertEquals(create.message, save.message, saved.id)
                assertEquals(0, manager.count(SavedValue::class.java), "${saved.id}: nothing was written")
            } else {
                manager.save(saved)
                val read = read(saved.id, "n.d")
                assertTrue(read is Double && read.equals(d), "${saved.id}: read back as $read (${read?.javaClass})")
                // A datetime loads back as the same instant, in UTC.
                val loaded = manager.load(saved.id, SavedValue::class.java)!!
                assertEquals(saved.copy(at = null), loaded.copy(at = null), saved.id)
                assertEquals(saved.at?.toInstant(), loaded.at?.toInstant(), saved.id)
            }
        }
    }

    @Test
    fun `a whole float saved, alone, in a list or in a map, reads back by Cypher as a float`() {
        manager.save(SavedValue("f", d = 2.0, l = listOf(1.5, 2.0), m = mapOf("x" to 2.0)))
        manager.save(SavedValue("big", d = 1e16))

        assertEquals(listOf(2.0, 2.0, 2.0, "2.0", "2.0"), cypher("MATCH (n:SavedValue {id: 'f'}) RETURN [n.d, n.l[1], n.m.x, toString(n.d), toString(n.l[1])]").single())
        assertEquals(1e16, read("big", "n.d"))
        assertEquals("1.0E16", read("big", "toString(n.d)"))
        // And as a CREATE given the same values writes them: the two are equal.
        cypher("CREATE (n:SavedValue {id: 'c'}) SET n += \$props", mapOf("props" to mapOf("d" to 2.0, "l" to listOf(1.5, 2.0))))
        assertEquals(listOf(true, true), cypher("MATCH (s:SavedValue {id: 'f'}), (c:SavedValue {id: 'c'}) RETURN [s.d = c.d AND toString(s.d) = toString(c.d), s.l = c.l]").single())
        assertEquals(mapOf("x" to 2.0), manager.load("f", SavedValue::class.java)!!.m)
    }

    @Test
    fun `a node saved again with a whole float in place of another float holds a float`() {
        manager.save(SavedValue("f", d = 2.5, l = listOf(1.5), day = LocalDate.of(2024, 1, 1)))
        manager.save(manager.load("f", SavedValue::class.java)!!.copy(d = 3.0))

        assertEquals(listOf(3.0, "3.0", 1.5, "2024-01-01"), cypher("MATCH (n:SavedValue {id: 'f'}) RETURN [n.d, toString(n.d), n.l[0], toString(n.day)]").single())
    }

    @Test
    fun `bytes saved read back by Cypher as the list of their signed values`() {
        manager.save(SavedValue("b", b = byteArrayOf(1, 2, -1)))

        assertEquals(listOf(1L, 2L, -1L), read("b", "n.b"))
        assertEquals(listOf<Byte>(1, 2, -1), manager.load("b", SavedValue::class.java)!!.b!!.toList())
    }

    @Test
    fun `a map with the key numberDouble saved reads back as that map, not as a float`() {
        manager.save(SavedValue("m", m = mapOf("\$numberDouble" to "NaN", "n" to 2.0)))

        assertEquals(mapOf("\$numberDouble" to "NaN", "n" to 2.0), read("m", "n.m"))
        assertEquals(mapOf("\$numberDouble" to "NaN", "n" to 2.0), manager.load("m", SavedValue::class.java)!!.m)
    }

    @Test
    fun `a relationship's float properties are written as a node's are`() {
        manager.saveAll(listOf(SavedValue("a"), SavedValue("b")))
        assertTrue(manager.edges.relate(nodeRef<SavedValue>("a"), nodeRef<SavedValue>("b"), "WEIGHS", mapOf("w" to 2.0, "ws" to listOf(1.5, 2.0))))

        assertEquals(listOf(2.0, "2.0", 2.0), cypher("MATCH (:SavedValue {id: 'a'})-[r:WEIGHS]->(:SavedValue {id: 'b'}) RETURN [r.w, toString(r.w), r.ws[1]]").single())
        // Related again with the same properties, it is the one relationship.
        assertTrue(manager.edges.relate(nodeRef<SavedValue>("a"), nodeRef<SavedValue>("b"), "WEIGHS", mapOf("w" to 2.0, "ws" to listOf(1.5, 2.0))))
        assertEquals(listOf(1L), cypher("MATCH (:SavedValue {id: 'a'})-[r:WEIGHS]->() RETURN count(r)"))
        assertEquals(1, manager.edges.unrelate(nodeRef<SavedValue>("a"), nodeRef<SavedValue>("b"), "WEIGHS"))
    }

    @Test
    fun `a node whose id is a float is found again by its save, its load and Cypher, once`() {
        for (id in listOf(3.0, 4.5)) {
            manager.save(SavedGauge(id, "one"))
            manager.save(SavedGauge(id, "two"))
            assertEquals(SavedGauge(id, "two"), manager.load(id.toString(), SavedGauge::class.java), "$id")
            assertEquals(listOf(1L), cypher("MATCH (n:SavedGauge) WHERE n.id = \$id RETURN count(n)", mapOf("id" to id)), "$id")
        }
        assertEquals(2, manager.count(SavedGauge::class.java))
    }
}

@PostgresTests
class SaveValuesPostgresTest : SaveValuesContract() {
    override val pm = PostgresTestDatabase.graphManager("savevalues")
    override fun clear() = PostgresTestDatabase.clear(pm)
    override val refused = setOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
}

@OracleTests
class SaveValuesOracleTest : SaveValuesContract() {
    override val pm = OracleTestDatabase.graphManager("savevalues")
    override fun clear() = OracleTestDatabase.clear(pm)
    override val refused = emptySet<Double>()
}

class SaveValuesSqliteTest : SaveValuesContract() {
    override val pm = SqliteTestDatabase.graphManager("savevalues")
    override fun clear() = SqliteTestDatabase.clear(pm)
    override val refused = setOf(Double.NaN)
}
