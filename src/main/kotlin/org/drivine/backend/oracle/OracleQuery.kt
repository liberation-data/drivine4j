package org.drivine.backend.oracle

import org.drivine.backend.sql.RowSource

/**
 * A `GRAPH_TABLE` over a graph: the rows that match [pattern], named [alias]. It can give any of
 * [columns], and gives those the query reads, so that `COLUMNS` says what a row is there for and not
 * everything a node has. A node of the pattern and the row it becomes have one name, so a query reads
 * `people_.doc` of the node its `MATCH` calls `people_`.
 *
 * @param columns what a row can carry, each under the name the query reads it by, in the order they are written
 */
internal class GraphTable(
    private val graph: String,
    private val pattern: String,
    private val columns: List<Column>,
    override val alias: String,
) : RowSource {

    /** A column of a row: [name], and the [expression] of the pattern that gives it. */
    class Column(val name: String, private val expression: String) {
        /** The column in `COLUMNS`: named for the property it reads, unless it is that property's own name. */
        val text: String get() = if (expression.substringAfterLast('.') == name) expression else "$expression AS $name"
    }

    /** A row is never nothing: a query that reads no column of it, a count, is given the first. */
    override fun reading(readers: List<String>): String {
        val read = columns.filter { column -> readers.any { reads(it, column.name) } }.ifEmpty { columns.take(1) }
        return written(read)
    }

    /** The source with every column it can give, for a query whose reader is not known here. */
    fun whole(): String = written(columns)

    /**
     * This source as one that gives every column whatever is read of it: for a query that holds text
     * written by hand, which may name a column in any way SQL allows, `N_.STAMP` or `n_ . stamp`, and
     * not only as the statement's own text does.
     */
    fun entire(): RowSource = object : RowSource {
        override val alias: String get() = this@GraphTable.alias
        override fun reading(readers: List<String>): String = whole()
    }

    private fun written(columns: List<Column>): String =
        "GRAPH_TABLE ($graph\n    MATCH $pattern\n    COLUMNS (${columns.joinToString(", ") { it.text }})) $alias"

    /** Whether [text] names the column [name] of a row of this source. */
    private fun reads(text: String, name: String): Boolean {
        val reference = "$alias.$name"
        var at = text.indexOf(reference)
        while (at >= 0) {
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + reference.length)
            if ((before == null || !continuesName(before)) && (after == null || !continuesName(after))) return true
            at = text.indexOf(reference, at + 1)
        }
        return false
    }

    private fun continuesName(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '#'

    companion object {
        /** The columns of the node a pattern calls [variable]: see [OracleSql.NODE_COLUMNS]. */
        fun node(variable: String): List<Column> = OracleSql.NODE_COLUMNS.map { Column(it, "$variable.$it") }
    }
}
