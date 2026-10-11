package org.drivine.query

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JdbcParameterCoercerTest {

    private fun coerced(value: Any?): Any? = JdbcParameterCoercer.coerce(mapOf("v" to value))["v"]

    @Test
    fun `a zoned date time is bound as its offset date time, which JDBC knows`() {
        val zoned = ZonedDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"))
        assertEquals(OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(1)), coerced(zoned))
    }

    @Test
    fun `a list of maps is JSON text, with the instants inside it as ISO strings`() {
        val value = listOf(mapOf("at" to Instant.parse("2024-03-01T12:00:00Z")), mapOf("at" to Instant.parse("2024-03-02T00:00:00.5Z")))
        assertEquals("""[{"at":"2024-03-01T12:00:00Z"},{"at":"2024-03-02T00:00:00.500Z"}]""", coerced(value))
    }

    @Test
    fun `a set is coerced as a list is, so its instants are ISO strings and not Jackson's numbers`() {
        assertEquals("""["2024-03-01T12:00:00Z"]""", coerced(setOf(Instant.parse("2024-03-01T12:00:00Z"))))
        // A zoned date time inside is written as its offset form, without the zone Jackson refuses to read back.
        assertEquals("""["2024-03-01T12:00:05+01:00"]""", coerced(linkedSetOf(ZonedDateTime.parse("2024-03-01T12:00:05+01:00[Europe/Paris]"))))
    }

    @Test
    fun `a map keeps its nulls, so a property can be unset by it`() {
        assertEquals("""{"a":null,"b":{"c":null,"d":1}}""", coerced(mapOf("a" to null, "b" to mapOf("c" to null, "d" to 1))))
    }

    @Test
    fun `what the driver binds as it is passes through untouched`() {
        val floats = floatArrayOf(1f, 2f)
        val bytes = byteArrayOf(1, 2)
        assertSame(floats, coerced(floats))
        assertSame(bytes, coerced(bytes))
        assertEquals("text", coerced("text"))
        assertEquals(42L, coerced(42L))
        assertEquals(null, coerced(null))
    }

    @Test
    fun `an embedding as a list of floats is the JSON text of an Oracle vector`() {
        assertEquals("[1.0,2.0]", coerced(listOf(1f, 2f)))
        assertEquals("[0.5,-1.25]", coerced(listOf(0.5, -1.25)))
    }
}
