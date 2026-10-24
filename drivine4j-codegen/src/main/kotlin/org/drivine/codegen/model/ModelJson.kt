package org.drivine.codegen.model

/**
 * Writes a model file's JSON the same way every time, as the format asks: two spaces of indentation,
 * keys in the order they were put, and a newline at the end. A value is a [Map] (its entries in
 * order, a null value left out), a [List], a [String], a [Boolean] or a [Number].
 *
 * Written by hand rather than with a JSON library, so that the processor needs nothing on its path
 * beyond KSP, and the layout is the format's rather than a library's.
 */
internal object ModelJson {

    fun write(value: Any): String = StringBuilder().also { append(it, value, "") }.append('\n').toString()

    private fun append(out: StringBuilder, value: Any?, indent: String) {
        when (value) {
            is Map<*, *> -> {
                val entries = value.entries.filter { it.value != null }
                if (entries.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                entries.forEachIndexed { i, (key, item) ->
                    out.append(indent).append("  ")
                    string(out, key as String)
                    out.append(": ")
                    append(out, item, "$indent  ")
                    out.append(if (i < entries.size - 1) ",\n" else "\n")
                }
                out.append(indent).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                value.forEachIndexed { i, item ->
                    out.append(indent).append("  ")
                    append(out, item, "$indent  ")
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(indent).append(']')
            }
            is String -> string(out, value)
            is Boolean, is Number -> out.append(value.toString())
            else -> throw IllegalArgumentException("A model file holds no ${value?.javaClass?.name}")
        }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        value.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
