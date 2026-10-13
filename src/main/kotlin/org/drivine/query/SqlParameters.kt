package org.drivine.query

/**
 * Turns the `$name` parameters of a SQL statement into the positional `?` markers JDBC binds.
 *
 * A parameter is a `$` that does not continue an identifier, followed by a name: Oracle allows `$`
 * inside an identifier (`v$session`), never at its start. Text inside a string literal (`'…'`,
 * `n'…'`, `q'[…]'` or `nq'[…]'`), a quoted identifier or a comment is left as it is, so a JSON path
 * such as `'$.name'` or `'$[*]?(@ == $l)'` is not read as a parameter. Nor is a PL/SQL
 * conditional-compilation directive (`$IF`, `$THEN`, `$ELSE`, `$ELSIF`, `$END`, `$ERROR`), which a
 * parameter therefore cannot be named after.
 */
internal object SqlParameters {

    /** [statement] with a `?` for each parameter, and the name of each in the order they occur. */
    class Positional(val statement: String, val names: List<String>)

    /** Reads [sql] once, left to right. A name that occurs twice is listed twice, so it is bound twice. */
    fun positional(sql: String): Positional {
        val names = mutableListOf<String>()
        return Positional(replaced(sql) { name -> "?".also { names += name } }, names)
    }

    /**
     * [sql] with each parameter replaced by what [replacement] gives for its name, in the order they
     * occur. What is no parameter is left as it is: see [SqlParameters].
     */
    fun replaced(sql: String, replacement: (String) -> String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val end = when {
                c == '\'' || c == '"' -> endOfQuoted(sql, i, c)
                literalPrefix(sql, i) != null -> endOfPrefixedLiteral(sql, i, literalPrefix(sql, i)!!)
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
                val name = sql.substring(i + 1, j)
                if (name.uppercase() in DIRECTIVES) {
                    out.append(sql, i, j)
                } else {
                    out.append(replacement(name))
                }
                i = j
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The directives of PL/SQL conditional compilation, which look like parameters and are not. */
    private val DIRECTIVES = setOf("IF", "THEN", "ELSE", "ELSIF", "END", "ERROR")

    /** Whether [c] can begin a parameter's name: a letter or an underscore, and not a digit, so `$1` is left as it is. */
    private fun startsName(c: Char) = c.isLetter() || c == '_'

    /**
     * The prefix of a national or alternatively quoted literal that opens at [start] (`n`, `q` or
     * `nq`, in either case), when one does; null when the character begins no literal.
     */
    private fun literalPrefix(sql: String, start: Int): String? {
        if (continuesIdentifier(sql, start)) return null
        return listOf("nq'", "q'", "n'").firstOrNull { sql.regionMatches(start, it, 0, it.length, ignoreCase = true) }?.dropLast(1)
    }

    /** The index after the literal with [prefix] that opens at [start]. */
    private fun endOfPrefixedLiteral(sql: String, start: Int, prefix: String): Int =
        if (prefix.endsWith('q', ignoreCase = true)) endOfAlternativeQuoted(sql, start + prefix.length - 1) else endOfQuoted(sql, start + prefix.length, '\'')

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
