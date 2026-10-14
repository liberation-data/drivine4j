package org.drivine.query

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.OffsetTime
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

        assertEquals("2026-04-23T05:07:53.295203000Z", out["ts"])
    }

    @Test
    fun `ZonedDateTime is coerced to zone-free ISO 8601 string so Jackson can parse it back`() {
        val zdt = ZonedDateTime.of(2026, 4, 23, 5, 7, 53, 0, ZoneOffset.UTC)
        val out = TemporalCoercer.coerce(mapOf("ts" to zdt))

        // Must NOT be the default "...Z[UTC]" form — Jackson's InstantDeserializer rejects it.
        assertEquals("2026-04-23T05:07:53.000000000Z", out["ts"])
    }

    @Test
    fun `a time exactly on a minute keeps its seconds, which an Instant is not read without`() {
        val zdt = ZonedDateTime.of(2040, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val out = TemporalCoercer.coerce(mapOf("zoned" to zdt, "offset" to zdt.toOffsetDateTime()))

        assertEquals("2040-01-01T00:00:00.000000000Z", out["zoned"])
        assertEquals("2040-01-01T00:00:00.000000000Z", out["offset"])
    }

    @Test
    fun `non-UTC ZonedDateTime keeps its offset but drops the zone name suffix`() {
        val zdt = ZonedDateTime.of(2026, 4, 23, 1, 7, 53, 0, ZoneId.of("America/New_York"))
        val out = TemporalCoercer.coerce(mapOf("ts" to zdt))

        assertEquals("2026-04-23T01:07:53.000000000-04:00", out["ts"])
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

        assertEquals(listOf("2026-04-23T00:00:00.000000000Z", "2026-04-24T00:00:00.000000000Z"), out["stamps"])
    }

    @Test
    fun `nested map values are coerced recursively`() {
        val now = Instant.parse("2026-04-23T05:07:53Z")
        val out = TemporalCoercer.coerce(mapOf(
            "event" to mapOf("ts" to now, "name" to "launch")
        ))

        val event = out["event"] as Map<*, *>
        assertEquals("2026-04-23T05:07:53.000000000Z", event["ts"])
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
        assertEquals("2026-04-23T05:07:53.000000000Z", out["ts"])
        assertEquals(7, out["count"])
    }

    @Test
    fun `times with an offset are of one width, so their text sorts as they do`() {
        val second = Instant.parse("2026-03-01T12:00:00Z")
        val moments = listOf(0L, 1_000, 120_000_000, 123_000_000, 500_000_000, 999_999_999, 1_000_000_000).map(second::plusNanos)

        fun texts(of: (Instant) -> Any) = moments.map { TemporalCoercer.coerce(mapOf("v" to of(it)))["v"] as String }

        val forms: List<(Instant) -> Any> = listOf(
            { it },
            { it.atZone(ZoneId.of("America/New_York")) },
            { it.atOffset(ZoneOffset.ofHours(2)) },
            { it.atOffset(ZoneOffset.UTC).toOffsetTime() },
        )
        forms.forEach { form ->
            val written = texts(form)
            assertEquals(written.sorted(), written)
            assertEquals(1, written.map { it.length }.distinct().size)
        }
        assertEquals(moments, texts { it }.map(Instant::parse))
        assertEquals(moments, texts { it.atOffset(ZoneOffset.ofHours(2)) }.map { OffsetDateTime.parse(it).toInstant() })
        assertEquals("12:00:00.120000000Z", TemporalCoercer.coerce(mapOf("v" to OffsetTime.parse("12:00:00.12Z")))["v"])
    }

    @Test
    fun `the shortest form writes a time in as few digits as it has`() {
        val zoned = ZonedDateTime.of(2040, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val out = TemporalCoercer.shortest(mapOf("at" to listOf(zoned, Instant.parse("2026-04-23T05:07:53.5Z"))))

        assertEquals(listOf("2040-01-01T00:00:00Z", "2026-04-23T05:07:53.500Z"), out["at"])
    }
}
