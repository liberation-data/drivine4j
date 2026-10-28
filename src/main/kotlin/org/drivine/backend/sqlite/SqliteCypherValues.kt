package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import java.math.BigDecimal
import kotlin.math.sign

/**
 * Cypher's values as a compiled statement holds them on SQLite, the JSON text of each, and what Cypher does
 * with them that SQLite's JSON functions do not: compare them, and add them. A text a function is given
 * that is SQL's null, or JSON's, is no value.
 */
internal object SqliteCypherValues {

    private val json = ObjectMapper()

    /** The JSON text [text] read, or null where it is null or JSON's null. */
    fun read(text: String?): JsonNode? = text?.let(json::readTree)?.takeUnless { it.isNull || it.isMissingNode }

    /** [node] as JSON text. */
    fun write(node: JsonNode): String = json.writeValueAsString(node)

    /**
     * Whether [a] and [b] are equal as Cypher compares them: a number equal to a number of the same value
     * whatever its kind (`1` and `1.0`), a list to a list of equal items in the same order, a map to a map of
     * the same keys with equal values in any order, and nothing to a value of another kind.
     */
    fun equal(a: JsonNode, b: JsonNode): Boolean = when {
        a.isNumber && b.isNumber -> number(a).compareTo(number(b)) == 0
        a.isArray && b.isArray -> a.size() == b.size() && (0 until a.size()).all { equal(a[it], b[it]) }
        a.isObject && b.isObject -> a.size() == b.size() && a.properties().all { (key, value) -> b.has(key) && equal(value, b[key]) }
        else -> a.nodeType == b.nodeType && a == b
    }

    /**
     * Whether [a] and [b] are equal as Cypher compares values that may be or hold a null: null where either is null; a list
     * to a list, and a map to a map, item by item and key by key, false where a pair is false (lists of different lengths
     * and maps of different keys among them), else null where a pair is null, else true; any other two as [equal] compares them.
     */
    fun compare(a: JsonNode, b: JsonNode): Boolean? {
        fun all(pairs: List<Pair<JsonNode, JsonNode>>): Boolean? {
            var unknown = false
            for ((x, y) in pairs) when (compare(x, y)) {
                false -> return false
                null -> unknown = true
                true -> {}
            }
            return if (unknown) null else true
        }
        return when {
            a.isNull || b.isNull -> null
            a.isArray && b.isArray -> if (a.size() != b.size()) false else all((0 until a.size()).map { a[it] to b[it] })
            a.isObject && b.isObject ->
                if (a.size() != b.size() || a.properties().any { !b.has(it.key) }) false else all(a.properties().map { (key, value) -> value to b[key] })
            else -> equal(a, b)
        }
    }

    /** Whether the map [props] holds each entry of the map [map], with an equal value: a null among them is held only by a null. */
    fun contains(props: JsonNode, map: JsonNode): Boolean =
        props.isObject && map.isObject && map.properties().all { (key, value) -> props.has(key) && equal(props[key], value) }

    /**
     * `a + b`, as Cypher adds two values: numbers summed, a whole number where both are; lists joined, or an item put
     * at a list's end or start; a text and a text or a number written one after the other. Null for any other two.
     */
    fun add(a: JsonNode, b: JsonNode): JsonNode? = when {
        a.isNumber && b.isNumber ->
            if (a.isIntegralNumber && b.isIntegralNumber) {
                runCatching { JsonNodeFactory.instance.numberNode(Math.addExact(a.longValue(), b.longValue())) }
                    .getOrElse { JsonNodeFactory.instance.numberNode(a.bigIntegerValue() + b.bigIntegerValue()) }
            } else {
                JsonNodeFactory.instance.numberNode(a.doubleValue() + b.doubleValue())
            }
        a.isArray -> JsonNodeFactory.instance.arrayNode().addAll(a.toList()).apply { if (b.isArray) addAll(b.toList()) else add(b) }
        b.isArray -> JsonNodeFactory.instance.arrayNode().add(a).addAll(b.toList())
        (a.isTextual || a.isNumber) && (b.isTextual || b.isNumber) -> JsonNodeFactory.instance.textNode(a.asText() + b.asText())
        else -> null
    }

    /** [node] with each map in it, at any depth, with its keys in order. */
    fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> JsonNodeFactory.instance.objectNode().apply { node.properties().sortedBy { it.key }.forEach { (key, value) -> set<JsonNode>(key, canonical(value)) } }
        node.isArray -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(canonical(it)) } }
        else -> node
    }

    /** JSON's null, which a list holds where an item is null. */
    val NULL: JsonNode = NullNode.instance

    private fun number(node: JsonNode): BigDecimal = if (node.isIntegralNumber) node.bigIntegerValue().toBigDecimal() else BigDecimal(node.doubleValue())

    /**
     * Where [a] comes beside [b] as Cypher orders two values in a comparison (`<`, `>=` …): -1 before, 0 equal, 1 after. A list
     * beside a list item by item, the first pair that is not equal deciding, and a list that the other starts with first; a number
     * beside a number, a text beside a text by its code points, and a truth value beside a truth value. Null where they are not
     * ordered: a null, values of two kinds, or two maps that differ, which Cypher orders and the compiler does not yet.
     */
    fun order(a: JsonNode, b: JsonNode): Int? = when {
        a.isNull || b.isNull -> null
        a.isArray && b.isArray -> listOrder(a, b)
        a.isObject && b.isObject -> 0.takeIf { a.size() == b.size() && a.properties().all { (key, value) -> b.has(key) && order(value, b[key]) == 0 } }
        a.isNumber && b.isNumber -> number(a).compareTo(number(b)).sign
        a.isTextual && b.isTextual -> codePoints(a.textValue(), b.textValue())
        a.isBoolean && b.isBoolean -> a.booleanValue().compareTo(b.booleanValue()).sign
        else -> null
    }

    /** [order] of the lists [a] and [b]: by their first pair that is not equal, which is null where it is not ordered; else by their sizes. */
    private fun listOrder(a: JsonNode, b: JsonNode): Int? {
        for (i in 0 until minOf(a.size(), b.size())) {
            val pair = order(a[i], b[i])
            if (pair != 0) return pair
        }
        return a.size().compareTo(b.size()).sign
    }

    /** Where the text [a] comes beside [b] by their code points, as Cypher orders texts: Java's `compareTo` orders UTF-16's units. */
    private fun codePoints(a: String, b: String): Int {
        val (x, y) = a.codePoints().toArray() to b.codePoints().toArray()
        return (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }?.let { x[it].compareTo(y[it]).sign } ?: x.size.compareTo(y.size).sign
    }
}
