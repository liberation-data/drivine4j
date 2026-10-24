package org.drivine.codegen.model

/**
 * Writes a model file's JSON the same way every time, as the format asks: two spaces of indentation,
 * keys in the order they were put, and a newline at the end. A value is a [Map] (its entries in
 * order, a null value left out), a [List], a [String], a [Boolean] or a [Number].
 *
 * It reads one back too, into the same shapes, so that the generator can keep the entries the
 * previous file had and the code no longer declares.
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

    /** Reads [text] into maps that keep their keys' order, lists, strings, booleans, numbers and nulls. */
    fun read(text: String): Any? = Reader(text).run { value().also { space(); require(at == text.length) { "Unexpected text at $at" } } }

    private class Reader(val text: String) {
        var at = 0

        fun value(): Any? {
            space()
            return when (val c = text.getOrNull(at)) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c != null && (c == '-' || c.isDigit())) number() else throw IllegalArgumentException("Unexpected '$c' at $at")
            }
        }

        fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            space()
            if (text.getOrNull(at) == '}') return map.also { at++ }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                map[key] = value()
                space()
                if (text.getOrNull(at) == ',') at++ else return map.also { expect('}') }
            }
        }

        fun list(): List<Any?> {
            val list = mutableListOf<Any?>()
            at++
            space()
            if (text.getOrNull(at) == ']') return list.also { at++ }
            while (true) {
                list.add(value())
                space()
                if (text.getOrNull(at) == ',') at++ else return list.also { expect(']') }
            }
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text.getOrNull(at++) ?: throw IllegalArgumentException("Unterminated string")) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text.getOrNull(at++)) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        /** A number, as [write] wrote it: an [Int] when it is a whole one that fits, else a [Long] or a [Double]. */
        fun number(): Number {
            val start = at
            while (at < text.length && text[at] in "+-0123456789.eE") at++
            val n = text.substring(start, at)
            return n.toIntOrNull() ?: n.toLongOrNull() ?: n.toDouble()
        }

        fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "Unexpected text at $at" }
            at += word.length
            return value
        }

        fun expect(c: Char) {
            require(text.getOrNull(at) == c) { "Expected '$c' at $at" }
            at++
        }

        fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
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
