package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_contains(props, map)`: whether the JSON map `props` holds each entry of the JSON map `map`, as a
 * pattern's map of properties is met (see [SqliteCypherValues.contains]), 1 or 0; null where either is no value.
 */
internal class SqliteContainsFunction : Function() {

    override fun xFunc() {
        val props = SqliteCypherValues.read(value_text(0))
        val map = SqliteCypherValues.read(value_text(1))
        if (props == null || map == null) return result()
        result(if (SqliteCypherValues.contains(props, map)) 1 else 0)
    }
}
