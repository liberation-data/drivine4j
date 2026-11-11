package org.drivine.cli

import java.io.Reader

/** One thing an input runs: a [Statement] of Cypher, or a [Command], a line that starts with `:`. */
sealed interface Input {
    /** A statement, without the `;` that ended it, with its comments as written: the store reads them as Cypher does. */
    data class Statement(val cypher: String) : Input

    /** A command's line, from its `:`, without the line break or a `\r` before it. */
    data class Command(val line: String) : Input {
        /** The command's name, as `:param` is `:param x => 1`'s. */
        val name: String get() = line.trim().substringBefore(' ')
    }
}

/**
 * Splits an input into what it runs, as the contract's "Files" says: statements on `;`, but not on one inside a string in
 * `'…'` or `"…"` (where `\` escapes the character after it), a name in backticks, a `//` comment to the end of its line,
 * or a `/* … */` comment; and a command where a line's first character after any spaces is `:`, where a statement would
 * start, so not on the line of a statement's `;`, after it. A statement that is only spaces and comments isn't run; the text after the last `;` is, where it's more.
 *
 * The input is read as it comes, a character at a time, and each statement is given as soon as it ends: so a file of an
 * export, which may be larger than memory, runs as it's read, and a statement on stdin runs before stdin closes.
 */
class Statements(private val reader: Reader) : Iterator<Input> {

    private var next: Input? = null
    private var done = false
    private var pushedBack = -2
    // Whether the next input starts a line: not after a statement's `;`, as the rest of that line starts no command.
    private var atLineStart = true

    private fun read(): Int = if (pushedBack != -2) pushedBack.also { pushedBack = -2 } else reader.read()

    private fun unread(c: Int) {
        pushedBack = c
    }

    override fun hasNext(): Boolean {
        if (next == null && !done) next = scan()
        return next != null
    }

    override fun next(): Input {
        if (!hasNext()) throw NoSuchElementException()
        return next!!.also { next = null }
    }

    /** The next input, or null at the end. */
    private fun scan(): Input? {
        val text = StringBuilder()
        // Whether anything but spaces and comments is in the statement so far.
        var content = false
        // Whether the statement so far is only spaces and comments, and a line has just started: where a command may.
        var lineStart = atLineStart
        while (true) {
            val c = read()
            if (c == -1) {
                done = true
                return if (content) Input.Statement(text.toString().trim()) else null
            }
            val ch = c.toChar()
            when {
                ch == ':' && lineStart && !content -> return Input.Command(":" + line()).also { atLineStart = true }
                ch == ';' -> {
                    if (content) return Input.Statement(text.toString().trim()).also { atLineStart = false }
                    text.setLength(0)
                    lineStart = false
                }
                ch == '\'' || ch == '"' || ch == '`' -> {
                    text.append(ch)
                    quoted(ch, text)
                    content = true
                    lineStart = false
                }
                ch == '/' -> {
                    val after = read()
                    when (after) {
                        '/'.code -> {
                            text.append("//")
                            // To the end of the line, which ends it and starts another.
                            while (true) {
                                val d = read()
                                if (d == -1) break
                                text.append(d.toChar())
                                if (d == '\n'.code) break
                            }
                            lineStart = true
                        }
                        '*'.code -> {
                            text.append("/*")
                            blockComment(text)
                            lineStart = false
                        }
                        else -> {
                            if (after != -1) unread(after)
                            text.append(ch)
                            content = true
                            lineStart = false
                        }
                    }
                }
                ch == '\n' -> {
                    text.append(ch)
                    lineStart = true
                }
                ch == ' ' || ch == '\t' || ch == '\r' || ch == '\u000C' -> text.append(ch)
                else -> {
                    text.append(ch)
                    content = true
                    lineStart = false
                }
            }
        }
    }

    /** The rest of a command's line, without its line break or a `\r` before it. */
    private fun line(): String {
        val line = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) {
                done = true
                break
            }
            if (c == '\n'.code) break
            line.append(c.toChar())
        }
        return line.toString().removeSuffix("\r")
    }

    /** The rest of a string or a name that [quote] opened, into [text]: a `\` escapes the next character, but in backticks. */
    private fun quoted(quote: Char, text: StringBuilder) {
        while (true) {
            val c = read()
            if (c == -1) return
            text.append(c.toChar())
            when {
                c == quote.code -> return
                c == '\\'.code && quote != '`' -> read().takeIf { it != -1 }?.let { text.append(it.toChar()) }
            }
        }
    }

    /** The rest of a `/* … */` comment, into [text]. */
    private fun blockComment(text: StringBuilder) {
        var star = false
        while (true) {
            val c = read()
            if (c == -1) return
            text.append(c.toChar())
            if (star && c == '/'.code) return
            star = c == '*'.code
        }
    }
}

/**
 * The parameters [cypher] uses, by their names, in the order it first uses each: every `$name` or `` $`a name` `` outside a
 * string, a backticked name and a comment, read with the rules [Statements] splits by. A name repeated is given once.
 */
internal fun parametersUsed(cypher: String): List<String> {
    val used = LinkedHashSet<String>()
    var i = 0
    fun skipQuoted(quote: Char) {
        i++
        while (i < cypher.length) {
            val c = cypher[i]
            if (c == '\\' && quote != '`') { i += 2; continue }
            i++
            if (c == quote) return
        }
    }
    while (i < cypher.length) {
        val c = cypher[i]
        when {
            c == '\'' || c == '"' || c == '`' -> skipQuoted(c)
            c == '/' && cypher.getOrNull(i + 1) == '/' -> { i = cypher.indexOf('\n', i).let { if (it < 0) cypher.length else it } }
            c == '/' && cypher.getOrNull(i + 1) == '*' -> { i = cypher.indexOf("*/", i + 2).let { if (it < 0) cypher.length else it + 2 } }
            c == '$' && cypher.getOrNull(i + 1) == '`' -> {
                val start = i + 2
                var end = start
                val name = StringBuilder()
                while (end < cypher.length) {
                    if (cypher[end] == '`') {
                        if (cypher.getOrNull(end + 1) == '`') { name.append('`'); end += 2; continue }
                        break
                    }
                    name.append(cypher[end]); end++
                }
                if (name.isNotEmpty()) used += name.toString()
                i = end + 1
            }
            c == '$' -> {
                val start = i + 1
                var end = start
                while (end < cypher.length && (cypher[end].isLetterOrDigit() || cypher[end] == '_')) end++
                if (end > start) used += cypher.substring(start, end)
                i = end
            }
            else -> i++
        }
    }
    return used.toList()
}
