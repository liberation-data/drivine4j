package org.drivine.cypher.ast

/**
 * A query: its clauses in order, ending in a `RETURN`, or in a write that returns nothing; then the
 * queries [unions] joins to it, each ending in a `RETURN` of the same names.
 */
data class Query(val clauses: List<Clause>, val unions: List<Union> = emptyList())
