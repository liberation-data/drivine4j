package org.drivine.cypher.ast

/** A query joined to the one before it by `UNION`, its rows after that query's, or with [all] every row kept. */
data class Union(val all: Boolean, val clauses: List<Clause>)
