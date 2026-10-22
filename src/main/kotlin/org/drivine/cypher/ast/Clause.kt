package org.drivine.cypher.ast

/** A clause of a query, in the order the query reads them. */
sealed interface Clause {

    /** `MATCH` or `OPTIONAL MATCH`: the paths that must be there, or with [optional] may be. */
    data class Match(val optional: Boolean, val patterns: List<Pattern>, val where: Expression?) : Clause

    /** `UNWIND`: a row for each item of a list, bound to [variable]. */
    data class Unwind(val list: Expression, val variable: String) : Clause

    /** `WITH`: what the rest of the query sees, and the rows it keeps. */
    data class With(val projection: Projection, val where: Expression?) : Clause

    /** `RETURN`: what the query gives back. */
    data class Return(val projection: Projection) : Clause
}
