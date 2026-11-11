package org.drivine.connection

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.TextNode
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [JacksonEncoder] gives the compiler each value as drivine4j's Jackson mapper writes it: its tree, written by the
 * compiler, is the text Jackson writes of Jackson's own tree, and its text is the mapper's.
 */
class JacksonEncoderTest {

    private enum class Colour { RED }

    private data class Pet(val name: String, val born: LocalDateTime)

    private val values: List<Any> = listOf(
        "Ada", true, 7, Long.MAX_VALUE, 1.5, 0.1f, Double.NaN, Double.NEGATIVE_INFINITY,
        BigInteger("123456789012345678901234567890"), BigInteger("-5"),
        BigDecimal("10.500"), BigDecimal("1E+3"), BigDecimal("0.000001"), BigDecimal("-2.50E-12"), BigDecimal("1E+400"),
        byteArrayOf(1, 2, 3, -1),
        Colour.RED, LocalDateTime.of(2024, 1, 2, 3, 4, 5, 6_000), ZonedDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)),
        Date(1_700_000_000_000), Duration.ofNanos(1_500_000_001),
        Pet("Rex", LocalDateTime.of(2020, 5, 6, 7, 8)),
        listOf(1, "two", null, mapOf("three" to BigDecimal("3.0"))),
        mapOf("nested" to mapOf("pet" to Pet("Tib", LocalDateTime.of(2021, 1, 1, 0, 0))), "none" to null),
    )

    @Test
    fun `a value's tree is written as Jackson writes its own tree of it`() {
        for (value in values) {
            val jackson = Neo4jObjectMapper.instance.writeValueAsString(Neo4jObjectMapper.instance.valueToTree(value))
            assertEquals(jackson, CypherJson.writeValueAsString(JacksonEncoder.tree(value)), "$value")
        }
    }

    @Test
    fun `a value's text is the mapper's`() {
        for (value in values + listOf<Any?>(null)) {
            assertEquals(Neo4jObjectMapper.instance.writeValueAsString(value), JacksonEncoder.text(value), "$value")
        }
    }

    @Test
    fun `a value that holds a tree of the compiler's, or none, is left to the compiler`() {
        val tree = TextNode("x")
        for (value in listOf(tree, listOf(1, tree), mapOf("a" to listOf(tree)), arrayOf<Any>(tree), mapOf(tree to 1))) {
            assertNull(JacksonEncoder.tree(value), "$value")
            assertNull(JacksonEncoder.text(value), "$value")
        }
        assertNull(JacksonEncoder.tree(null))
    }

    /** Every class [value] holds, at any depth, itself included. */
    private fun classes(value: Any?): List<Class<*>> = when (value) {
        null -> emptyList()
        is Map<*, *> -> listOf(value.javaClass) + value.flatMap { (k, v) -> classes(k) + classes(v) }
        is Collection<*> -> listOf(value.javaClass) + value.flatMap(::classes)
        else -> listOf(value.javaClass)
    }

    @Test
    fun `Kotlin's own collections reach Jackson as plain java_util ones, in the same order, and are written alike`() {
        val kotlins = listOf<Any>(
            emptyList<Any>(), emptySet<Any>(), emptyMap<String, Any>(), listOf("a"), setOf(2, 1), mapOf("b" to 1, "a" to 2),
            buildList { add(1); add(emptyList<Any>()) }, buildMap { put("l", emptyList<Any>()); put("m", emptyMap<String, Any>()) },
            listOf(1, 2).asReversed(), arrayOf<Any>(emptyList<Any>(), "x"), mapOf("labels" to emptyList<String>(), "props" to mapOf("k" to 1)),
            // A byte array is still its base64 text, a sorted or hashed map keeps its order, and a set's two arrays of the
            // same items stay two.
            listOf(byteArrayOf(1, 2, -1), emptyList<Any>()), java.util.TreeMap(mapOf("b" to emptyList<Any>(), "a" to 1)),
            hashMapOf("z" to 1, "a" to emptyList<Any>(), "m" to emptyMap<String, Any>()), linkedSetOf(arrayOf<Any>(1), arrayOf<Any>(1), emptyList<Any>()),
        )
        for (value in kotlins) {
            val plain = JacksonEncoder.plain(value, native = true)
            val kotlin = classes(plain).filter { it.name.startsWith("kotlin.") }
            assertEquals(emptyList(), kotlin, "$value gives Jackson Kotlin's own classes")
            // The copy, as a native image writes it, is written as the value is.
            val mapper = Neo4jObjectMapper.instance
            assertEquals(mapper.writeValueAsString(value), mapper.writeValueAsString(plain), "$value")
            assertEquals(mapper.writeValueAsString(mapper.valueToTree(value)), CypherJson.writeValueAsString(JacksonEncoder.tree(mapper.valueToTree(plain))), "$value")
            assertEquals(mapper.writeValueAsString(value), JacksonEncoder.text(value), "$value")
        }
        assertEquals(listOf("b", "a"), (JacksonEncoder.plain(linkedMapOf("b" to emptyList<Any>(), "a" to 2), native = true) as Map<*, *>).keys.toList())
        // A value that holds no Kotlin collection is itself, and on a JVM every value is.
        val java = arrayListOf<Any>(linkedMapOf("k" to arrayListOf(1)))
        assertSame(java, JacksonEncoder.plain(java, native = true))
        val kotlin = listOf(emptyList<Any>())
        assertSame(kotlin, JacksonEncoder.plain(kotlin))
        // A collection of the application's own class is left for Jackson to write as that class says.
        val own = object : AbstractList<Int>() {
            override val size = 1
            override fun get(index: Int) = 1
        }
        assertSame(own, JacksonEncoder.plain(own, native = true))
        // A value that holds none of Kotlin's own collections is itself, not a copy.
        val plain = mapOf("l" to listOf(1, 2), "a" to arrayOf<Any>(setOf(1, 2)), "m" to hashMapOf("k" to arrayListOf(1)))
        assertSame(plain, JacksonEncoder.plain(plain, native = true))
        // A POJO in Jackson's tree, as an application's serializer may write one, is written the same way.
        assertEquals("[[],{}]", CypherJson.writeValueAsString(JacksonEncoder.tree(com.fasterxml.jackson.databind.node.POJONode(listOf(emptyList<Any>(), emptyMap<String, Any>())))))
    }
}
