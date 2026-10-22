package org.drivine.backend.sql

/**
 * Rewrites a predicate the caller wrote, with a property of the root written as Cypher writes it,
 * `issue.state` or ``issue.`metadata.source` ``, to read that property. So the plain comparisons a
 * Cypher predicate makes are the same text on a SQL store; anything more is SQL, over the root's
 * columns `issue.props`, `issue.labels` and `issue.stamp`. Text inside a string literal is left as it is.
 * A property compared with `true` or `false` (`issue.locked = true`) is compared with the text a store
 * reads a JSON boolean as, since SQL compares no text with a boolean.
 *
 * @param property how the store reads the property of a row, given the row and the property's name
 */
internal class CypherShorthand(private val alias: String, private val row: String, private val property: (String, String) -> String) {

    /** [predicate] with each `alias.name` of the root read as that property, or as that column of the row. */
    fun rewrite(predicate: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < predicate.length) {
            if (predicate[at] == '\'') {
                val stop = endOfLiteral(predicate, at)
                out.append(predicate, at, stop)
                at = stop
                continue
            }
            val match = SHORTHAND.matchAt(predicate, at)?.takeIf { at == 0 || !continuesName(predicate[at - 1]) }
            if (match != null && match.groupValues[1] == alias && predicate.getOrNull(match.range.last + 1) != '(') {
                val key = match.groupValues[2].ifEmpty { match.groupValues[3] }
                // One of the row's own columns is the column; any other name is a property.
                out.append(if (key in COLUMNS) "$row.$key" else property(row, key))
                at = match.range.last + 1
                // A property compared with a boolean is compared with the boolean's text, which is what a property is read as.
                if (key !in COLUMNS) {
                    BOOLEAN_COMPARED.matchAt(predicate, at)?.let { compared ->
                        out.append(compared.groupValues[1]).append("'").append(compared.groupValues[2].lowercase()).append("'")
                        at = compared.range.last + 1
                    }
                }
            } else {
                out.append(predicate[at++])
            }
        }
        return out.toString()
    }

    /** The index after the string literal that opens at [start]; a doubled quote is part of it. */
    private fun endOfLiteral(predicate: String, start: Int): Int {
        var end = start + 1
        while (end < predicate.length) {
            if (predicate[end] != '\'') end++ else if (predicate.getOrNull(end + 1) == '\'') end += 2 else break
        }
        return minOf(end + 1, predicate.length)
    }

    private fun continuesName(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

    private companion object {
        /** `alias.name` or ``alias.`a name` ``. */
        val SHORTHAND = Regex("([A-Za-z_][A-Za-z0-9_]*)\\.(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))")

        /** An equality or inequality with `true` or `false` that follows a property, in any case: the operator with its space, and the boolean. */
        val BOOLEAN_COMPARED = Regex("(\\s*(?:=|<>|!=)\\s*)(true|false)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

        /** What a row of a node has of its own; a predicate that names one means the column. */
        val COLUMNS = setOf("nid", "labels", "props", "stamp")
    }
}
