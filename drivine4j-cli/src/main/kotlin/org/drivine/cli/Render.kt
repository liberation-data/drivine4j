package org.drivine.cli

import org.drivine.query.GraphNode
import org.drivine.query.GraphPath
import org.drivine.query.GraphRelationship
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.TemporalAccessor

/** Prints a statement's [Result] in one of the contract's formats ("Output"). Every line ends in `\n`, on Windows too. */
fun interface Renderer {
    fun render(result: Result, out: Appendable)

    companion object {
        fun of(format: Format): Renderer = when (format) {
            // auto is table on a terminal; until table is here, it is plain, as it is where stdout isn't a terminal.
            Format.AUTO, Format.PLAIN -> PlainRenderer
            Format.JSON -> JsonRenderer
        }
    }
}

/**
 * `json`: one compact line per statement, `{"columns":[...],"rows":[[...],...]}`, with values written as the Cypher
 * spec's files write them, so a program reads a result as the spec's cases are read. A float keeps its fraction, as
 * Java's `Double.toString` writes it (`2.0`, `1.0E10`), where a JSON writer would make `2` of it, a whole number; NaN and
 * the infinities, which JSON has no number for, are `{"$float":"NaN"}` and the others. A whole number is written exactly,
 * past 2^53 too. A text is escaped only where JSON requires it. A node is `{"$t":"node","labels":[…],"properties":{…}}`,
 * a relationship `{"$t":"relationship","type":…,"properties":{…}}` and a path
 * `{"$t":"path","nodes":[…],"relationships":[…],"forward":[…]}`, where `forward[i]` says whether `relationships[i]` goes
 * from `nodes[i]` to `nodes[i + 1]`, all with no store's ids (liberation-data/drivine-cypher-spec#128). `$t` is the kind,
 * as the spec's typed output names it, so that a program tells a node from a map: a map of the user's own that has a
 * `$t` key is written inside one, `{"$t":"map","v":{…}}`, so it can't pass for a node.
 */
object JsonRenderer : Renderer {

    override fun render(result: Result, out: Appendable) {
        out.append("{\"columns\":")
        write(result.columns, out)
        out.append(",\"rows\":")
        write(result.rows, out)
        out.append("}\n")
    }

    fun write(value: Any?, out: Appendable) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Double -> float(value, value.toString(), out)
            is Float -> float(value.toDouble(), value.toString(), out)
            is Long, is Int, is Short, is Byte, is BigInteger -> out.append(value.toString())
            is BigDecimal -> out.append(value.toString())
            is CharSequence -> string(value, out)
            is GraphNode -> tagged("node", listOf("labels" to value.labels, "properties" to value.properties), out)
            is GraphRelationship -> tagged("relationship", listOf("type" to value.type, "properties" to value.properties), out)
            is GraphPath -> tagged(
                "path", listOf("nodes" to value.nodes, "relationships" to value.relationships, "forward" to value.forward), out,
            )
            // Escaped by the keys as they are written, so that a key that isn't a String, but is written as $t, escapes it too.
            is Map<*, *> -> if (value.keys.any { it.toString() == TAG }) tagged("map", listOf("v" to Untagged(value)), out) else entries(value, out)
            is Untagged -> entries(value.map, out)
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out)
            // A temporal value is its ISO 8601 text, which is what its toString is.
            is TemporalAccessor -> string(value.toString(), out)
            else -> string(value.toString(), out)
        }
    }

    /** The key that names a value's kind, as the spec's typed output names it. */
    private const val TAG = "\$t"

    /** A map of the user's own that has a [TAG] key, written as it is inside the `{"$t":"map","v":…}` that holds it. */
    private class Untagged(val map: Map<*, *>)

    /** An object whose first key is [TAG], naming its [kind], then [entries]. */
    private fun tagged(kind: String, entries: List<Pair<String, Any?>>, out: Appendable) =
        entries(listOf(TAG to kind) + entries, out)

    /** An object of [map]'s entries, each key written as its text. */
    private fun entries(map: Map<*, *>, out: Appendable) = entries(map.entries.map { (k, v) -> k.toString() to v }, out)

    private fun entries(entries: List<Pair<String, Any?>>, out: Appendable) {
        out.append('{')
        entries.forEachIndexed { i, (key, item) ->
            if (i > 0) out.append(',')
            string(key, out)
            out.append(':')
            write(item, out)
        }
        out.append('}')
    }

    private fun float(value: Double, text: String, out: Appendable) {
        when {
            value.isNaN() -> out.append("{\"\$float\":\"NaN\"}")
            value == Double.POSITIVE_INFINITY -> out.append("{\"\$float\":\"Infinity\"}")
            value == Double.NEGATIVE_INFINITY -> out.append("{\"\$float\":\"-Infinity\"}")
            else -> out.append(text)
        }
    }

    /** [text] as a JSON string: `"`, `\` and the control characters escaped, the short forms where JSON has them. */
    fun string(text: CharSequence, out: Appendable) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}

