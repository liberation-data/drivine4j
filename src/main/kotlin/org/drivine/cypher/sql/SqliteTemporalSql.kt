package org.drivine.cypher.sql

import org.drivine.backend.sqlite.SqliteFunctions
import org.drivine.backend.sqlite.SqliteTimestamps
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * SQLite's SQL for temporal values: see [TemporalSql]. SQLite has no type for a moment and no arithmetic of the
 * calendar, so a timestamp is the text of a wall clock to the microsecond, `2024-03-15T10:30:00.000000`, which
 * compares and orders as the clock does (see `SqliteTimestamps`), and what is worked out of one is worked out by
 * the functions Drivine registers on each connection, with `java.time`: see `SqliteTemporalFunction`.
 */
class SqliteTemporalSql(shared: (List<String>, (List<String>) -> String) -> String) : TemporalSql(shared) {

    override fun local(text: String, kind: Temporal) = temporal("local", text, "'${kind.name}'")

    override fun utc(text: String, kind: Temporal) = temporal("utc", text, "'${kind.name}'")

    /**
     * The clock, in UTC, as the statement is compiled, just before it runs: the same for each of its rows and each step
     * of a write, as a statement's clock is in Cypher. SQLite's own `'now'` is the same within one step of a query alone.
     */
    override val now = "'${SqliteTimestamps.write(LocalDateTime.now(ZoneOffset.UTC))}'"

    override fun matches(text: String, pattern: String) = "($text REGEXP $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "${SqliteFunctions.REGEX_GROUP}($text, $pattern, $group)"

    override fun number(text: String) = "CAST(NULLIF($text, '') AS NUMERIC)"

    /** A timestamp is such a text already. */
    override fun sortable(timestamp: String) = timestamp

    /** The last six digits, for a timestamp of a year BC is a character longer: `-0044-03-15T10:30:00.000000`. */
    override fun micros(timestamp: String) = "substr($timestamp, -6)"

    /** `java.time`'s, for SQLite's own `strftime` reads no year before 0. */
    override fun dayOfWeek(timestamp: String) = temporal("day_of_week", timestamp)

    override fun addMonths(timestamp: String, months: String) = temporal("add_months", timestamp, months)

    override fun addTime(timestamp: String, days: String, seconds: String) = temporal("add_time", timestamp, days, seconds)

    override fun monthsBetween(from: String, to: String) = temporal("months_between", from, to)

    override fun secondsBetween(from: String, to: String) = temporal("seconds_between", from, to)

    override fun truncate(unit: String, timestamp: String) = if (unit in UNITS) temporal("truncate", timestamp, "'$unit'") else null

    override fun replace(text: String, pattern: String, replacement: String) = "${SqliteFunctions.REGEX_REPLACE}($text, $pattern, $replacement)"

    override val toChar = SqliteFunctions.TO_CHAR

    override val trunc = SqliteFunctions.TRUNC

    override val epoch = "'1970-01-01T00:00:00.000000'"

    /** SQLite divides one whole number by another to a whole number: the dividend is made a real number first. */
    override fun quotient(dividend: String, divisor: String) = "($dividend + 0.0) / $divisor"

    override fun extract(field: String, timestamp: String) = temporal("extract", timestamp, "'$field'")

    /** `java.time` numbers years as Cypher does, and its `to_char` writes one before 0 with its sign. */
    override val numbersBcFromOne = false

    /** The temporal function's [operation] of [arguments]. */
    private fun temporal(operation: String, vararg arguments: String) = "${SqliteFunctions.TEMPORAL}('$operation', ${arguments.joinToString(", ")})"
}
