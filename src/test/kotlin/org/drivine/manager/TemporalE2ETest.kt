package org.drivine.manager

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared fixture covering every temporal type Drivine maps (reused by the cross-engine tests). */
@NodeFragment(labels = ["TemporalsNode"])
data class TemporalsNode(
    @NodeId val id: String,
    val instant: Instant,
    val zoned: ZonedDateTime,
    val date: LocalDate,
    val dateTime: LocalDateTime,
    val optional: Instant? = null,
    val createdBy: String = "temporal-test",
) {
    companion object {
        val INSTANT: Instant = Instant.parse("2026-06-03T02:25:23.449563Z")

        /** A node at [INSTANT] + [plusSeconds]; distinct ids keep the session snapshot from interfering. */
        fun at(id: String, plusSeconds: Long = 0): TemporalsNode {
            val i = INSTANT.plusSeconds(plusSeconds)
            return TemporalsNode(id, i, i.atZone(ZoneId.of("UTC")), LocalDate.of(2026, 6, 3),
                LocalDateTime.of(2026, 6, 3, 2, 25, 23, 449563000))
        }
    }
}

/**
 * End-to-end temporal behaviour against Neo4j: every `java.time` type stores natively, round-trips,
 * is range-queryable and orderable, and updates persist. Covers `Instant`/`Date` plus the native
 * direct properties (`ZonedDateTime`/`LocalDate`/`LocalDateTime`) the write-mapper fix enabled.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class TemporalE2ETest @Autowired constructor(
    private val gom: GraphObjectManager,
    private val pm: PersistenceManager,
) {
    private val node = TemporalsNode.at("base")

    @BeforeEach
    fun clean() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) WHERE n.createdBy = 'temporal-test' DETACH DELETE n"))
    }

    @Test
    fun `all temporal types round-trip, including a null`() {
        gom.save(node.copy(id = "rt"))
        val loaded = gom.load("rt", TemporalsNode::class.java)!!
        assertEquals(node.instant, loaded.instant)
        // Same instant on the timeline — Neo4j normalizes a UTC-region ZonedDateTime to a Z offset.
        assertTrue(node.zoned.isEqual(loaded.zoned), "expected same instant, got ${loaded.zoned}")
        assertEquals(node.date, loaded.date)
        assertEquals(node.dateTime, loaded.dateTime)
        assertNull(loaded.optional)
    }

    @Test
    fun `each temporal type is stored as a native (non-string) value`() {
        gom.save(node.copy(id = "vt"))
        listOf("instant", "zoned", "date", "dateTime").forEach { prop ->
            val vt = pm.getOne(
                QuerySpecification.withStatement("MATCH (n:TemporalsNode {id: 'vt'}) RETURN valueType(n.$prop)")
                    .transform(String::class.java)
            )
            assertTrue(!vt.contains("STRING"), "$prop stored as $vt (expected a temporal type)")
        }
    }

    @Test
    fun `range query with bound Instant and ZonedDateTime params match`() {
        gom.save(node.copy(id = "range"))
        assertEquals(listOf("range"), instantRange(node.instant.minusSeconds(60)))
        assertEquals(emptyList(), instantRange(node.instant.plusSeconds(60)))

        fun zonedRange(since: ZonedDateTime) = pm.query(
            QuerySpecification.withStatement("MATCH (n:TemporalsNode) WHERE n.zoned >= \$s RETURN n.id")
                .bind(mapOf("s" to since)).transform(String::class.java)
        )
        assertEquals(listOf("range"), zonedRange(node.zoned.minusDays(1)))
    }

    @Test
    fun `order by a temporal property is chronological`() {
        gom.save(TemporalsNode.at("mid", 0))
        gom.save(TemporalsNode.at("late", 10))
        gom.save(TemporalsNode.at("early", -10))
        val ordered = pm.query(
            QuerySpecification.withStatement("MATCH (n:TemporalsNode) RETURN n.id ORDER BY n.instant")
                .transform(String::class.java)
        )
        assertEquals(listOf("early", "mid", "late"), ordered)
    }

    @Test
    fun `updating a temporal persists the new value (dirty save)`() {
        gom.save(node.copy(id = "upd"))
        val loaded = gom.load("upd", TemporalsNode::class.java)!!
        val moved = node.instant.plusSeconds(3600)
        gom.save(loaded.copy(instant = moved))   // only `instant` is dirty
        assertEquals(moved, gom.load("upd", TemporalsNode::class.java)!!.instant)
    }

    private fun instantRange(since: Instant) = pm.query(
        QuerySpecification.withStatement("MATCH (n:TemporalsNode) WHERE n.instant >= \$s RETURN n.id")
            .bind(mapOf("s" to since)).transform(String::class.java)
    )
}