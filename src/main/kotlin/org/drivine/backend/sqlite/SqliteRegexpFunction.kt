package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * SQLite's `REGEXP` operator, which it leaves to the application: `text REGEXP expression` calls
 * `regexp(expression, text)`, and is true where the expression matches somewhere in the text. Null
 * where either is null, as a comparison with null is.
 */
internal class SqliteRegexpFunction(private val patterns: SqlitePatterns) : Function() {

    override fun xFunc() {
        val expression = value_text(0)
        val text = value_text(1)
        if (expression == null || text == null) return result()
        result(if (patterns.of(expression).matcher(text).find()) 1 else 0)
    }
}
