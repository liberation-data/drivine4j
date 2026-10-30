package org.drivine.backend.sqlite

import org.drivine.cypher.sql.Temporal
import org.sqlite.Function
import org.sqlite.core.Codes
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.SQLException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * `drivine_temporal(operation, …)`: what compiled Cypher asks of a timestamp on SQLite (see [SqliteTimestamps]),
 * which has no type for one and no arithmetic of the calendar, worked out with `java.time`, as `SqliteTemporalSql`
 * names each operation. Null where an argument is.
 *
 * - `local(text, kind)`: the wall clock the ISO text of a temporal [Temporal] kind reads, its offset left out: a
 *   date at its midnight, a time of day on 1970-01-01.
 * - `utc(text, kind)`: the moment the text is, in UTC; for a kind of no offset, its wall clock.
 * - `extract(timestamp, field)`: `YEAR`, `MONTH`, `DAY`, `HOUR` or `MINUTE` as a whole number, `SECOND` with its fraction.
 * - `add_months(timestamp, months)`: so many months later, a day past the month's end its last day.
 * - `add_time(timestamp, days, seconds)`: so many days and seconds later.
 * - `months_between(from, to)`: the whole months a calendar counts from one to the other, negative where `to` is before.
 * - `seconds_between(from, to)`: the seconds from one to the other, with their fraction.
 * - `truncate(timestamp, unit)`: cut to the `millennium`, `century`, `decade`, `year`, ISO `weekYear`, `quarter`, `month`, ISO `week`,
 *   `day`, `hour`, `minute`, `second`, `millisecond` or `microsecond`, as Neo4j cuts.
 *
 * A text of the kind's shape that names no such value, a 30th of February, fails the statement, as it does on Postgres.
 */
internal class SqliteTemporalFunction : Function() {

    override fun xFunc() {
        val operation = value_text(0)
        if ((1 until args()).any { value_type(it) == Codes.SQLITE_NULL }) return result()
        try {
            when (operation) {
                "local" -> result(SqliteTimestamps.write(local(value_text(1), Temporal.valueOf(value_text(2)))))
                "utc" -> result(SqliteTimestamps.write(utc(value_text(1), Temporal.valueOf(value_text(2)))))
                "extract" -> extract(clock(1), value_text(2))
                "add_months" -> result(SqliteTimestamps.write(clock(1).plusMonths(whole(2))))
                "add_time" -> result(SqliteTimestamps.write(clock(1).plusDays(whole(2)).plus(micros(value_double(3)), ChronoUnit.MICROS)))
                "months_between" -> result(ChronoUnit.MONTHS.between(clock(1), clock(2)))
                "seconds_between" -> Duration.between(clock(1), clock(2)).let { result(it.seconds + it.nano / 1e9) }
                "truncate" -> result(SqliteTimestamps.write(truncate(clock(1), value_text(2))))
                else -> throw SQLException("drivine_temporal has no operation $operation.")
            }
        } catch (e: DateTimeParseException) {
            throw SQLException("'${e.parsedString}' is no temporal value: ${e.message}", e)
        } catch (e: java.time.DateTimeException) {
            throw SQLException(e.message, e)
        }
    }

    private fun clock(arg: Int): LocalDateTime = SqliteTimestamps.read(value_text(arg))!!

    /** The argument [arg] as a whole number, rounded half away from zero, as Postgres casts a number to one. */
    private fun whole(arg: Int): Long =
        if (value_type(arg) == Codes.SQLITE_INTEGER) value_long(arg) else BigDecimal(value_text(arg)).setScale(0, RoundingMode.HALF_UP).toLong()

    private fun micros(seconds: Double): Long = Math.round(seconds * 1_000_000)

    private fun local(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE -> LocalDate.parse(text).atStartOfDay()
        Temporal.LOCAL_DATE_TIME -> LocalDateTime.parse(text)
        Temporal.DATE_TIME -> LocalDateTime.parse(unzoned(text))
        Temporal.LOCAL_TIME -> LocalTime.parse(text).atDate(EPOCH_DAY)
        Temporal.TIME -> LocalTime.parse(unzoned(text)).atDate(EPOCH_DAY)
        Temporal.DURATION -> throw SQLException("A duration is no moment.")
    }.truncatedTo(ChronoUnit.MICROS)

    private fun utc(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE_TIME -> OffsetDateTime.parse(withColon(text)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        Temporal.TIME -> OffsetDateTime.parse("${EPOCH_DAY}T${withColon(text)}").withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        else -> local(text, kind)
    }.truncatedTo(ChronoUnit.MICROS)

    private fun extract(clock: LocalDateTime, field: String) = when (field) {
        "YEAR" -> result(clock.year)
        "MONTH" -> result(clock.monthValue)
        "DAY" -> result(clock.dayOfMonth)
        "HOUR" -> result(clock.hour)
        "MINUTE" -> result(clock.minute)
        "SECOND" -> result(clock.second + clock.nano / 1e9)
        else -> throw SQLException("A timestamp has no field $field.")
    }

    private fun truncate(clock: LocalDateTime, unit: String): LocalDateTime = when (unit) {
        "year" -> clock.toLocalDate().withDayOfYear(1).atStartOfDay()
        "quarter" -> clock.toLocalDate().withDayOfMonth(1).withMonth((clock.monthValue - 1) / 3 * 3 + 1).atStartOfDay()
        "month" -> clock.toLocalDate().withDayOfMonth(1).atStartOfDay()
        "week" -> clock.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
        "day" -> clock.truncatedTo(ChronoUnit.DAYS)
        "hour" -> clock.truncatedTo(ChronoUnit.HOURS)
        "minute" -> clock.truncatedTo(ChronoUnit.MINUTES)
        "second" -> clock.truncatedTo(ChronoUnit.SECONDS)
        "millisecond" -> clock.truncatedTo(ChronoUnit.MILLIS)
        "microsecond" -> clock.truncatedTo(ChronoUnit.MICROS)
        // The year less its years into the span, counted toward zero, as Neo4j counts: -1234's millennium is -1000.
        "millennium" -> clock.toLocalDate().withYear(clock.year - clock.year % 1000).withDayOfYear(1).atStartOfDay()
        "century" -> clock.toLocalDate().withYear(clock.year - clock.year % 100).withDayOfYear(1).atStartOfDay()
        "decade" -> clock.toLocalDate().withYear(clock.year - clock.year % 10).withDayOfYear(1).atStartOfDay()
        "weekYear" -> clock.toLocalDate().with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(ChronoField.DAY_OF_WEEK, 1).atStartOfDay()
        else -> throw SQLException("A timestamp is not truncated to $unit.")
    }

    /** [text] without the offset it ends with. */
    private fun unzoned(text: String) = OFFSET.replace(text, "")

    /** [text] with its offset written as `java.time` reads one: with a colon, or `Z`. */
    private fun withColon(text: String) = OFFSET.replace(text) { match ->
        val offset = match.value
        if (offset == "Z" || offset.contains(':')) offset else "${offset.substring(0, 3)}:${offset.substring(3)}"
    }

    private companion object {
        val EPOCH_DAY: LocalDate = LocalDate.of(1970, 1, 1)
        val OFFSET = Regex(Temporal.OFFSET)
    }
}
