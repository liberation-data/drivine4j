package org.drivine.cypher.ast

/** A clause of a query, in the order the query reads them. */
sealed interface Clause {

    /** `MATCH` or `OPTIONAL MATCH`: the paths that must be there, or with [optional] may be. */
    data class Match(val optional: Boolean, val patterns: List<Pattern>, val where: Expression?) : Clause

    /** `UNWIND`: a row for each item of a list, bound to [variable]. */
    data class Unwind(val list: Expression, val variable: String) : Clause

    /** `WITH`: what the rest of the query sees, and the rows it keeps. */
    data class With(val projection: Projection, val where: Expression?) : Clause

    /** `CREATE`: the nodes and relationships of [patterns] made, for each row. */
    data class Create(val patterns: List<Pattern>) : Clause

    /**
     * `MERGE`: for each row, the [pattern] found, each way it is there, or made whole where it is not, with
     * [onCreate] set on what it made and [onMatch] on what it found.
     */
    data class Merge(val pattern: Pattern, val onCreate: List<SetItem>, val onMatch: List<SetItem>) : Clause

    /** `SET`: its [items] written in turn, for each row. */
    data class Set(val items: List<SetItem>) : Clause

    /** `REMOVE`: its [items] taken away in turn, for each row. */
    data class Remove(val items: List<RemoveItem>) : Clause

    /** `DELETE` of nodes and relationships, or with [detach] a node with its relationships. */
    data class Delete(val detach: Boolean, val expressions: List<Expression>) : Clause

    /** `RETURN`: what the query gives back. */
    data class Return(val projection: Projection) : Clause
}
