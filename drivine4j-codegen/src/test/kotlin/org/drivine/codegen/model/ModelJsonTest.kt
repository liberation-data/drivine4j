package org.drivine.codegen.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModelJsonTest {

    @Test
    fun `writes the format's layout, leaving out a null and escaping what JSON asks`() {
        val json = ModelJson.write(
            linkedMapOf(
                "b" to "say \"hi\"\\\n\t\r\u0001",
                "a" to listOf(1, 2.5, true),
                "gone" to null,
                "empty" to linkedMapOf<String, Any?>("only" to null),
                "none" to emptyList<Any>(),
            ),
        )
        assertEquals(
            """
            {
              "b": "say \"hi\"\\\n\t\r\u0001",
              "a": [
                1,
                2.5,
                true
              ],
              "empty": {},
              "none": []
            }

            """.trimIndent(),
            json,
        )
    }

    @Test
    fun `reads back what it writes, the keys in their order`() {
        val value = linkedMapOf(
            "z" to "say \"hi\"\\\n\t\u0001 caf\u00e9",
            "a" to listOf(1, -2, 3_000_000_000L, 2.5, true, false, emptyList<Any>(), linkedMapOf<String, Any?>()),
            "m" to linkedMapOf("nested" to listOf(linkedMapOf("k" to "v"))),
        )
        val json = ModelJson.write(value)
        assertEquals(value, ModelJson.read(json))
        assertEquals(json, ModelJson.write(ModelJson.read(json)!!))
        assertEquals(listOf("z", "a", "m"), (ModelJson.read(json) as Map<*, *>).keys.toList())
        assertEquals("\u00e9", ModelJson.read("\"\\u00e9\""))
        assertEquals(null, ModelJson.read(" null "))
    }

    @Test
    fun `refuses what is not JSON`() {
        listOf("{", "[1,", "{\"a\" 1}", "tru", "1 2", "\"open").forEach { text ->
            kotlin.test.assertFailsWith<IllegalArgumentException>(text) { ModelJson.read(text) }
        }
    }
}
