package org.drivine.backend.oracle

/**
 * What a [Query] reads its rows from, under a name the rest of the query calls them by. It is written
 * last, once the query knows what it reads of each row.
 */
internal interface RowSource {

    /** The name of a row of this source. */
    val alias: String

    /** The source as it stands after `FROM`, for a query whose other clauses are [readers]. */
    fun reading(readers: List<String>): String
}

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

/** A query written elsewhere, as rows named [alias]: every column it selects is there to read. */
internal class Derived(private val query: String, override val alias: String) : RowSource {
    override fun reading(readers: List<String>): String = "(\n    ${query.under()}\n) $alias"
}

/**
 * One `SELECT`, laid out a clause to a line. Its parts are text; what it adds is the order they are
 * written in, the row source that reads what the other clauses name, and one layout for every query a
 * statement holds, so that a query nested in another sits under the line that opens it.
 *
 * @param where the predicates every row meets; each is one that `AND` may join as it stands
 * @param tail the clauses after `WHERE`, each a line: an order, a page
 */
internal class Query(
    private val select: String,
    private val from: RowSource,
    private val where: List<String> = emptyList(),
    private val tail: List<String> = emptyList(),
) {
    /** The query, its first line unindented and the lines of each clause under it. */
    fun text(): String = buildString {
        append("SELECT ").append(select)
        append("\nFROM ").append(from.reading(listOf(select) + where + tail))
        if (where.isNotEmpty()) append("\nWHERE ").append(where.joinToString("\n  AND "))
        tail.forEach { append("\n").append(it) }
    }

    /** The query as a value or a row source of another: in parentheses, under the line that opens them. */
    fun nested(): String = "(\n    ${text().under()})"
}

/** [this], text of several lines, with its later lines moved in under the line its first is written on. */
internal fun String.under(): String = replace("\n", "\n    ")
