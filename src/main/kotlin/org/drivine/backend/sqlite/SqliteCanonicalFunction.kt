package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_canonical(json)`: the JSON text of the value, each map in it with its keys in order, so two maps of the same
 * entries are the same text, and group, and are kept once, as Postgres's `jsonb` keeps them. Null where it is null.
 */
internal class SqliteCanonicalFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        result(SqliteCypherValues.write(SqliteCypherValues.canonical(SqliteCypherValues.read(text) ?: return result(text))))
    }
}
