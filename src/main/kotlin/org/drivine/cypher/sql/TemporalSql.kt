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

    /** [timestamp] truncated to [unit], one of [UNITS], as Neo4j truncates: null for another unit. */
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

    /**
     * Whether the store numbers a year BC from -1, with no year 0, as Postgres and Oracle do, where Cypher numbers years
     * astronomically: 0 for 1 BC and -44 for 45 BC. Where it does, a year before 1 is one more than the store's.
     */
    protected open val numbersBcFromOne = true

    /** The year of [timestamp], a number, as Cypher numbers it: see [numbersBcFromOne]. */
    open fun year(timestamp: String): String =
        if (!numbersBcFromOne) extract("YEAR", timestamp) else shared(listOf(extract("YEAR", timestamp))) { (y) -> "($y + CASE WHEN $y < 0 THEN 1 ELSE 0 END)" }

    /** The year of the ISO week of [timestamp], a number, as Cypher numbers a year: `to_char` writes one BC unsigned. */
    open fun weekYear(timestamp: String): String {
        val week = number("$toChar($timestamp, 'IYYY')")
        return if (!numbersBcFromOne) week else shared(listOf(timestamp)) { (t) -> "CASE WHEN ${extract("YEAR", t)} < 0 THEN 1 - $week ELSE $week END" }
    }

    /**
     * The date of [timestamp] as ISO 8601 writes it, `2024-03-15`, followed by the `to_char` pattern [then]: its year as
     * Cypher numbers it, `0000` for 1 BC and `-0044` for 45 BC. A store's own, and its [era], read their operand twice at
     * most: a value an aggregate's argument reads is bound nowhere, and is written out again wherever it is read.
     */
    open fun date(timestamp: String, then: String = ""): String = "$toChar($timestamp, 'YYYY-MM-DD$then')"

    /**
     * The ISO 8601 text [text] of a date or a moment, its year numbered as Cypher numbers it, as the store reads one:
     * itself, where the store numbers years so too, or the year is after 0.
     */
    open fun era(text: String): String = text

    /** The text [text] with every match of [pattern], a SQL text, replaced by [replacement]. */
    open fun replace(text: String, pattern: String, replacement: String) = "REGEXP_REPLACE($text, $pattern, $replacement)"

    /** The zoned text [text] without the offset it ends with. */
    protected fun unzoned(text: String) = replace(text, "'${Temporal.OFFSET}'", "''")

    companion object {
        /**
         * The units a timestamp truncates to, as Cypher names them, from the longest. The week-based year starts on the
         * Monday of its first ISO week. A timestamp keeps microseconds alone, so cut to them it is the same.
         */
        val UNITS = listOf(
            "millennium", "century", "decade", "year", "weekYear", "quarter", "month", "week", "day", "hour", "minute", "second",
            "millisecond", "microsecond",
        )

        /**
         * The units of many years, and how many: as Neo4j counts them, the year less its years into the span, counted toward
         * zero as Java divides. So 2017's millennium starts in 2000, 1984's century in 1900, and -1234's millennium in -1000.
         */
        val YEARS_OF = mapOf("millennium" to 1000, "century" to 100, "decade" to 10)
    }
}
