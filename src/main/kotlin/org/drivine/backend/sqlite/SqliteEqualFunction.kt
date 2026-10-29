package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_equal(a, b)`: whether the JSON values [a] and [b] are equal as Cypher compares them (see
 * [SqliteCypherValues.compare]), 1 or 0; null where either is no value, as a comparison with null is, or
 * where a null they hold makes the comparison null.
 */
internal class SqliteEqualFunction : Function() {

    override fun xFunc() {
        val a = SqliteCypherValues.read(value_text(0))
        val b = SqliteCypherValues.read(value_text(1))
        if (a == null || b == null) return result()
        when (SqliteCypherValues.compare(a, b)) {
            true -> result(1)
            false -> result(0)
            null -> result()
        }
    }
}
