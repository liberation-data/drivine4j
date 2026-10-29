package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.JsonNode
import org.sqlite.Function

/**
 * `drivine_sort_key(json)`: a list as text that orders, as SQLite orders text, as Cypher orders lists: item by item,
 * a list that begins another first, and items of different kinds as Cypher orders kinds (a map, a list, a text, a truth
 * value, a number, then null last). Each item is its kind's rank, then itself: a number by the bits of its double, so
 * their text orders as the numbers do, NaN after them all; a text up to a mark below any character; a list its own
 * items, then its end, which comes before any item. Null where the value is null; a value that is no list, its JSON text.
 */
internal class SqliteSortKeyFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        val node = SqliteCypherValues.read(text) ?: return result()
        if (!node.isArray) return result(text)
        result(StringBuilder().also { list(node, it) }.toString())
    }

    private fun list(node: JsonNode, out: StringBuilder) {
        node.forEach { item(it, out) }
        out.append(END)
    }

    private fun item(node: JsonNode, out: StringBuilder) {
        when {
            // NaN, as a statement given it writes it, is a number, whose bits come after every other's, an infinity's too.
            SqliteCypherValues.isNaN(node) -> out.append('5').append(number(Double.NaN))
            node.isObject -> out.append('1').also { text(SqliteCypherValues.write(SqliteCypherValues.canonical(node)), out) }
            node.isArray -> out.append('2').also { list(node, out) }
            node.isTextual -> out.append('3').also { text(node.textValue(), out) }
            node.isBoolean -> out.append('4').append(if (node.booleanValue()) '1' else '0')
            node.isNumber -> out.append('5').append(number(node.doubleValue()))
            else -> out.append('6')
        }
    }

    /** The text, its two lowest characters escaped, then a mark below any character it holds. */
    private fun text(text: String, out: StringBuilder) {
        text.forEach { c ->
            when (c) {
                '\u0001' -> out.append("\u0002\u0002")
                '\u0002' -> out.append("\u0002\u0003")
                else -> out.append(c)
            }
        }
        out.append('\u0001')
    }

    /** The double's bits as 16 hexadecimal digits that order as the doubles do: a positive one's sign set, a negative one's bits flipped. */
    private fun number(value: Double): String {
        val bits = java.lang.Double.doubleToLongBits(if (value == 0.0) 0.0 else value)
        val ordered = if (bits < 0) bits.inv() else bits or Long.MIN_VALUE
        return "%016X".format(ordered)
    }

    private companion object {
        /** The end of a list, which comes before any item's rank. */
        const val END = '0'
    }
}
