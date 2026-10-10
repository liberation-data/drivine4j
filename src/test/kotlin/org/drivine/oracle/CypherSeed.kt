package org.drivine.oracle

/**
 * Reads the `CREATE` statements the cross-engine tests seed a graph with, so the same fixture can be
 * written to a store that does not speak Cypher. It reads what those fixtures use and no more:
 * patterns of nodes and relationships, with literal properties.
 *
 * ```
 * CREATE (a:Person {id: 'a', tags: ['x'], `metadata.rank`: 5})-[:KNOWS {since: 2020}]->(b:Person {id: 'b'})
 * CREATE (a)<-[:FOLLOWS]-(b)
 * ```
 */
internal class CypherSeed private constructor(private val text: String) {

    class Node(val labels: List<String>, val properties: Map<String, Any?>)

    /** A relationship from the node at index [from] to the node at index [to], as they are in [nodes]. */
    class Edge(val from: Int, val to: Int, val type: String, val properties: Map<String, Any?>)

    val nodes = mutableListOf<Node>()
    val edges = mutableListOf<Edge>()

    private val variables = mutableMapOf<String, Int>()
    private var at = 0

    private fun parse() {
        skipSpace()
        while (at < text.length) {
            expectWord("CREATE")
            do pattern() while (take(','))
            take(';')
            skipSpace()
        }
    }

    private fun pattern() {
        var left = node()
        while (true) {
            skipSpace()
            val incoming = text.startsWith("<-", at)
            if (!incoming && !text.startsWith("-", at)) return
            at += if (incoming) 2 else 1
            expect('[')
            name()
            expect(':')
            val type = name() ?: fail("a relationship type")
            val properties = if (peek() == '{') map() else emptyMap()
            expect(']')
            expect('-')
            val outgoing = take('>')
            check(incoming != outgoing) { "A created relationship has one direction, at $at in: $text" }
            val right = node()
            edges += if (outgoing) Edge(left, right, type, properties) else Edge(right, left, type, properties)
            left = right
        }
    }

    /** A node pattern; the index of the node it names or makes. */
    private fun node(): Int {
        expect('(')
        val variable = name()
        val labels = mutableListOf<String>()
        while (take(':')) labels += name() ?: fail("a label")
        val properties = if (peek() == '{') map() else emptyMap()
        expect(')')
        variables[variable]?.takeIf { labels.isEmpty() && properties.isEmpty() }?.let { return it }
        nodes += Node(labels, properties)
        variable?.let { variables[it] = nodes.lastIndex }
        return nodes.lastIndex
    }

    private fun map(): Map<String, Any?> {
        expect('{')
        val entries = linkedMapOf<String, Any?>()
        if (!take('}')) {
            do {
                val key = name() ?: fail("a property name")
                expect(':')
                entries[key] = value()
            } while (take(','))
            expect('}')
        }
        return entries
    }

    private fun value(): Any? {
        skipSpace()
        val c = text.getOrNull(at) ?: fail("a value")
        return when {
            c == '\'' || c == '"' -> string(c)
            c == '[' -> {
                at++
                val items = mutableListOf<Any?>()
                if (!take(']')) {
                    do items += value() while (take(','))
                    expect(']')
                }
                items
            }
            c == '{' -> map()
            c == '-' || c.isDigit() -> {
                val start = at
                at++
                while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
                val number = text.substring(start, at)
                number.toLongOrNull() ?: number.toDouble()
            }
            else -> {
                val word = name() ?: fail("a value")
                when {
                    // A function that wraps one value, as an engine's vector or temporal constructor does: the value itself.
                    peek() == '(' -> { expect('('); value().also { expect(')') } }
                    word == "true" -> true
                    word == "false" -> false
                    word == "null" -> null
                    else -> fail("a value, not '$word'")
                }
            }
        }
    }

    private fun string(quote: Char): String {
        at++
        val out = StringBuilder()
        while (text[at] != quote) {
            if (text[at] == '\\') at++
            out.append(text[at++])
        }
        at++
        return out.toString()
    }

    /** An identifier, plain or in backticks; null when there is none here. */
    private fun name(): String? {
        skipSpace()
        if (text.getOrNull(at) == '`') {
            val end = text.indexOf('`', at + 1)
            return text.substring(at + 1, end).also { at = end + 1 }
        }
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        return text.substring(start, at).ifEmpty { null }
    }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char? {
        skipSpace()
        return text.getOrNull(at)
    }

    private fun take(c: Char): Boolean = (peek() == c).also { if (it) at++ }

    private fun expect(c: Char) {
        if (!take(c)) fail("'$c'")
    }

    private fun expectWord(word: String) {
        skipSpace()
        if (!text.startsWith(word, at, ignoreCase = true)) fail(word)
        at += word.length
    }

    private fun fail(expected: String): Nothing =
        throw IllegalArgumentException("Expected $expected at $at: ${text.substring(at.coerceAtMost(text.length)).take(40)}… in: $text")

    companion object {
        fun parse(statement: String): CypherSeed = CypherSeed(statement).apply { parse() }
    }
}
