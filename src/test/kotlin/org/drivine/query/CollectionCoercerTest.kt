package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CollectionCoercerTest {

    @Test
    fun `a set is coerced to a list in its own order`() {
        val out = CollectionCoercer.coerce(mapOf("ids" to linkedSetOf(3, 1, 2)))

        assertEquals(listOf(3, 1, 2), out["ids"])
    }

    @Test
    fun `a set nested in a list or a map is coerced too`() {
        val out = CollectionCoercer.coerce(
            mapOf("rows" to listOf(mapOf("tags" to setOf("a", "b"))))
        )

        assertEquals(listOf(mapOf("tags" to listOf("a", "b"))), out["rows"])
    }

    @Test
    fun `scalars and nulls are left alone`() {
        val parameters = mapOf("name" to "Ada", "age" to 36, "nickname" to null)

        assertEquals(parameters, CollectionCoercer.coerce(parameters))
    }
}
