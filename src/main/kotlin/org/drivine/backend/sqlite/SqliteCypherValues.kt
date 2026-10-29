package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.util.RawValue
import java.math.BigDecimal
import kotlin.math.sign

/**
 * Cypher's values as a compiled statement holds them on SQLite, the JSON text of each, and what Cypher does
 * with them that SQLite's JSON functions do not: compare them, and add them. A text a function is given
 * that is SQL's null, or JSON's, is no value.
 *
 * An infinity is the number SQLite writes for one, `9.0e+999`, which reads as the double it is. NaN, which SQLite's JSON holds
 * as no number, is [NAN], as a statement given NaN writes it: a number equal to nothing and ordered with nothing.
 */
internal object SqliteCypherValues {

    private val json = ObjectMapper()

    /** The JSON text [text] read, or null where it is null or JSON's null. */
    fun read(text: String?): JsonNode? = text?.let(json::readTree)?.takeUnless { it.isNull || it.isMissingNode }

    /** [node] as JSON text: an infinity as SQLite writes one, and NaN as [NAN]. */
    fun write(node: JsonNode): String = json.writeValueAsString(nonFinite(node))

    /** NaN, as a statement given one writes it, where SQLite's JSON holds no NaN: see [SqliteCypherCompiler][org.drivine.cypher.sql.SqliteCypherCompiler]. */
    val NAN: JsonNode = JsonNodeFactory.instance.objectNode().put("\$numberDouble", "NaN")

    /** Whether [node] is NaN, as [NAN] writes it. */
    fun isNaN(node: JsonNode): Boolean = node == NAN

    /** [node] with each NaN in it, at any depth, [NAN], and each infinity the number SQLite writes for one. */
    fun nonFinite(node: JsonNode): JsonNode = when {
        node.isFloatingPointNumber && node.doubleValue().isNaN() -> NAN
        node.isFloatingPointNumber && node.doubleValue().isInfinite() ->
            JsonNodeFactory.instance.rawValueNode(RawValue(if (node.doubleValue() > 0) INFINITY else "-$INFINITY"))
        node is ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, nonFinite(value)) } }
        node is ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(nonFinite(it)) } }
        else -> node
    }

    /** Whether [node] is a number, NaN among them. */
    private fun isNumeric(node: JsonNode) = node.isNumber || isNaN(node)

    /** Whether [node], a number, is NaN or an infinity, which [number] makes no decimal of. */
    private fun nonFiniteNumber(node: JsonNode) = isNaN(node) || (node.isFloatingPointNumber && !node.doubleValue().isFinite())

    /** [node], a number, as a double: NaN for [NAN]. */
    private fun double(node: JsonNode) = if (isNaN(node)) Double.NaN else node.doubleValue()

    /**
     * Whether [a] and [b] are equal as Cypher compares them: a number equal to a number of the same value
     * whatever its kind (`1` and `1.0`), a list to a list of equal items in the same order, a map to a map of
     * the same keys with equal values in any order, and nothing to a value of another kind.
     */
    fun equal(a: JsonNode, b: JsonNode): Boolean = when {
        isNaN(a) || isNaN(b) -> false
        a.isNumber && b.isNumber -> if (nonFiniteNumber(a) || nonFiniteNumber(b)) a.doubleValue() == b.doubleValue() else number(a).compareTo(number(b)) == 0
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
            isNaN(a) || isNaN(b) -> false
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
        isNumeric(a) && isNumeric(b) ->
            if (a.isIntegralNumber && b.isIntegralNumber) {
                runCatching { JsonNodeFactory.instance.numberNode(Math.addExact(a.longValue(), b.longValue())) }
                    .getOrElse { JsonNodeFactory.instance.numberNode(a.bigIntegerValue() + b.bigIntegerValue()) }
            } else {
                nonFinite(JsonNodeFactory.instance.numberNode(double(a) + double(b)))
            }
        a.isArray -> JsonNodeFactory.instance.arrayNode().addAll(a.toList()).apply { if (b.isArray) addAll(b.toList()) else add(b) }
        b.isArray -> JsonNodeFactory.instance.arrayNode().add(a).addAll(b.toList())
        (a.isTextual || isNumeric(a)) && (b.isTextual || isNumeric(b)) -> JsonNodeFactory.instance.textNode(text(a) + text(b))
        else -> null
    }

    /** [node] with each map in it, at any depth, with its keys in order. */
    fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> JsonNodeFactory.instance.objectNode().apply { node.properties().sortedBy { it.key }.forEach { (key, value) -> set<JsonNode>(key, canonical(value)) } }
        node.isArray -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(canonical(it)) } }
        else -> node
    }

    /** The text [node], a text or a number, is put together with another as: NaN and an infinity as Java writes them. */
    private fun text(node: JsonNode) = if (isNaN(node)) "NaN" else node.asText()

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
        a.isNull || b.isNull || isNaN(a) || isNaN(b) -> null
        a.isArray && b.isArray -> listOrder(a, b)
        a.isObject && b.isObject -> 0.takeIf { compare(a, b) == true }
        a.isNumber && b.isNumber -> if (nonFiniteNumber(a) || nonFiniteNumber(b)) a.doubleValue().compareTo(b.doubleValue()).sign else number(a).compareTo(number(b)).sign
        // By code points, as Cypher orders texts: Java's `compareTo` orders UTF-16's units.
        a.isTextual && b.isTextual -> SqlitePlanWriter.CODE_POINTS.compare(a.textValue(), b.textValue()).sign
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

    /** The number SQLite writes for an infinity, which it reads as one. */
    private const val INFINITY = "9e999"
}
