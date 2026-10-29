package org.drivine.backend.sqlite

import org.sqlite.Function
import org.sqlite.core.Codes
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `drivine_round(number, precision, mode)`: the number rounded to as many decimal places by the mode, as Java's
 * `BigDecimal` rounds it, as Neo4j's `round(value, precision, mode)` does: in decimal, so 2.345 is halfway between
 * 2.34 and 2.35, where SQLite's binary arithmetic finds it just below. Null where the number or the precision is.
 */
internal class SqliteRoundFunction : Function() {

    override fun xFunc() {
        if (value_type(0) == Codes.SQLITE_NULL || value_type(1) == Codes.SQLITE_NULL) return result()
        val number = if (value_type(0) == Codes.SQLITE_INTEGER) BigDecimal.valueOf(value_long(0)) else BigDecimal.valueOf(value_double(0))
        // Places at or past the number's own change nothing, and setScale would write each out: round(2.5, 1000000000) overflows.
        val places = value_long(1)
        result(if (places >= number.scale()) number.toDouble() else number.setScale(places.toInt(), RoundingMode.valueOf(requireNotNull(value_text(2)))).toDouble())
    }
}
