package org.drivine.sqlite

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetTime
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A node of each of the three temporal kinds a save wrote as text or a number before liberation-data/drivine4j#862. */
@NodeFragment(labels = ["Amounts"])
data class AmountsNode(
    @NodeId val id: String,
    val time: OffsetTime? = null,
    val duration: Duration? = null,
    val period: Period? = null,
    val times: List<OffsetTime> = emptyList(),
    val durations: List<Duration> = emptyList(),
    val periods: List<Period> = emptyList(),
    @PropertyBag val waits: Map<String, Duration> = emptyMap(),
) {
    companion object {
        val TIME: OffsetTime = OffsetTime.parse("10:11:12+02:00")
        val DURATION: Duration = Duration.parse("P1DT2H")
        val PERIOD: Period = Period.parse("P1Y2M")

        /** A node with each kind alone, in a list and, for a duration, in a property bag. */
        fun full(id: String) = AmountsNode(
            id, TIME, DURATION, PERIOD, listOf(TIME, OffsetTime.parse("23:00Z")), listOf(DURATION, Duration.ofMillis(-1500)),
            listOf(PERIOD, Period.ofDays(3)), mapOf("queue" to Duration.ofMinutes(3)),
        )
    }
}

/** The node of [AmountsNode] read with its duration alone. */
@NodeFragment(labels = ["Amounts"])
data class DurationOnly(@NodeId val id: String, val duration: Duration? = null)

/** The node of [AmountsNode] read with its period alone. */
@NodeFragment(labels = ["Amounts"])
data class PeriodOnly(@NodeId val id: String, val period: Period? = null)

/**
 * A save of an `OffsetTime`, a `Duration` and a `Period` on SQLite (liberation-data/drivine4j#862): each is stored as the
 * store layout's tag of its kind, `time` or `duration`, as `bind()` writes it, read back into its field, and found by Cypher's
 * `time(...)` and `duration(...)`. A node a save wrote before, with the text or the number, still loads.
 */
class SqliteTemporalAmountsTest {

    private val pm = SqliteTestDatabase.graphManager("amounts")
    private val om = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
    private val cypher = SqliteTestDatabase.fixtures(pm)

    @BeforeEach
    fun clean() = SqliteTestDatabase.clear(pm)

    /** The JSON at [path] of the node [id]'s properties. */
    private fun at(id: String, path: String): String? = pm.query(
        QuerySpecification.withStatement("SELECT json_extract(props, \$path) FROM amounts_node WHERE props ->> '$.id' = \$id")
            .bind(mapOf("id" to id, "path" to path)).transform(String::class.java)
    ).singleOrNull()

    /** The `$t` of the value at [path] of the node [id]'s properties, or, where it is no tag, its JSON type. */
    private fun kind(id: String, path: String): String? = at(id, "$path.\"\$t\"") ?: pm.query(
        QuerySpecification.withStatement("SELECT json_type(props, \$path) FROM amounts_node WHERE props ->> '$.id' = \$id")
            .bind(mapOf("id" to id, "path" to path)).transform(String::class.java)
    ).singleOrNull()

    private fun props(id: String): String? = at(id, "$")

    private fun ids(statement: String): List<String> =
        cypher.query(QuerySpecification.withStatement(statement).transform(String::class.java)).sorted()

    @Test
    fun `each is stored as its kind, in a list and in a property bag too, and loads back as it was saved`() {
        val node = AmountsNode.full("a")
        om.save(node)

        val kinds = listOf("$.time", "$.duration", "$.period", "$.\"waits.queue\"") +
            listOf("times", "durations", "periods").flatMap { p -> listOf("$.$p[0]", "$.$p[1]") }
        assertEquals(
            listOf("time", "duration", "duration", "duration", "time", "time", "duration", "duration", "duration", "duration"),
            kinds.map { kind("a", it) },
            props("a"),
        )
        // A duration is held as Java writes it, in hours, where Cypher's duration('P1DT2H') keeps the day.
        assertEquals("""{"${'$'}t":"duration","v":"PT26H"}""", at("a", "$.duration"))
        assertEquals(node, om.load("a", AmountsNode::class.java))
    }

    @Test
    fun `each is found by Cypher's own value of its kind, alone and in a list`() {
        om.save(AmountsNode.full("a"))
        om.save(AmountsNode("b", OffsetTime.parse("10:11:12+03:00"), Duration.ofHours(25), Period.ofMonths(13)))

        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.time = time('10:11:12+02:00') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.duration = duration('PT26H') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.period = duration('P1Y2M') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.`waits.queue` = duration('PT3M') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE duration('PT-1.5S') IN n.durations RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE time('23:00Z') IN n.times RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE duration('P3D') IN n.periods RETURN n.id"))
        // A duration of 26 hours is not one of a day and two hours, as on Neo4j, which compares each part on its own.
        assertEquals(emptyList(), ids("MATCH (n:Amounts) WHERE n.duration = duration('P1DT2H') RETURN n.id"))
        // A time compares as the moment it is at its offset, and a duration's parts are read as Cypher reads them.
        assertEquals(listOf("b"), ids("MATCH (n:Amounts) WHERE n.time < time('10:11:12+02:00') RETURN n.id"))
        assertEquals(listOf("a", "b"), ids("MATCH (n:Amounts) WHERE n.duration.hours >= 25 RETURN n.id"))
        assertEquals(listOf("b"), ids("MATCH (n:Amounts) WHERE n.period.months = 13 RETURN n.id"))
    }

