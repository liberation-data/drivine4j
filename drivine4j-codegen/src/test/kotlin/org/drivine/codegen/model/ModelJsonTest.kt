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
}
