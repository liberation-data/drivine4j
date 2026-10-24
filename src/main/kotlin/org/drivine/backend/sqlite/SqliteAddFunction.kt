package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_add(a, b)`: Cypher's `a + b` of the JSON values `a` and `b`, as JSON text (see [SqliteCypherValues.add]).
 * A list takes a null as an item; any other value added to null is null, as are two values Cypher does not add.
 */
internal class SqliteAddFunction : Function() {

    override fun xFunc() {
        val a = SqliteCypherValues.read(value_text(0)) ?: SqliteCypherValues.NULL
        val b = SqliteCypherValues.read(value_text(1)) ?: SqliteCypherValues.NULL
        val sum = SqliteCypherValues.add(a, b)
        if (sum == null) result() else result(SqliteCypherValues.write(sum))
    }
}
