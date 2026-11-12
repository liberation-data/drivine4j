package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.connection.JacksonEncoder
import org.drivine.cypher.json.CypherJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import org.drivine.cypher.store.SqliteCypherValues as Shared

/**
 * [SqliteCypherValues] writes NaN and the infinities as the compiler's own write side ([Shared]) does: the compiler
 * writes them into a statement, and drivine4j's functions read them back and write their results, each over its own
 * tree, so the two are pinned to one text here.
 */
class SqliteCypherValuesTest {

    private val json = ObjectMapper()

    @Test
    fun `NaN is the compiler's stand-in for it`() {
        assertEquals(CypherJson.writeValueAsString(Shared.NAN), json.writeValueAsString(SqliteCypherValues.NAN))
    }

    @Test
    fun `a value with NaN and infinities in it, at any depth, is written as the compiler writes it`() {
        val value = mapOf(
            "nan" to Double.NaN,
            "up" to Double.POSITIVE_INFINITY,
            "list" to listOf(1, Double.NEGATIVE_INFINITY, mapOf("deep" to Float.NaN), 0.5),
            "text" to "NaN",
        )
        val node = json.valueToTree<JsonNode>(value)
        assertEquals(Shared.write(JacksonEncoder.tree(node)), SqliteCypherValues.write(node))
    }
}
