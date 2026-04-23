package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DollarEscaperTest {

    @Test
    fun `dollar in string value is escaped with backslash`() {
        val out = DollarEscaper.coerce(mapOf("v" to "price: \$100"))

        assertEquals("price: \\\$100", out["v"])
    }

    @Test
    fun `kotlin template syntax in string value is escaped`() {
        val out = DollarEscaper.coerce(mapOf("code" to "println(\"hello \${name}\")"))

        assertEquals("println(\"hello \\\${name}\")", out["code"])
    }

    @Test
    fun `multiple dollars in one string are all escaped`() {
        val out = DollarEscaper.coerce(mapOf("v" to "\$a \$b \$c"))

        assertEquals("\\\$a \\\$b \\\$c", out["v"])
    }

    @Test
    fun `string without dollar is unchanged`() {
        val out = DollarEscaper.coerce(mapOf("v" to "no money here"))

        assertEquals("no money here", out["v"])
    }

    @Test
    fun `non-string values pass through unchanged`() {
        val params = mapOf(
            "n" to 42,
            "b" to true,
            "nothing" to null
        )
        val out = DollarEscaper.coerce(params)

        assertEquals(params, out)
    }

    @Test
    fun `dollars in list elements are escaped recursively`() {
        val out = DollarEscaper.coerce(mapOf("tags" to listOf("\$foo", "bar", "\$baz")))

        assertEquals(listOf("\\\$foo", "bar", "\\\$baz"), out["tags"])
    }

    @Test
    fun `dollars in nested map values are escaped recursively`() {
        val out = DollarEscaper.coerce(mapOf(
            "props" to mapOf("name" to "ada", "note" to "owes \$5")
        ))

        val props = out["props"] as Map<*, *>
        assertEquals("ada", props["name"])
        assertEquals("owes \\\$5", props["note"])
    }

    @Test
    fun `empty map passes through`() {
        assertEquals(emptyMap<String, Any?>(), DollarEscaper.coerce(emptyMap()))
    }
}