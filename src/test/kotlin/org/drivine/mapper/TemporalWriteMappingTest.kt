package org.drivine.mapper

import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.annotation.JsonPacked
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the temporal write-path fix. `toMap` (the object→param-map conversion used by
 * `save()`) must preserve **native** java.time values, not ISO strings — so the shared
 * `convertValueForNeo4j` + per-connection coercer pipeline can convert them per backend, exactly as
 * it does for bound query params. Reads / real JSON still use ISO strings.
 */
class TemporalWriteMappingTest {

    data class Node(
        val id: String,
        val createdAt: Instant,
        val legacyDate: Date,
        val zoned: ZonedDateTime,
        val offset: OffsetDateTime,
        val day: LocalDate,
    )

    private val m = Neo4jObjectMapper.instance
    private val instant = Instant.parse("2026-06-03T02:25:23.449563Z")

    private fun node(i: Instant) = Node(
        id = "n", createdAt = i, legacyDate = Date.from(i),
        zoned = i.atZone(ZoneId.of("UTC")), offset = i.atOffset(ZoneOffset.UTC),
        day = LocalDate.of(2026, 6, 3),
    )

    @Test
    fun `toMap preserves native temporals (no stringification)`() {
        val map = m.toMap(node(instant))
        assertTrue(map["createdAt"] is Instant, "got ${map["createdAt"]?.javaClass}")
        assertTrue(map["legacyDate"] is Date, "got ${map["legacyDate"]?.javaClass}")
        assertTrue(map["zoned"] is ZonedDateTime, "got ${map["zoned"]?.javaClass}")
        assertTrue(map["offset"] is OffsetDateTime, "got ${map["offset"]?.javaClass}")
        assertTrue(map["day"] is LocalDate, "got ${map["day"]?.javaClass}")
    }

    @Test
    fun `write path and param path converge through convertValueForNeo4j`() {
        // The bug was that save() (toMap) and bind() (convertValueForNeo4j) disagreed. Now the toMap
        // output feeds the same converter the param path uses, yielding the identical driver value.
        val fromWrite = m.convertValueForNeo4j(m.toMap(node(instant))["createdAt"])
        val fromParam = m.convertValueForNeo4j(instant)
        assertEquals(fromParam, fromWrite)
        assertEquals(instant.atZone(ZoneId.of("UTC")), fromWrite)  // native ZonedDateTime, not a String
    }

