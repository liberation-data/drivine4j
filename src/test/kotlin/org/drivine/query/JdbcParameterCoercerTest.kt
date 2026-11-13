package org.drivine.query

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.ValueLayout
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JdbcParameterCoercerTest {

    private val mapper = ObjectMapper()

    private fun coerced(value: Any?): Any? = JdbcParameterCoercer.coerce(mapOf("v" to value))["v"]

    @Test
    fun `a zoned date time is bound as its offset date time, which JDBC knows`() {
        val zoned = ZonedDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneId.of("Europe/Paris"))
        assertEquals(OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(1)), coerced(zoned))
    }

    /** The JSON text [coerced] wrote, read back as a tree, so that it is compared whatever order Jackson writes keys in. */
    private fun tree(json: Any?): Any? = json?.let { mapper.readTree(it as String) }

    /** The tag the store layout holds a date time of the canonical text [v] as. */
    private fun dateTime(v: String): Map<String, String> = ValueLayout.tagMap(Temporal.DATE_TIME, v)

    @Test
    fun `a list of maps is JSON text, with the instants inside it as the store layout's temporal tags`() {
        val value = listOf(mapOf("at" to Instant.parse("2024-03-01T12:00:00Z")), mapOf("at" to Instant.parse("2024-03-02T00:00:00.5Z")))
        val expected = listOf(mapOf("at" to dateTime("2024-03-01T12:00:00Z")), mapOf("at" to dateTime("2024-03-02T00:00:00.500Z")))
        assertEquals(mapper.valueToTree<JsonNode>(expected), tree(coerced(value)))
    }

    @Test
    fun `a set is coerced as a list is, so its instants are temporal tags and not Jackson's numbers`() {
        assertEquals(mapper.valueToTree<JsonNode>(listOf(dateTime("2024-03-01T12:00:00Z"))), tree(coerced(setOf(Instant.parse("2024-03-01T12:00:00Z")))))
        // A zoned date time inside is written as its offset form, without the zone Jackson refuses to read back.
        assertEquals(
            mapper.valueToTree<JsonNode>(listOf(dateTime("2024-03-01T12:00:05+01:00"))),
            tree(coerced(linkedSetOf(ZonedDateTime.parse("2024-03-01T12:00:05+01:00[Europe/Paris]")))),
        )
    }

    @Test
    fun `a map's key that begins with a dollar is written with one more, so it is never taken for a tag's`() {
        assertEquals("""{"${'$'}${'$'}t":"date","v":1}""", coerced(mapOf("${'$'}t" to "date", "v" to 1)))
        // At any depth, in a list in a map, and one that begins with two is written with three.
        assertEquals(
            """{"a":[{"${'$'}${'$'}k":{"${'$'}${'$'}${'$'}j":1}}]}""",
            coerced(mapOf("a" to listOf(mapOf("${'$'}k" to mapOf("${'$'}${'$'}j" to 1))))),
        )
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
