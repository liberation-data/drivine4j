package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_regexp_count(text, expression)`: how many times the expression matches in the text, each
 * match after the last one ends, as Postgres's `regexp_matches(text, expression, 'g')` counts them.
 * Nothing in a null text.
 */
internal class SqliteRegexpCountFunction(private val patterns: SqlitePatterns) : Function() {

    override fun xFunc() {
        val text = value_text(0)
        val expression = value_text(1)
        if (text == null || expression == null) return result(0)
        val matcher = patterns.of(expression).matcher(text)
        var count = 0
        while (matcher.find()) count++
        result(count)
    }
}
