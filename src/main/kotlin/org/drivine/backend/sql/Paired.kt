package org.drivine.backend.sql

/**
 * Every pair of rows of [name], the first named [first] and the second [alias], that meets [on]: what a
 * search that measures each of a set against the others reads, where the store cannot ask a query of
 * its own for each row (SQLite has no `LATERAL`).
 */
internal class Paired(private val name: String, private val first: String, override val alias: String, private val on: String) : RowSource {
    override fun reading(readers: List<String>): String = "$name $first\nJOIN $name $alias ON $on"
}
