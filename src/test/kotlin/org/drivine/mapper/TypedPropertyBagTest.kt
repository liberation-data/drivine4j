package org.drivine.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import sample.propertybag.BaggedNode
import sample.propertybag.TypedBagNode
import java.time.Instant

/**
 * Value typing for `@PropertyBag` (0.0.60).
 *
 * The read path reassembles a bag into a `Map<String, Any?>` and hands it to
 * `objectMapper.convertValue(data, targetType)`. Jackson resolves the *declared* generic value type
 * of the bag field, so a typed bag coerces per entry — which is what confines the documented read
 * asymmetry to `Map<String, Any?>` alone. These tests pin that boundary, driving the mapper with the
 * widened values a graph driver actually returns (`Long` for a written `Int`).
 */
class TypedPropertyBagTest {

    private val mapper = Neo4jObjectMapper.instance

    @Test
    fun `a typed Int bag coerces the driver's Long back to Int`() {
        // What the driver hands back: every integer is 64-bit
        val fromDriver = mapOf("id" to "n1", "scores" to mapOf("relevance" to 3L, "rank" to 42L))

        val node = mapper.convertValue(fromDriver, TypedBagNode::class.java)

        assertEquals(3, node.scores["relevance"])
        assertInstanceOf(Integer::class.java, node.scores["relevance"])
    }

    @Test
    fun `typed bags coerce Double, String, Boolean and Instant values`() {
        val moment = Instant.parse("2026-07-24T10:15:30Z")
        val fromDriver = mapOf(
            "id" to "n1",
            "ratios" to mapOf("confidence" to 0.75),
            "labels" to mapOf("source" to "wiki"),
            "flags" to mapOf("published" to true),
            "timestamps" to mapOf("indexedAt" to moment.toString()),
        )

        val node = mapper.convertValue(fromDriver, TypedBagNode::class.java)

        assertEquals(0.75, node.ratios["confidence"])
        assertEquals("wiki", node.labels["source"])
        assertEquals(true, node.flags["published"])
        assertEquals(moment, node.timestamps["indexedAt"])
        assertInstanceOf(Instant::class.java, node.timestamps["indexedAt"])
    }

    @Test
    fun `a typed Int bag narrows even when the driver returns a wider Long`() {
        // Long.MAX would not fit; ordinary values round-trip exactly
        val node = mapper.convertValue(
            mapOf("id" to "n1", "scores" to mapOf("a" to 2_147_483_647L)),
            TypedBagNode::class.java,
        )
        assertEquals(Int.MAX_VALUE, node.scores["a"])
    }

    @Test
    fun `an untyped Map of Any keeps the driver's widened type - the documented asymmetry`() {
        val node = mapper.convertValue(
            mapOf("id" to "n1", "title" to "T", "metadata" to mapOf("score" to 3L)),
            BaggedNode::class.java,
        )

        // Untyped bag: the value stays as the driver produced it. This is inherent — the graph
        // stores one 64-bit integer type, and an `Any?` declaration gives Jackson nothing to
        // narrow to. Declare a typed bag when exact width matters.
        assertInstanceOf(java.lang.Long::class.java, node.metadata["score"])
        assertEquals(3L, node.metadata["score"])
    }

    @Test
    fun `an empty typed bag reads back empty, not null`() {
        val node = mapper.convertValue(mapOf("id" to "n1"), TypedBagNode::class.java)
        assertEquals(emptyMap<String, Int>(), node.scores)
        assertEquals(emptyMap<String, Instant>(), node.timestamps)
    }
}