/**
 * `plain`: each result's columns, `, ` between them, then a line per row, with each value printed as cypher-shell
 * prints it, so what a script reads from cypher-shell it reads from drivine. A result with no rows prints nothing.
 */
object PlainRenderer : Renderer {

    override fun render(result: Result, out: Appendable) {
        if (result.rows.isEmpty()) return
        out.append(result.columns.joinToString(", ")).append('\n')
        result.rows.forEach { row ->
            row.forEachIndexed { i, value ->
                if (i > 0) out.append(", ")
                write(value, out)
            }
            out.append('\n')
        }
    }

    /**
     * [value] as cypher-shell prints it: `NULL`, `TRUE`, a float as `Double.toString` writes it, a text in `"…"` with only
     * its `"` escaped, `[1, "b"]`, `{k: 2}`, a node as `(:A:B {k: 2})`, a relationship as `[:R {k: 2}]`, a path as
     * `(:A)-[:R]->(:B)<-[:S]-(:C)`, along or against each relationship's direction, and a temporal value as its ISO 8601
     * text, unquoted. A node or a relationship with no properties prints none: `(:A)`, `[:R]`, and a node with no labels
     * prints its properties alone: `({k: 2})`. [quote] is a text's quote, `"` but where `:params` gives `'`: each it
     * holds is escaped.
     */
    fun write(value: Any?, out: Appendable, quote: Char = '"') {
        when (value) {
            null -> out.append("NULL")
            is Boolean -> out.append(if (value) "TRUE" else "FALSE")
            is CharSequence -> out.append(quote).append(value.toString().replace(quote.toString(), "\\$quote")).append(quote)
            is GraphNode -> {
                out.append('(')
                value.labels.forEach { out.append(':').append(it) }
                // As cypher-shell joins a node's labels and properties: a space between them only where it has both.
                if (value.labels.isNotEmpty()) properties(value.properties, out, quote) else if (value.properties.isNotEmpty()) map(value.properties, out, quote)
                out.append(')')
            }
            is GraphRelationship -> {
                out.append("[:").append(value.type)
                properties(value.properties, out, quote)
                out.append(']')
            }
            is GraphPath -> {
                write(value.nodes.first(), out, quote)
                value.relationships.forEachIndexed { i, relationship ->
                    out.append(if (value.forward[i]) "-" else "<-")
                    write(relationship, out, quote)
                    out.append(if (value.forward[i]) "->" else "-")
                    write(value.nodes[i + 1], out, quote)
                }
            }
            is Map<*, *> -> map(value, out, quote)
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(", ")
                    write(item, out, quote)
                }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out, quote)
            // Double, Float, the whole numbers and a temporal value print as their toString does.
            else -> out.append(value.toString())
        }
    }

    private fun map(value: Map<*, *>, out: Appendable, quote: Char) {
        out.append('{')
        value.entries.forEachIndexed { i, (key, item) ->
            if (i > 0) out.append(", ")
            out.append(key.toString()).append(": ")
            write(item, out, quote)
        }
        out.append('}')
    }

    /** A node's or a relationship's [properties], after a space, as a map prints; nothing where it has none. */
    private fun properties(properties: Map<String, Any?>, out: Appendable, quote: Char) {
        if (properties.isEmpty()) return
        out.append(' ')
        map(properties, out, quote)
    }

    /**
     * The parameters as `:params` prints them: `{`, then each `name: value` on a line of its own, indented two spaces,
     * with `,` after each but the last, then `}`; a text in `'…'`. With none, `{}`.
     */
    fun parameters(parameters: Map<String, Any?>, out: Appendable) {
        if (parameters.isEmpty()) {
            out.append("{}\n")
            return
        }
        out.append("{\n")
        parameters.entries.forEachIndexed { i, (name, value) ->
            out.append("  ").append(name).append(": ")
            write(value, out, '\'')
            out.append(if (i < parameters.size - 1) ",\n" else "\n")
        }
        out.append("}\n")
    }
}
