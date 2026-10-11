package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.property

/**
 * Rewrites a predicate the caller wrote, with a property of the root written as Cypher writes it,
 * `issue.state` or ``issue.`metadata.source` ``, to read that property. So the plain comparisons a
 * Cypher predicate makes are the same text on Oracle; anything more is SQL, over the root's columns
 * `issue.props`, `issue.labels` and `issue.stamp`. Text inside a string literal is left as it is.
 */
internal class CypherShorthand(private val alias: String, private val row: String) {

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

        /** What a row of a node has of its own; a predicate that names one means the column. */
        val COLUMNS = setOf("nid", "labels", "props", "stamp")
    }
}