    @Test
    fun `a time with its offset, a duration and a period are bound as they are, alone and in a list or a map`() {
        // #833: each went to toMap(), which can't make a map of one, and failed.
        val values = listOf(
            java.time.OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(2)), java.time.Duration.ofMillis(-1500),
            java.time.Period.ofMonths(14),
        )
        values.forEach { assertTrue(m.convertValueForNeo4j(it) === it, "$it") }
        assertEquals(values, m.convertValueForNeo4j(values))
        assertEquals(values, m.convertValueForNeo4j(values.toTypedArray()))
        assertEquals(mapOf("v" to values), m.convertValueForNeo4j(mapOf("v" to values)))
    }

    data class Amounts(
        val time: java.time.OffsetTime,
        val duration: java.time.Duration,
        val period: java.time.Period,
        val durations: List<java.time.Duration>,
        val periods: Map<String, java.time.Period>,
    )

    private val amounts = Amounts(
        java.time.OffsetTime.parse("10:11:12+02:00"), java.time.Duration.parse("P1DT2H"), java.time.Period.parse("P1Y2M"),
        listOf(java.time.Duration.ofMillis(-1500)), mapOf("trial" to java.time.Period.ofDays(30)),
    )

    @Test
    fun `toMap keeps a time with its offset, a duration and a period native, alone, in a list and in a map`() {
        // #862: JavaTimeModule wrote the time and the period as text and the duration as its seconds.
        val map = m.toMap(amounts)
        assertTrue(map["time"] === amounts.time, "got ${map["time"]?.javaClass}")
        assertTrue(map["duration"] === amounts.duration, "got ${map["duration"]?.javaClass}")
        assertTrue(map["period"] === amounts.period, "got ${map["period"]?.javaClass}")
        assertEquals(amounts.durations, map["durations"])
        assertEquals(amounts.periods, map["periods"])
        listOf(amounts.time, amounts.duration, amounts.period).forEach { assertTrue(m.toPropertyValue(it) === it, "$it") }
        // What a save writes is what bind() sends, and the map converts back to the object, as a save's returned copy is made.
        assertEquals(m.convertValueForNeo4j(amounts.duration), m.convertValueForNeo4j(map["duration"]))
        assertEquals(amounts, m.convertValue(map, Amounts::class.java))
    }

    data class Read(val duration: java.time.Duration? = null, val period: java.time.Period? = null)

    @Test
    fun `a duration a Bolt driver gives is read into a Duration without months and a Period without time`() {
        // #862: Jackson made a map of the driver's IsoDuration, which neither reads.
        fun iso(months: Long, days: Long, seconds: Long, nanos: Int) = org.neo4j.driver.Values.isoDuration(months, days, seconds, nanos).asIsoDuration()
        fun duration(iso: org.neo4j.driver.types.IsoDuration) = runCatching { m.convertValue(mapOf("duration" to iso), Read::class.java).duration }.getOrNull()
        fun period(iso: org.neo4j.driver.types.IsoDuration) = runCatching { m.convertValue(mapOf("period" to iso), Read::class.java).period }.getOrNull()

        // A duration as the driver gives Java's own: all its time in seconds.
        assertEquals(java.time.Duration.ofHours(26), duration(iso(0, 0, 93600, 0)))
        // As Cypher's duration('P1DT2H') is held: a day and two hours.
        assertEquals(java.time.Duration.ofHours(26), duration(iso(0, 1, 7200, 0)))
        assertEquals(null, period(iso(0, 1, 7200, 0)), "a period holds no hours")
        assertEquals(java.time.Duration.ofMillis(-1500), duration(iso(0, 0, -2, 500_000_000)))
        assertEquals(java.time.Period.of(1, 2, 0), period(iso(14, 0, 0, 0)))
        assertEquals(null, duration(iso(14, 0, 0, 0)), "a duration holds no months")
        assertEquals(java.time.Period.of(-1, -2, 2), period(iso(-14, 2, 0, 0)))
        assertEquals(java.time.Period.ofDays(3) to java.time.Duration.ofDays(3), period(iso(0, 3, 0, 0)) to duration(iso(0, 3, 0, 0)))
        assertEquals(java.time.Period.ZERO to java.time.Duration.ZERO, period(iso(0, 0, 0, 0)) to duration(iso(0, 0, 0, 0)))
        assertEquals(null to null, period(iso(1, 0, 1, 0)) to duration(iso(1, 0, 1, 0)), "neither holds months and time")

        assertEquals(listOf("P1Y2M3DT2H", "PT26H", "P1DT2H", "PT-1.5S", "P-1Y-2M2D", "PT0S"), listOf(
            iso(14, 3, 7200, 0), iso(0, 0, 93600, 0), iso(0, 1, 7200, 0), iso(0, 0, -2, 500_000_000), iso(-14, 2, 0, 0), iso(0, 0, 0, 0),
        ).map { m.convertValue(it, String::class.java) })
    }

    @Test
    fun `a period is read from the text of a duration of no time, and of no other`() {
        // #862: a saved Period.ZERO is stored as the duration of nothing, PT0S, which Period.parse refuses.
        fun period(text: String) = runCatching { m.readValue<Read>("""{"period": "$text"}""").period }.getOrNull()
        assertEquals(java.time.Period.ZERO, period("PT0S"))
        assertEquals(java.time.Period.ofDays(1), period("P1DT0S"))
        assertEquals(java.time.Period.of(1, 2, 3), period("P1Y2M3D"))
        assertEquals(null, period("PT1H"))
        assertEquals(null, period("P1DT0.5S"))
        assertEquals(null, m.readValue<Read>("""{"period": null}""").period)
        assertEquals(java.time.Period.ofDays(4), m.convertValue(mapOf("period" to java.time.Period.ofDays(4)), Read::class.java).period)
    }

    @Test
    fun `reads and real JSON still use ISO strings`() {
        val json = m.writeValueAsString(node(instant))
        assertTrue(json.contains("\"createdAt\":\"2026-06-03T02:25:23.449563Z\""), json)
        assertEquals(instant, m.readValue<Node>(json).createdAt)  // round-trips back
    }

    data class Packed(@JsonPacked val tags: List<String>)

    @Test
    fun `@JsonPacked still packs through the temporal write mapper`() {
        // @JsonPacked packs collections to a JSON string via its own mapper; the write mapper used by
        // toMap must leave that behaviour intact. (Note: @JsonPacked collections of *temporals* are a
        // separate, pre-existing limitation — its packing mapper has no JavaTimeModule.)
        val packed = m.toMap(Packed(listOf("a", "b")))["tags"]
        assertTrue(packed is String, "expected a packed JSON string, got ${packed?.javaClass}")
        assertEquals("""["a","b"]""", packed)
        assertEquals(listOf("a", "b"), m.convertValue(mapOf("tags" to packed), Packed::class.java).tags)
    }
}