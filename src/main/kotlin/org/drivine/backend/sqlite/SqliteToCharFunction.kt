package org.drivine.backend.sqlite

import org.sqlite.Function
import org.sqlite.core.Codes
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields

/**
 * `drivine_to_char(value, pattern)`: a timestamp (see [SqliteTimestamps]) or a number written in a pattern of
 * Postgres's and Oracle's `to_char`, those compiled Cypher writes temporal values with. Null where the value is.
 *
 * - A timestamp's pattern is made of `YYYY`, `MM`, `DD`, `HH24`, `MI`, `SS`, `IYYY` (the ISO week's year), `IW` (its
 *   week) and `DDD` (the day of the year), and text in double quotes, written as it is; anything else is itself.
 * - A number's opens with `FM`: `0`s for its digits, a whole number padded to as many, and after a point as many
 *   `9`s as the fraction may have digits, rounded, its trailing zeros left out (`2.5`, `2.`).
 */
internal class SqliteToCharFunction : Function() {

    override fun xFunc() {
        val pattern = value_text(1) ?: return result()
        if (value_type(0) == Codes.SQLITE_NULL) return result()
        result(if (pattern.startsWith("FM")) number(pattern.removePrefix("FM")) else clock(pattern))
    }

    private fun number(pattern: String): String {
        val value = if (value_type(0) == Codes.SQLITE_INTEGER) BigDecimal.valueOf(value_long(0)) else BigDecimal(value_text(0))
        val point = pattern.indexOf('.')
        if (point < 0) {
            val whole = value.setScale(0, RoundingMode.HALF_UP)
            val digits = whole.abs().toPlainString().padStart(pattern.count { it == '0' }, '0')
            return if (whole.signum() < 0) "-$digits" else digits
        }
        val fraction = pattern.length - point - 1
        val rounded = value.setScale(fraction, RoundingMode.HALF_UP).stripTrailingZeros()
        val text = if (rounded.scale() <= 0) rounded.setScale(0).toPlainString() + "." else rounded.toPlainString()
        return if (text.startsWith("-0") && rounded.signum() == 0) text.removePrefix("-") else text
    }

    private fun clock(pattern: String): String {
        val clock = SqliteTimestamps.read(value_text(0))!!
        val written = StringBuilder()
        var at = 0
        while (at < pattern.length) {
            if (pattern[at] == '"') {
                val end = pattern.indexOf('"', at + 1).takeIf { it > at } ?: pattern.length
                written.append(pattern, at + 1, end)
                at = end + 1
                continue
            }
            val token = TOKENS.firstOrNull { pattern.startsWith(it, at) }
            if (token == null) {
                written.append(pattern[at++])
                continue
            }
            written.append(
                when (token) {
                    "HH24" -> "%02d".format(clock.hour)
                    "IYYY" -> "%04d".format(clock.get(IsoFields.WEEK_BASED_YEAR))
                    "YYYY" -> "%04d".format(clock.year)
                    "DDD" -> "%03d".format(clock.get(ChronoField.DAY_OF_YEAR))
                    "IW" -> "%02d".format(clock.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                    "MM" -> "%02d".format(clock.monthValue)
                    "DD" -> "%02d".format(clock.dayOfMonth)
                    "MI" -> "%02d".format(clock.minute)
                    else -> "%02d".format(clock.second)
                },
            )
            at += token.length
        }
        return written.toString()
    }

    private companion object {
        /** The parts of a timestamp's pattern, the longer of two that start alike first. */
        val TOKENS = listOf("HH24", "IYYY", "YYYY", "DDD", "IW", "MM", "DD", "MI", "SS")
    }
}
