package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_float_text(real)`: the float as Java writes a double, as `toString` gives it on a graph engine: `2.0`, `1.0E7`,
 * `1.2E-4`, `0.3333333333333333`. SQLite's own text of a real has 17 digits, `0.33333333333333331`.
 */
internal class SqliteFloatTextFunction : Function() {

    override fun xFunc() {
        if (value_type(0) == SQLITE_NULL) return result()
        result(value_double(0).toString())
    }

    private companion object {
        /** SQLite's type code of a null argument. */
        const val SQLITE_NULL = 5
    }
}
