package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_list_order(a, b)`: where the JSON list [a] comes beside the JSON list [b] as Cypher orders them in a comparison
 * (see [SqliteCypherValues.order]), -1, 0 or 1; null where either is no list, or where the pair that decides is not ordered.
 */
internal class SqliteListOrderFunction : Function() {

    override fun xFunc() {
        val a = SqliteCypherValues.read(value_text(0))
        val b = SqliteCypherValues.read(value_text(1))
        if (a == null || b == null || !a.isArray || !b.isArray) return result()
        SqliteCypherValues.order(a, b)?.let { result(it) } ?: result()
    }
}
