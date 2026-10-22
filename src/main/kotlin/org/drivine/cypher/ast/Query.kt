package org.drivine.cypher.ast

/** A query: its clauses in order, ending in a `RETURN`, or in a write that returns nothing. */
data class Query(val clauses: List<Clause>)
