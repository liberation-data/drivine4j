package org.drivine.cli

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
 * past 2^53 too. A text is escaped only where JSON requires it.
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
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (key, item) ->
                    if (i > 0) out.append(',')
                    string(key.toString(), out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
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
     * its `"` escaped, `[1, "b"]`, `{k: 2}`, and a temporal value as its ISO 8601 text, unquoted.
     */
    fun write(value: Any?, out: Appendable) {
        when (value) {
            null -> out.append("NULL")
            is Boolean -> out.append(if (value) "TRUE" else "FALSE")
            is CharSequence -> out.append('"').append(value.toString().replace("\"", "\\\"")).append('"')
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (key, item) ->
                    if (i > 0) out.append(", ")
                    out.append(key.toString()).append(": ")
                    write(item, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(", ")
                    write(item, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out)
            // Double, Float, the whole numbers and a temporal value print as their toString does.
            else -> out.append(value.toString())
        }
    }
}
