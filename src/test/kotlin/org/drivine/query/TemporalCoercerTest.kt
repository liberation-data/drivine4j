package org.drivine.query

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemporalCoercerTest {

    @Test
    fun `Instant is coerced to its ISO 8601 string form`() {
        val now = Instant.parse("2026-04-23T05:07:53.295203Z")
        val out = TemporalCoercer.coerce(mapOf("ts" to now))

        assertEquals("2026-04-23T05:07:53.295203Z", out["ts"])
    }

    @Test
    fun `ZonedDateTime is coerced to zone-free ISO 8601 string so Jackson can parse it back`() {
        val zdt = ZonedDateTime.of(2026, 4, 23, 5, 7, 53, 0, ZoneOffset.UTC)
        val out = TemporalCoercer.coerce(mapOf("ts" to zdt))

        // Must NOT be the default "...Z[UTC]" form — Jackson's InstantDeserializer rejects it.
        assertEquals("2026-04-23T05:07:53Z", out["ts"])
    }

    @Test
    fun `non-UTC ZonedDateTime keeps its offset but drops the zone name suffix`() {
        val zdt = ZonedDateTime.of(2026, 4, 23, 1, 7, 53, 0, ZoneId.of("America/New_York"))
        val out = TemporalCoercer.coerce(mapOf("ts" to zdt))

        assertEquals("2026-04-23T01:07:53-04:00", out["ts"])
    }

    @Test
    fun `LocalDateTime is coerced to string`() {
        val ldt = LocalDateTime.of(2026, 4, 23, 5, 7, 53)
        val out = TemporalCoercer.coerce(mapOf("ts" to ldt))

        assertEquals(ldt.toString(), out["ts"])
    }

    @Test
    fun `LocalDate is coerced to string`() {
        val date = LocalDate.of(2026, 4, 23)
        val out = TemporalCoercer.coerce(mapOf("d" to date))

        assertEquals("2026-04-23", out["d"])
    }

    @Test
    fun `list of temporal values is coerced recursively`() {
        val a = Instant.parse("2026-04-23T00:00:00Z")
        val b = Instant.parse("2026-04-24T00:00:00Z")
        val out = TemporalCoercer.coerce(mapOf("stamps" to listOf(a, b)))

        assertEquals(listOf("2026-04-23T00:00:00Z", "2026-04-24T00:00:00Z"), out["stamps"])
    }

    @Test
    fun `nested map values are coerced recursively`() {
        val now = Instant.parse("2026-04-23T05:07:53Z")
        val out = TemporalCoercer.coerce(mapOf(
            "event" to mapOf("ts" to now, "name" to "launch")
        ))

        val event = out["event"] as Map<*, *>
        assertEquals("2026-04-23T05:07:53Z", event["ts"])
        assertEquals("launch", event["name"])
    }

    @Test
    fun `non-temporal values pass through unchanged`() {
        val params = mapOf(
            "id" to "abc",
            "count" to 42,
            "flag" to true,
            "tags" to listOf("a", "b"),
            "nothing" to null
        )
        val out = TemporalCoercer.coerce(params)

        assertEquals(params, out)
    }

    @Test
    fun `mixed temporal and non-temporal values coexist`() {
        val now = Instant.parse("2026-04-23T05:07:53Z")
        val out = TemporalCoercer.coerce(mapOf(
            "id" to "abc",
            "ts" to now,
            "count" to 7
        ))

        assertEquals("abc", out["id"])
        assertEquals("2026-04-23T05:07:53Z", out["ts"])
        assertEquals(7, out["count"])
    }
}