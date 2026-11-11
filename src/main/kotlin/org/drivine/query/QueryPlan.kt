package org.drivine.query

/**
 * A store's plan of a statement, which ran nothing: each line as the store gives it ([lines]), or, where the store can't
 * plan what the statement is or compiles to, as Oracle can't plan a PL/SQL block, no lines and why ([none]). It is for a
 * caller that shows a plan, as the drivine CLI's `--explain=plan` does: a plan's text is the store's, and changes with its
 * version and its data.
 */
data class QueryPlan(val lines: List<String>, val none: String? = null) {
    companion object {
        /** No plan, for [why]. */
        fun none(why: String) = QueryPlan(emptyList(), why)
    }
}
