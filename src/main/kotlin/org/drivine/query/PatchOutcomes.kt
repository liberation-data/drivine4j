package org.drivine.query

/**
 * What the statements of a patch of many say of each node, over every attempt at them. A statement
 * returns `index/changed=stamp` for a node it found: [CHANGED] where a value it wrote differed, and
 * [UNCHANGED] where none did.
 *
 * A batch the engine turns away is run again whole. Where the caller's transaction outlives the
 * statement that failed, as it does on Oracle, the statements before it stay applied, and the second
 * run finds their nodes already as it would leave them. So a node counts as changed when any attempt
 * changed it and it still carries the stamp that attempt left: an attempt that was rolled back left
 * none, and the node then says for itself whether the last attempt changed it.
 */
internal class PatchOutcomes {
    private val leftByAChange = mutableMapOf<Int, MutableSet<String>>()

    /** Takes in the rows a statement returned, in whichever attempt, and gives them back. */
    fun <T> returned(rows: List<T>): List<T> = rows.onEach { row ->
        val entry = FoundStamp.of(row as String)
        if (entry.found == CHANGED) leftByAChange.getOrPut(entry.index) { mutableSetOf() } += entry.stamp
    }

    /**
     * Whether each node found was changed, by the index of its row, given the [rows] of the attempt
     * that went through. Where two nodes share an id, one that changed says so for both.
     */
    fun changed(rows: List<Any?>): Map<Int, Boolean> = rows.map { FoundStamp.of(it as String) }
        .groupBy({ it.index }, { it.found == CHANGED || it.stamp in leftByAChange[it.index].orEmpty() })
        .mapValues { (_, each) -> each.any { it } }

    companion object {
        /** In a returned row, where a save's has the stamp found: a value written differed from the node's. */
        const val CHANGED = "1"

        /** In a returned row: every value written was the node's already. */
        const val UNCHANGED = "0"
    }
}
