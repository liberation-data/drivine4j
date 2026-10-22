package org.drivine.cypher.ast

/**
 * What a `WITH` or a `RETURN` gives the next part of a query, or the caller: its [items], each under the
 * name it is known by, then the order of the rows, how many are skipped and how many are kept. With
 * [star], every variable in scope is carried as well.
 */
data class Projection(
    val distinct: Boolean,
    val star: Boolean,
    val items: List<Item>,
    val orderBy: List<SortItem>,
    val skip: Expression?,
    val limit: Expression?,
) {
    /** One item: its expression, and the name it is known by: its alias, or the text it was written as. */
    data class Item(val expression: Expression, val name: String)

    /** An order term. */
    data class SortItem(val expression: Expression, val descending: Boolean)
}
