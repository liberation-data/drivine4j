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