package org.drivine.backend.sqlite

import org.sqlite.Function
import org.sqlite.core.Codes
import kotlin.math.abs
import kotlin.math.truncate

/**
 * `drivine_trunc(number)`: the number with its fraction cut off, as a whole number where it fits one, as Postgres's
 * and Oracle's `TRUNC` give a number of no fraction: SQLite's own `trunc` gives a real number, which JSON writes `2.0`.
 *
 * A real number within [SLACK] units of its last place of a whole number is that whole number. Postgres and Oracle
 * work out a fraction of a second exactly, and SQLite in binary, which misses it by that much: 33.001 seconds times
 * a million is 33000999.999999996, and those microseconds are 33001000.
 */
internal class SqliteTruncFunction : Function() {

    override fun xFunc() {
        when (value_type(0)) {
            Codes.SQLITE_NULL -> result()
            Codes.SQLITE_INTEGER -> result(value_long(0))
            else -> {
                val number = value_double(0)
                val whole = Math.rint(number)
                val cut = if (abs(number - whole) <= SLACK * Math.ulp(number)) whole else truncate(number)
                if (cut >= Long.MIN_VALUE.toDouble() && cut < Long.MAX_VALUE.toDouble()) result(cut.toLong()) else result(cut)
            }
        }
    }

    private companion object {
        /** How many units of its last place a real number may miss a whole number by and be it: see the class. */
        const val SLACK = 2
    }
}
