package org.drivine.backend.sqlite

import org.sqlite.Function
import java.util.regex.Matcher

/**
 * `drivine_regexp_replace(text, expression, replacement)`: the text with each match of the regular expression
 * replaced, the replacement naming a group as Postgres's and Oracle's do, `\1`; null where any is null.
 */
internal class SqliteRegexReplaceFunction(private val patterns: SqlitePatterns) : Function() {

    override fun xFunc() {
        val text = value_text(0)
        val expression = value_text(1)
        val replacement = value_text(2)
        if (text == null || expression == null || replacement == null) return result()
        result(patterns.of(expression).matcher(text).replaceAll(java(replacement)))
    }

    /** [replacement] as Java writes one: a backslash and a digit name a group, which Java names with a dollar; anything else is itself. */
    private fun java(replacement: String): String {
        val written = StringBuilder()
        var at = 0
        GROUP.findAll(replacement).forEach { match ->
            written.append(Matcher.quoteReplacement(replacement.substring(at, match.range.first))).append('$').append(match.groupValues[1])
            at = match.range.last + 1
        }
        return written.append(Matcher.quoteReplacement(replacement.substring(at))).toString()
    }

    private companion object {
        val GROUP = Regex("""\\(\d)""")
    }
}
