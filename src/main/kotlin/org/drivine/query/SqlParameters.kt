package org.drivine.query

/**
 * Turns the `$name` parameters of a SQL statement into the positional `?` markers JDBC binds.
 *
 * A parameter is a `$` that does not continue an identifier, followed by a name: Oracle allows `$`
 * inside an identifier (`v$session`), never at its start. Text inside a string literal, a quoted
 * identifier or a comment is left as it is, so a JSON path such as `'$.name'` or `'$[*]?(@ == $l)'`
 * is not read as a parameter.
 */
internal object SqlParameters {

    /** [statement] with a `?` for each parameter, and the name of each in the order they occur. */
    class Positional(val statement: String, val names: List<String>)

    fun positional(sql: String): Positional {
        val out = StringBuilder(sql.length)
        val names = mutableListOf<String>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val end = when {
                c == '\'' || c == '"' -> endOfQuoted(sql, i, c)
                (c == 'q' || c == 'Q') && sql.startsWith("'", i + 1) && !continuesIdentifier(sql, i) -> endOfAlternativeQuoted(sql, i)
                sql.startsWith("--", i) -> sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                sql.startsWith("/*", i) -> sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                else -> -1
            }
            if (end >= 0) {
                out.append(sql, i, end)
                i = end
            } else if (c == '$' && !continuesIdentifier(sql, i) && i + 1 < sql.length && startsName(sql[i + 1])) {
                var j = i + 1
                while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                names += sql.substring(i + 1, j)
                out.append('?')
                i = j
            } else {
                out.append(c)
                i++
            }
        }
        return Positional(out.toString(), names)
    }

    private fun startsName(c: Char) = c.isLetter() || c == '_'

    /** Whether the character before [index] is part of an identifier, which the one at [index] would then continue. */
    private fun continuesIdentifier(sql: String, index: Int): Boolean =
        index > 0 && sql[index - 1].let { it.isLetterOrDigit() || it == '_' || it == '$' || it == '#' }

    /** The index after the literal or quoted identifier that opens at [start]; a doubled quote is part of it. */
    private fun endOfQuoted(sql: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
                if (i + 1 < sql.length && sql[i + 1] == quote) i += 2 else return i + 1
            } else {
                i++
            }
        }
        return sql.length
    }

    /** The index after an Oracle `q'[…]'` literal that opens at [start]. */
    private fun endOfAlternativeQuoted(sql: String, start: Int): Int {
        if (start + 2 >= sql.length) return sql.length
        val close = when (val open = sql[start + 2]) {
            '[' -> ']'
            '(' -> ')'
            '{' -> '}'
            '<' -> '>'
            else -> open
        }
        return sql.indexOf("$close'", start + 3).let { if (it < 0) sql.length else it + 2 }
    }
}
