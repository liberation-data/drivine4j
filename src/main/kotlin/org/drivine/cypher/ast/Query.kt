package org.drivine.cypher.ast

/** A query: its clauses in order, ending in a `RETURN`. */
data class Query(val clauses: List<Clause>)