    @Test
    fun `a value of each kind at its edges loads back as it was saved`() {
        // A period of nothing is stored as Neo4j writes it, PT0S, which Period.parse alone refuses.
        listOf(Period.ZERO, Period.ofWeeks(2), Period.of(1, 2, 3), Period.of(0, -1, 2), Period.ofYears(-3)).forEachIndexed { i, period ->
            om.save(PeriodOnly("p$i", period))
            assertEquals(period, om.load("p$i", PeriodOnly::class.java)?.period, "$period, stored as ${props("p$i")}")
        }
        listOf(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1500), Duration.ofDays(400), Duration.ofDays(-1).minusNanos(1)).forEachIndexed { i, duration ->
            om.save(DurationOnly("d$i", duration))
            assertEquals(duration, om.load("d$i", DurationOnly::class.java)?.duration, "$duration, stored as ${props("d$i")}")
        }
        // A period's months are carried into years, as Neo4j holds them: the same period, though Period's equals tells them apart.
        om.save(PeriodOnly("months", Period.ofMonths(14)))
        assertEquals(Period.of(1, 2, 0), om.load("months", PeriodOnly::class.java)?.period)
        val time = OffsetTime.parse("23:59:59.123456789-05:30")
        om.save(AmountsNode("t", time = time))
        assertEquals(time, om.load("t", AmountsNode::class.java)?.time)
    }

    @Test
    fun `a duration Cypher writes loads into a Duration when it has no months, and into a Period when it has no time`() {
        fun loaded(duration: String): Pair<Any?, Any?> {
            clean()
            cypher.execute(QuerySpecification.withStatement("CREATE (:Amounts {id: 'c', duration: $duration, period: $duration})"))
            return runCatching { om.load("c", DurationOnly::class.java)?.duration }.getOrElse { "refused" } to
                runCatching { om.load("c", PeriodOnly::class.java)?.period }.getOrElse { "refused" }
        }
        assertEquals(Duration.ofHours(26) to "refused", loaded("duration('P1DT2H')"), "a period holds no hours")
        assertEquals("refused" to Period.of(1, 2, 0), loaded("duration('P1Y2M')"), "a duration holds no months")
        assertEquals(Duration.ofDays(3) to Period.ofDays(3), loaded("duration('P3D')"))
        assertEquals(Duration.ZERO to Period.ZERO, loaded("duration('PT0S')"))
        assertEquals("refused" to "refused", loaded("duration('P1M2DT3H')"), "neither holds both months and hours")

        clean()
        cypher.execute(QuerySpecification.withStatement("CREATE (:Amounts {id: 'm', duration: duration('P1M')})"))
        val refused = assertFails { om.load("m", DurationOnly::class.java) }
        assertTrue(generateSequence(refused) { it.cause }.any { "P1M" in it.message.orEmpty() }, refused.toString())
    }

    @Test
    fun `a node a save wrote before, with the text or the number, still loads, and is found by a value of its kind once saved again`() {
        // As a save wrote them before #862: JavaTimeModule's text, and a duration's seconds.
        SqliteSeed(pm).node(
            "Amounts",
            properties = mapOf(
                "id" to "old", "time" to AmountsNode.TIME, "duration" to AmountsNode.DURATION, "period" to AmountsNode.PERIOD,
                "times" to listOf(AmountsNode.TIME), "durations" to listOf(AmountsNode.DURATION), "periods" to listOf(AmountsNode.PERIOD),
            ),
        )
        assertEquals(listOf("text", "real", "text"), listOf("$.time", "$.duration", "$.period").map { kind("old", it) }, props("old"))

        val old = assertNotNull(om.load("old", AmountsNode::class.java))
        assertEquals(
            AmountsNode(
                "old", AmountsNode.TIME, AmountsNode.DURATION, AmountsNode.PERIOD,
                listOf(AmountsNode.TIME), listOf(AmountsNode.DURATION), listOf(AmountsNode.PERIOD),
            ),
            old,
        )

        // A value of the kind, given to a load or to Cypher, finds only a node stored as the kind.
        om.save(AmountsNode("new", AmountsNode.TIME, AmountsNode.DURATION, AmountsNode.PERIOD))
        fun found(property: String, value: Any) = om.loadAll(AmountsNode::class.java, Any()) {
            conditions += PropertyCondition("n.$property", ComparisonOperator.EQUALS, value)
        }.map { it.id }.sorted()
        assertEquals(listOf("new"), found("time", AmountsNode.TIME))
        assertEquals(listOf("new"), found("duration", AmountsNode.DURATION))
        assertEquals(listOf("new"), found("period", AmountsNode.PERIOD))
        assertEquals(listOf("new"), ids("MATCH (n:Amounts) WHERE n.duration = duration('PT26H') RETURN n.id"))

        om.save(old)
        assertEquals(listOf("time", "duration", "duration"), listOf("$.time", "$.duration", "$.period").map { kind("old", it) }, props("old"))
        assertEquals(listOf("new", "old"), found("time", AmountsNode.TIME))
        assertEquals(listOf("new", "old"), found("duration", AmountsNode.DURATION))
        assertEquals(listOf("new", "old"), found("period", AmountsNode.PERIOD))
    }
}
