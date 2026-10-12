package org.drivine.query

/**
 * The stamp a statement handed back for one node, as it wrote it: `index/found=stamp`. [index] is the
 * one the statement was given for the node, [found] the stamp the node carried before the statement
 * wrote anything to it, and [stamp] the one it is left with. A save statement returns one for each
 * related node whose stamp it hands back, and a batch of fragments one row for each fragment.
 */
internal class FoundStamp(val index: Int, val found: String, val stamp: String) {
    companion object {
        /** Reads one entry, `index/found=stamp`. */
        fun of(entry: String): FoundStamp {
            // A stamp holds neither separator, and what follows the first `=` is the stamp whole.
            val (key, stamp) = entry.split('=', limit = 2)
            return FoundStamp(key.substringBefore('/').toInt(), key.substringAfter('/'), stamp)
        }
    }
}

/**
 * The row a [SaveStatement] returned, read: the [root]'s stamp, then that of each related node the
 * statement hands a stamp back for, by its index among [SaveStatement.stamped].
 */
internal class ReturnedStamps(val root: String, val related: Map<Int, FoundStamp>) {
    companion object {
        /** Reads a row: the root's stamp, then an entry for each related node, joined by commas. */
        fun of(row: String): ReturnedStamps {
            val returned = row.split(',')
            return ReturnedStamps(returned.first(), returned.drop(1).map(FoundStamp::of).associateBy { it.index })
        }
    }
}
