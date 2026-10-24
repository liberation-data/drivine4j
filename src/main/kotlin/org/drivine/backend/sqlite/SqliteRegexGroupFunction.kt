package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_regex_group(text, expression, group)`: the text the group of that number matches, where the regular
 * expression first matches in the text, as Postgres's `regexp_match` gives it; null where it matches nowhere, or
 * the group takes no part, or any is null.
 */
internal class SqliteRegexGroupFunction(private val patterns: SqlitePatterns) : Function() {

    override fun xFunc() {
        val text = value_text(0)
        val expression = value_text(1)
        if (text == null || expression == null || args() < 3) return result()
        val matcher = patterns.of(expression).matcher(text)
        val group = value_int(2)
        if (!matcher.find() || group > matcher.groupCount()) return result()
        val found = matcher.group(group)
        if (found == null) result() else result(found)
    }
}
