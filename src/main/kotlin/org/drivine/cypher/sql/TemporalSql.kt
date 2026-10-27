package org.drivine.cypher.sql

/**
 * A store's SQL for temporal values, which [Temporals] works openCypher's out of. Its texts and timestamps are
 * SQL: a text is a value's ISO 8601 text, a timestamp a SQL timestamp of no zone, a number a SQL number.
 * [shared] is the compiler's: a value it is given is written once where it is read more than once.
 */
abstract class TemporalSql(protected val shared: (List<String>, (List<String>) -> String) -> String) {

    /** The wall clock the text [text] of [kind] reads, an offset it has left out: a date at its midnight, a time of day on 1970-01-01. */
    abstract fun local(text: String, kind: Temporal): String

    /** The moment the text [text] of [kind] is, in UTC; for a kind of no zone, the wall clock it reads. */
    abstract fun utc(text: String, kind: Temporal): String

    /** The moment the statement runs, in UTC. */
    abstract val now: String

    /** Whether the text [text] matches the regular expression [pattern], a SQL text. */
    abstract fun matches(text: String, pattern: String): String

    /** The text the group [group] of the regular expression [pattern] matches in [text]: null where it matches none. */
    abstract fun group(text: String, pattern: String, group: Int): String

    /** The text [text] as a number: null where it is null or empty. */
    abstract fun number(text: String): String

    /** The timestamp [timestamp] as a text of one width, `2024-03-15T10:30:00.000000`, which orders as the timestamps do. */
    abstract fun sortable(timestamp: String): String

    /** The microseconds of the second of the timestamp [timestamp], as six digits. */
    abstract fun micros(timestamp: String): String

    /** The ISO day of the week of [timestamp], from 1 for Monday to 7. */
    abstract fun dayOfWeek(timestamp: String): String

    /** [timestamp] [months] later, a day past the month's end its last day. */
    abstract fun addMonths(timestamp: String, months: String): String

    /** [timestamp] [days] and [seconds] later, numbers each. */
    abstract fun addTime(timestamp: String, days: String, seconds: String): String

    /** The whole months from [from] to [to], as a calendar counts them: negative where [to] is before. */
    abstract fun monthsBetween(from: String, to: String): String

    /** The seconds from [from] to [to], a fraction of one among them. */
    abstract fun secondsBetween(from: String, to: String): String

    /** [timestamp] truncated to [unit] (`year`, `quarter`, `month`, `week`, `day`, `hour`, `minute`, `second`, `millisecond`): null for another unit. */
    abstract fun truncate(unit: String, timestamp: String): String?

    /** The name of the function that writes a timestamp or a number as text in a pattern (`'YYYY-MM-DD'`, `'FM0000'`), as Postgres's and Oracle's `to_char` does. */
    open val toChar = "to_char"

    /** The name of the function that cuts a number's fraction off, as `TRUNC` does. */
    open val trunc = "TRUNC"

    /** The timestamp of the epoch, 1970-01-01 at midnight. */
    open val epoch = "TIMESTAMP '1970-01-01 00:00:00'"

    /** [dividend] divided by [divisor], numbers each, with its fraction kept where both are whole numbers. */
    open fun quotient(dividend: String, divisor: String) = "$dividend / $divisor"

    /** The field [field] (`YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND` with its fraction) of the timestamp [timestamp], a number. */
    open fun extract(field: String, timestamp: String) = "EXTRACT($field FROM $timestamp)"

    /** The text [text] with every match of [pattern], a SQL text, replaced by [replacement]. */
    open fun replace(text: String, pattern: String, replacement: String) = "REGEXP_REPLACE($text, $pattern, $replacement)"

    /** The zoned text [text] without the offset it ends with. */
    protected fun unzoned(text: String) = replace(text, "'${Temporal.OFFSET}'", "''")
}
