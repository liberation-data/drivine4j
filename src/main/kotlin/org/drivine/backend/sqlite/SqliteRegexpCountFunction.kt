package org.drivine.backend.sqlite

import org.drivine.cypher.host.regex.JavaPatterns
import org.sqlite.Function

/**
 * `drivine_regexp_count(text, expression)`: how many times the expression matches in the text, each
 * match after the last one ends, as Postgres's `regexp_matches(text, expression, 'g')` counts them.
 * Nothing in a null text. The expression is read as the shared module's `regexp` reads one ([JavaPatterns]):
 * Java's, a POSIX class (`[:alnum:]`) as Java names it, so a full-text search's expressions, written for
 * Postgres, read the same.
 */
internal class SqliteRegexpCountFunction : Function() {

    override fun xFunc() {
        val text = value_text(0)
        val expression = value_text(1)
        if (text == null || expression == null) return result(0)
        result(JavaPatterns.of(expression).count(text))
    }
}
