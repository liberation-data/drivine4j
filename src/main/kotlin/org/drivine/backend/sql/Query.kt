package org.drivine.backend.sql

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

/** A table of the graph, [name], as rows named [alias]. */
internal class Table(private val name: String, override val alias: String) : RowSource {
    override fun reading(readers: List<String>): String = "$name $alias"
}

/** A query written elsewhere, as rows named [alias]: every column it selects is there to read. */
internal class Derived(private val query: String, override val alias: String) : RowSource {
    override fun reading(readers: List<String>): String = "(\n    ${query.under()}\n) $alias"
}

/**
 * The nodes [grouped] gives once each, as rows named [once], joined to their rows of [nodes], named
 * [row]: the Postgres and SQLite selects read a path's nodes so, each node once however many ways lead to it,
 * and the nodes a vector index finds for `nearestAmong`, each joined to its row among the roots searched.
 */
internal class OnceReached(private val grouped: String, private val once: String, private val nodes: String, private val row: String) : RowSource {
    override val alias: String get() = row
    override fun reading(readers: List<String>): String = "(\n    ${grouped.under()}\n) $once\nJOIN $nodes $row ON $row.nid = $once.nid"
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
