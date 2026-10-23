package org.drivine.cypher

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * openCypher's temporal values give the same fields, arithmetic and comparisons on a SQL store, compiled, as on
 * Neo4j: the same statements are run on Neo4j, Postgres and Oracle. A graph engine's driver gives a temporal
 * value as one of its own, and a SQL store as its ISO text, so the tests read values' fields, and the text
 * where only a SQL store gives it.
 */
abstract class CypherTemporalContract {

    abstract val pm: PersistenceManager

    abstract fun clear()

    /** Whether the statement under test is compiled to SQL: what only a SQL store gives is asked of it alone. */
    open val compiles: Boolean = false

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    private fun one(cypher: String, parameters: Map<String, Any?> = emptyMap()): Map<*, *> = query(cypher, parameters).single() as Map<*, *>

    @BeforeEach
    fun graph() = clear()

    @Test
    fun `a date, a time and a moment made of a text or a map give their fields`() {
        assertEquals(
            mapOf("year" to 2024L, "month" to 3L, "day" to 15L, "dayOfWeek" to 5L, "ordinalDay" to 75L, "week" to 11L, "quarter" to 1L),
            one("WITH date('2024-03-15') AS d RETURN {year: d.year, month: d.month, day: d.day, dayOfWeek: d.dayOfWeek, ordinalDay: d.ordinalDay, week: d.week, quarter: d.quarter}"),
        )
        assertEquals(
            mapOf(
                "hour" to 10L, "minute" to 30L, "second" to 45L, "millisecond" to 123L, "offsetMinutes" to 120L, "timezone" to "+02:00",
                "epochMillis" to Instant.parse("2024-03-15T08:30:45.123Z").toEpochMilli(),
            ),
            one(
                """
                WITH datetime('2024-03-15T10:30:45.123+02:00') AS t
                RETURN {hour: t.hour, minute: t.minute, second: t.second, millisecond: t.millisecond, offsetMinutes: t.offsetMinutes, timezone: t.timezone, epochMillis: t.epochMillis}
                """
            ),
        )
        assertEquals(
            mapOf("leap" to 60L, "noon" to 1704110400L, "local" to 10L, "zoned" to 30L, "minutes" to 45L),
            one(
                """
                RETURN {leap: date({year: 2024, month: 2, day: 29}).ordinalDay,
                        noon: datetime({year: 2024, month: 1, day: 1, hour: 12, timezone: 'Z'}).epochSeconds,
                        local: localtime('10:30').hour, zoned: time('10:30:00Z').minute,
                        minutes: localdatetime({year: 2024, month: 1, day: 1, hour: 9, minute: 45}).minute}
                """
            ),
        )
    }

    @Test
    fun `a duration made of a text or a map gives its fields`() {
        assertEquals(
            mapOf("years" to 1L, "months" to 14L, "monthsOfYear" to 2L, "days" to 3L, "hours" to 4L, "minutes" to 245L, "minutesOfHour" to 5L, "seconds" to 14706L, "secondsOfMinute" to 6L),
            one(
                """
                WITH duration('P1Y2M3DT4H5M6S') AS d
                RETURN {years: d.years, months: d.months, monthsOfYear: d.monthsOfYear, days: d.days, hours: d.hours,
                        minutes: d.minutes, minutesOfHour: d.minutesOfHour, seconds: d.seconds, secondsOfMinute: d.secondsOfMinute}
                """
            ),
        )
        assertEquals(mapOf("days" to 1L, "hours" to 12L), one("WITH duration({days: 1, hours: 12}) AS d RETURN {days: d.days, hours: d.hours}"))
    }

    @Test
    fun `a duration is added to a moment, a month past the end of a shorter one its last day, and durations to each other`() {
        assertEquals(
            mapOf("endOfFebruary" to 29L, "nextDay" to 16L, "fortnightBefore" to 2L, "days" to 1L, "hours" to 12L, "doubled" to 120L),
            one(
                """
                RETURN {endOfFebruary: (date('2024-01-31') + duration('P1M')).day,
                        nextDay: (datetime('2024-03-15T23:30:00Z') + duration('PT1H')).day,
                        fortnightBefore: (date('2024-03-15') - duration('P15D')).month,
                        days: (duration('P1D') + duration('PT12H')).days,
                        hours: (duration('P1D') + duration('PT12H')).hours,
                        doubled: (duration('PT1H') * 2).minutes}
                """
            ),
        )
        // A moment read from the store, its kind told by its text.
        query("CREATE (:CyTemporal {id: 'a', day: date('2024-03-15')}), (:CyTemporal {id: 'b', day: date('2024-01-01')})")
        assertEquals(listOf(16L, 2L), query("MATCH (n:CyTemporal) RETURN (n.day + duration('P1D')).day ORDER BY n.id"))
        // Its bound values nested in one another, which Postgres would pull up and write out again as it plans, out of memory.
        assertEquals(listOf(18L, 4L), query("MATCH (n:CyTemporal) RETURN (n.day + duration({days: \$days})).day ORDER BY n.id", mapOf("days" to 3)))
    }

    @Test
    fun `moments compare as the moments they are, whatever their offsets, and a duration only as equal or not`() {
        assertEquals(
            mapOf("before" to true, "otherOffset" to false, "earlier" to true, "durations" to false),
            one(
                """
                RETURN {before: date('2024-03-15') < date('2024-03-16'),
                        otherOffset: datetime('2024-03-15T10:00:00+02:00') = datetime('2024-03-15T08:00:00Z'),
                        earlier: datetime('2024-03-15T10:00:00+02:00') < datetime('2024-03-15T08:00:01Z'),
                        durations: duration('P1D') = duration('PT24H')}
                """
            ),
        )
        query("CREATE (:CyTemporal {id: 'a', at: '2024-06-01T00:00:00Z'}), (:CyTemporal {id: 'b', at: '2023-12-31T23:00:00-02:00'}), (:CyTemporal {id: 'c', at: '2023-06-01T00:00:00Z'})")
        assertEquals(listOf("a", "b"), query("MATCH (n:CyTemporal) WHERE datetime(n.at) > datetime('2024-01-01T00:00:00Z') RETURN n.id ORDER BY n.id"))
    }

    @Test
    fun `the duration between two moments is in months, then days, then seconds, or in one of them`() {
        assertEquals(
            mapOf("months" to 2L, "days" to 5L, "inDays" to 60L, "inSeconds" to 3600L, "inMonths" to 1L),
            one(
                """
                WITH duration.between(date('2024-01-15'), date('2024-03-20')) AS d
                RETURN {months: d.months, days: d.days,
                        inDays: duration.inDays(date('2024-01-01'), date('2024-03-01')).days,
                        inSeconds: duration.inSeconds(datetime('2024-01-01T00:00:00Z'), datetime('2024-01-01T01:00:00Z')).seconds,
                        inMonths: duration.inMonths(date('2024-01-31'), date('2024-03-01')).months}
                """
            ),
        )
    }

    @Test
    fun `a moment truncates to a unit, and the moment a statement runs is one moment`() {
        assertEquals(
            mapOf("firstOfMonth" to 1L, "onTheHour" to 0L, "midnight" to 0L, "one" to true, "clock" to true, "time" to true, "local" to true),
            one(
                """
                RETURN {firstOfMonth: date.truncate('month', date('2024-03-15')).day,
                        onTheHour: datetime.truncate('hour', datetime('2024-03-15T10:30:45Z')).minute,
                        midnight: datetime.truncate('day', datetime('2024-03-15T10:30:45Z')).hour,
                        one: date.statement() = date(), clock: localtime() = localtime.statement(), time: time() = time.statement(),
                        local: localdatetime() = localdatetime.statement()}
                """
            ),
        )
    }

    @Test
    fun `a parameter of a java time type is a temporal value of its kind`() {
        assertEquals(
            mapOf("year" to 2024L, "later" to true),
            one(
                "RETURN {year: \$day.year, later: \$at > datetime('2024-01-01T00:00:00Z')}",
                mapOf("day" to LocalDate.parse("2024-03-15"), "at" to Instant.parse("2024-06-01T00:00:00Z")),
            ),
        )
    }

    @Test
    fun `a month added to the last day of a month keeps its day where the month it lands in has it`() {
        assertEquals(
            mapOf("leap" to 29L, "april" to 30L, "back" to 29L, "clockDay" to 29L, "clockMillis" to 500L),
            one(
                """
                RETURN {leap: (date('2024-02-29') + duration('P1M')).day,
                        april: (date('2024-04-30') + duration('P1M')).day,
                        back: (date('2024-03-31') - duration('P1M')).day,
                        clockDay: (localdatetime('2024-02-29T10:30:00.5') + duration('P1M')).day,
                        clockMillis: (localdatetime('2024-02-29T10:30:00.5') + duration('P1M')).millisecond}
                """
            ),
        )
    }

    @Test
    fun `the months between two moments are whole months as a calendar counts them, of times and across offsets`() {
        assertEquals(
            mapOf(
                "lastDaysMonths" to 0L, "lastDaysDays" to 29L, "earlierMonths" to 0L, "earlierDays" to 30L, "earlierHours" to 18L,
                "backMonths" to -2L, "backDays" to -5L, "times" to 90L, "localTimes" to -1L, "offsets" to 2L, "mixedDays" to 1L, "mixedHours" to 12L,
            ),
            one(
                """
                RETURN {lastDaysMonths: duration.between(date('2024-01-31'), date('2024-02-29')).months,
                        lastDaysDays: duration.between(date('2024-01-31'), date('2024-02-29')).days,
                        earlierMonths: duration.between(datetime('2024-01-15T12:00:00Z'), datetime('2024-02-15T06:00:00Z')).months,
                        earlierDays: duration.between(datetime('2024-01-15T12:00:00Z'), datetime('2024-02-15T06:00:00Z')).days,
                        earlierHours: duration.between(datetime('2024-01-15T12:00:00Z'), datetime('2024-02-15T06:00:00Z')).hours,
                        backMonths: duration.between(date('2024-03-20'), date('2024-01-15')).months,
                        backDays: duration.between(date('2024-03-20'), date('2024-01-15')).days,
                        times: duration.between(time('10:00:00+02:00'), time('09:30:00Z')).minutes,
                        localTimes: duration.between(localtime('10:00'), localtime('09:00')).hours,
                        offsets: duration.between(datetime('2024-03-15T10:00:00+02:00'), datetime('2024-03-15T10:00:00Z')).hours,
                        mixedDays: duration.between(date('2024-01-01'), datetime('2024-01-02T12:00:00+01:00')).days,
                        mixedHours: duration.between(date('2024-01-01'), datetime('2024-01-02T12:00:00+01:00')).hours}
                """
            ),
        )
    }

    @Test
    fun `an offset written without a colon is read as the offset it is`() {
        val expected = mapOf("timezone" to "+02:00", "offsetMinutes" to 120L, "hour" to 10L, "epochMillis" to Instant.parse("2024-03-15T08:30:00Z").toEpochMilli())
        assertEquals(
            expected,
            one("WITH datetime('2024-03-15T10:30:00+0200') AS t RETURN {timezone: t.timezone, offsetMinutes: t.offsetMinutes, hour: t.hour, epochMillis: t.epochMillis}"),
        )
        assertEquals(mapOf("timezone" to "-05:30", "minute" to 15L), one("WITH time('10:15-0530') AS t RETURN {timezone: t.timezone, minute: t.minute}"))
        query("CREATE (:CyTemporal {id: 'a', at: '2024-03-15T10:30:00+0200'})")
        assertEquals(
            expected,
            one("MATCH (n:CyTemporal) WITH datetime(n.at) AS t RETURN {timezone: t.timezone, offsetMinutes: t.offsetMinutes, hour: t.hour, epochMillis: t.epochMillis}"),
        )
    }

    @Test
    fun `a zero offset, however written, is UTC's`() {
        val expected = mapOf("offset" to "Z", "timezone" to "Z", "utc" to true, "time" to "Z")
        assertEquals(
            expected,
            one("WITH datetime('2024-03-15T10:00:00+00:00') AS t RETURN {offset: t.offset, timezone: t.timezone, utc: t = datetime('2024-03-15T10:00:00Z'), time: time('10:00-0000').offset}"),
        )
        query("CREATE (:CyTemporal {id: 'a', at: '2024-03-15T10:00:00+00:00'})")
        assertEquals(
            expected,
            one(
                """
                MATCH (n:CyTemporal) WITH datetime(n.at) AS t
                RETURN {offset: t.offset, timezone: (t + duration('PT1H')).timezone, utc: t = datetime('2024-03-15T10:00:00Z'), time: time(t).offset}
                """
            ),
        )
    }

    @Test
    fun `a duration is negative, of weeks, or of a fraction of a second`() {
        assertEquals(
            mapOf("days" to -1L, "hours" to -2L, "weekDays" to 14L, "weeks" to 2L, "millis" to 1500L, "secs" to 1L, "mapWeeks" to 8L, "dayBefore" to 29L, "weekLater" to 22L),
            one(
                """
                WITH duration('-P1DT2H') AS n, duration('P2W') AS w, duration('PT1.5S') AS f
                RETURN {days: n.days, hours: n.hours, weekDays: w.days, weeks: w.weeks, millis: f.milliseconds, secs: f.seconds,
                        mapWeeks: duration({weeks: 1, days: 1}).days, dayBefore: (date('2024-03-01') + duration('-P1D')).day,
                        weekLater: (date('2024-03-15') + duration('P1W')).day}
                """
            ),
        )
    }

    @Test
    fun `a moment keeps the fraction of its second, and a time of day wraps past midnight`() {
        assertEquals(
            mapOf("micro" to 123456L, "nano" to 123456000L, "milli" to 123L, "nextMinute" to 31L, "carried" to 750L, "wrapped" to 0L, "zonedMinute" to 15L, "zone" to "+02:00"),
            one(
                """
                WITH datetime('2024-03-15T10:30:45.123456Z') AS t
                RETURN {micro: t.microsecond, nano: t.nanosecond, milli: t.millisecond,
                        nextMinute: (datetime('2024-03-15T10:30:59.999Z') + duration('PT0.002S')).minute,
                        carried: (localtime('10:30:45.5') + duration('PT0.25S')).millisecond,
                        wrapped: (localtime('23:30') + duration('PT1H')).hour,
                        zonedMinute: (time('10:00:00+02:00') - duration('PT45M')).minute,
                        zone: (time('10:00:00+02:00') - duration('PT45M')).timezone}
                """
            ),
        )
    }

    @Test
    fun `a moment gives its week year and offset fields, a duration its parts of a larger unit, and two zoned values are unequal at other offsets`() {
        assertEquals(
            mapOf(
                "weekYear" to 2025L, "week" to 1L, "weekDay" to 1L, "offset" to "-05:30", "offsetSeconds" to -19800L, "unequal" to true, "same" to false,
                "ordered" to true, "transaction" to true, "realtime" to true,
            ),
            one(
                """
                WITH datetime('2024-12-30T10:00:00Z') AS t, datetime('2024-03-15T10:00:00-05:30') AS u
                RETURN {weekYear: t.weekYear, week: t.week, weekDay: t.weekDay, offset: u.offset, offsetSeconds: u.offsetSeconds,
                        unequal: datetime('2024-03-15T10:00:00+02:00') <> datetime('2024-03-15T08:00:00Z'), same: t <> datetime('2024-12-30T10:00:00Z'),
                        ordered: (duration('P1D') < duration('P2D')) IS NULL, transaction: datetime.transaction().year > 2000, realtime: date.realtime().year > 2000}
                """
            ),
        )
        assertEquals(
            mapOf("quarters" to 6L, "quartersOfYear" to 2L, "monthsOfQuarter" to 2L, "weeks" to 2L, "daysOfWeek" to 3L, "ms" to 6L, "us" to 6007L, "ns" to 6007000L, "minutesOfHour" to -30L),
            one(
                """
                WITH duration('P1Y8M17DT3H4M5.006007S') AS d
                RETURN {quarters: d.quarters, quartersOfYear: d.quartersOfYear, monthsOfQuarter: d.monthsOfQuarter, weeks: d.weeks, daysOfWeek: d.daysOfWeek,
                        ms: d.millisecondsOfSecond, us: d.microsecondsOfSecond, ns: d.nanosecondsOfSecond, minutesOfHour: duration('PT-90M').minutesOfHour}
                """
            ),
        )
    }

    @Test
    fun `a duration is divided and scaled`() {
        assertEquals(
            mapOf("quarterHour" to 15L, "halfMonthDays" to 15L, "halfMonthHours" to 5L, "tripled" to 3L, "halfDay" to 12L),
            one(
                """
                RETURN {quarterHour: (duration('PT1H') / 4).minutes, halfMonthDays: (duration('P1M') / 2).days,
                        halfMonthHours: (duration('P1M') / 2).hours, tripled: (3 * duration('P1D')).days, halfDay: (duration('P1D') * 0.5).hours}
                """
            ),
        )
    }

    @Test
    fun `a moment truncates to each unit, keeping its offset, and a time and a date truncate too`() {
        assertEquals(
            mapOf(
                "year" to listOf(1L, 1L, 0L, 0L, 0L, 0L), "quarter" to listOf(4L, 1L, 0L, 0L, 0L, 0L), "month" to listOf(5L, 1L, 0L, 0L, 0L, 0L),
                "week" to listOf(5L, 13L, 0L, 0L, 0L, 0L), "day" to listOf(5L, 15L, 0L, 0L, 0L, 0L), "hour" to listOf(5L, 15L, 10L, 0L, 0L, 0L),
                "minute" to listOf(5L, 15L, 10L, 30L, 0L, 0L), "second" to listOf(5L, 15L, 10L, 30L, 45L, 0L),
                "millisecond" to listOf(5L, 15L, 10L, 30L, 45L, 123000L), "zone" to "+02:00",
            ),
            one(
                """
                WITH datetime('2024-05-15T10:30:45.123456+02:00') AS t
                WITH datetime.truncate('year', t) AS year, datetime.truncate('quarter', t) AS quarter, datetime.truncate('month', t) AS month,
                     datetime.truncate('week', t) AS week, datetime.truncate('day', t) AS day, datetime.truncate('hour', t) AS hour,
                     datetime.truncate('minute', t) AS minute, datetime.truncate('second', t) AS second, datetime.truncate('millisecond', t) AS millisecond
                RETURN {year: [year.month, year.day, year.hour, year.minute, year.second, year.microsecond],
                        quarter: [quarter.month, quarter.day, quarter.hour, quarter.minute, quarter.second, quarter.microsecond],
                        month: [month.month, month.day, month.hour, month.minute, month.second, month.microsecond],
                        week: [week.month, week.day, week.hour, week.minute, week.second, week.microsecond],
                        day: [day.month, day.day, day.hour, day.minute, day.second, day.microsecond],
                        hour: [hour.month, hour.day, hour.hour, hour.minute, hour.second, hour.microsecond],
                        minute: [minute.month, minute.day, minute.hour, minute.minute, minute.second, minute.microsecond],
                        second: [second.month, second.day, second.hour, second.minute, second.second, second.microsecond],
                        millisecond: [millisecond.month, millisecond.day, millisecond.hour, millisecond.minute, millisecond.second, millisecond.microsecond],
                        zone: day.timezone}
                """
            ),
        )
        assertEquals(
            mapOf("localHour" to 0L, "timeZone" to "+02:00", "timeSecond" to 0L, "monday" to 13L, "firstOfMonth" to 1L, "localMidnight" to 0L),
            one(
                """
                RETURN {localHour: localtime.truncate('hour', localtime('10:30:45')).minute,
                        timeZone: time.truncate('minute', time('10:30:45+02:00')).timezone,
                        timeSecond: time.truncate('minute', time('10:30:45+02:00')).second,
                        monday: date.truncate('week', date('2024-05-15')).day,
                        firstOfMonth: date.truncate('month', datetime('2024-05-15T10:30:00Z')).day,
                        localMidnight: localdatetime.truncate('day', datetime('2024-05-15T10:30:00Z')).hour}
                """
            ),
        )
    }

    @Test
    fun `a moment and a time made of a map take their fraction of a second and their offset`() {
        assertEquals(
            mapOf("millis" to Instant.parse("2024-03-15T08:30:45.123Z").toEpochMilli(), "zone" to "+02:00", "offset" to 120L, "micro" to 7L, "nano" to 5L),
            one(
                """
                RETURN {millis: datetime({year: 2024, month: 3, day: 15, hour: 10, minute: 30, second: 45, millisecond: 123, timezone: '+02:00'}).epochMillis,
                        zone: datetime({year: 2024, month: 3, day: 15, hour: 10, timezone: '+02:00'}).timezone,
                        offset: time({hour: 10, minute: 30, timezone: '+02:00'}).offsetMinutes,
                        micro: localtime({hour: 10, minute: 30, second: 5, microsecond: 7}).microsecond,
                        nano: localtime({hour: 1, minute: 0, second: 0, nanosecond: 5000}).microsecond}
                """
            ),
        )
    }

    @Test
    fun `a temporal value of one kind is made of a value of another`() {
        assertEquals(
            mapOf("day" to 15L, "hour" to 23L, "zone" to "-02:00", "clock" to 30L, "midnight" to 15L),
            one(
                """
                WITH datetime('2024-03-15T23:30:00-02:00') AS t
                RETURN {day: date(t).day, hour: localdatetime(t).hour, zone: time(t).timezone, clock: localtime(t).minute,
                        midnight: date(localdatetime('2024-03-15T10:30')).day}
                """
            ),
        )
    }

    @Test
    fun `a temporal value of null is null`() {
        assertEquals(
            mapOf(
                "date" to true, "datetime" to true, "duration" to true, "plus" to true, "field" to true, "between" to true, "times" to true,
                "sum" to true, "moved" to true, "days" to true,
            ),
            one(
                """
                RETURN {date: date(null) IS NULL, datetime: datetime(null) IS NULL, duration: duration(null) IS NULL,
                        plus: (null + duration('P1D')) IS NULL, field: date(null).year IS NULL,
                        between: duration.between(date(null), date('2024-03-15')) IS NULL, times: (duration('P1D') * null) IS NULL,
                        sum: (duration('P1D') + duration(null)) IS NULL, moved: (date('2024-03-15') + duration(null)) IS NULL,
                        days: duration(null).days IS NULL}
                """
            ),
        )
        // A property the node has not, as a value the store gives.
        query("CREATE (:CyTemporal {id: 'a', to: date('2024-03-15')})")
        assertEquals(listOf(true), query("MATCH (n:CyTemporal) RETURN duration.between(n.from, n.to) IS NULL"))
        // A moment or a time of day made of it, and moved, is null, and is written as none.
        assertEquals(
            listOf(listOf(null, null, null, null, true)),
            query(
                """
                MATCH (n:CyTemporal) SET n.next = datetime(n.at) + duration('P1D')
                RETURN [datetime(n.at) + duration('P1D'), localdatetime(n.at) + duration('P1D'), time(n.at) + duration('PT1H'),
                        localtime(n.at) + duration('PT1H'), n.next IS NULL]
                """
            ),
        )
    }

    @Test
    fun `a temporal value written to the store is read back as the value it was`() {
        query("CREATE (:CyTemporal {id: 'a', at: datetime('2024-03-15T10:30:00+02:00'), day: date('2024-03-15'), d: duration('P1M2D')})")
        assertEquals(
            mapOf(
                "hour" to 10L, "zone" to "+02:00", "months" to 1L, "days" to 2L, "plus" to 2L, "minus" to 14L, "durationPlus" to 3L,
                "otherOffset" to false, "same" to true, "later" to 3L, "reversed" to 2L, "shorter" to 1L,
            ),
            one(
                """
                MATCH (n:CyTemporal)
                RETURN {hour: n.at.hour, zone: n.at.timezone, months: n.d.months, days: n.d.days, plus: (date('2024-01-31') + n.d).day,
                        minus: (n.day - duration('P1D')).day, durationPlus: (n.d + duration('P1D')).days,
                        otherOffset: n.at = datetime('2024-03-15T08:30:00Z'), same: datetime(n.at) = datetime('2024-03-15T10:30:00+02:00'),
                        later: (duration('P1D') + n.day).month, reversed: (n.d + date('2024-01-31')).day,
                        shorter: (duration('P1M3D') - n.d).days}
                """
            ),
        )
    }

    @Test
    fun `a temporal value is carried through WITH, ORDER BY and collect`() {
        assertEquals(listOf(listOf(1L, 3L)), query("UNWIND [date('2024-03-15'), date('2024-01-02')] AS d WITH d ORDER BY d RETURN collect(d.month)"))
        assertEquals(listOf(listOf(10L)), query("UNWIND [datetime('2024-03-15T10:00:00+02:00')] AS t WITH collect(t) AS ts RETURN [x IN ts | x.hour]"))
        query("CREATE (:CyTemporal {id: 'a', at: datetime('2024-03-15T10:00:00+02:00')}), (:CyTemporal {id: 'b', at: datetime('2024-03-15T09:00:00Z')})")
        assertEquals(listOf("a", "b"), query("MATCH (n:CyTemporal) RETURN n.id ORDER BY datetime(n.at)"))
        // Bound by a WITH, a zoned value keeps its kind: it sorts and compares as the moment it is.
        assertEquals(listOf("a", "b"), query("MATCH (n:CyTemporal) WITH n, datetime(n.at) AS t RETURN n.id ORDER BY t"))
        assertEquals(
            listOf(true),
            query("WITH datetime('2024-03-15T10:00:00+02:00') AS a, datetime('2024-03-15T09:00:00Z') AS b RETURN a < b"),
        )
    }

    @Test
    fun `a temporal value keeps its kind through coalesce, CASE, a list, UNWIND, an index, collect and a list comprehension`() {
        assertEquals(
            mapOf("coalesce" to 120L, "case" to 120L, "index" to 120L, "comprehension" to listOf(120L), "projected" to listOf(240L)),
            one(
                """
                RETURN {coalesce: (coalesce(null, duration('PT1H')) * 2).minutes,
                        case: (CASE WHEN true THEN duration('PT1H') ELSE duration('PT2H') END * 2).minutes,
                        index: ([duration('PT1H')][0] * 2).minutes, comprehension: [d IN [duration('PT1H')] | (d * 2).minutes],
                        projected: [x IN [d IN [duration('PT1H')] | d * 2] | (x * 2).minutes]}
                """
            ),
        )
        assertEquals(listOf(120L, 240L), query("UNWIND [duration('PT1H'), duration('PT2H')] AS d RETURN (d * 2).minutes"))
        assertEquals(listOf(120L), query("WITH [duration('PT1H')] AS ds, 0 AS i RETURN (ds[i] * 2).minutes"))
        assertEquals(listOf(listOf(120L)), query("UNWIND [duration('PT1H')] AS d WITH collect(d) AS ds RETURN [x IN ds | (x * 2).minutes]"))
        // A zoned value so kept sorts as the moment it is, whatever its offset.
        assertEquals(
            listOf(10L, 9L),
            query("WITH [datetime('2024-03-15T10:00:00+02:00'), datetime('2024-03-15T09:00:00Z')] AS ts UNWIND ts AS t RETURN t.hour ORDER BY t"),
        )
        // A list reversed, its tail, or two lists of one kind together, keep their items' kind.
        assertEquals(listOf(10L, 9L), query("UNWIND reverse([datetime('2024-03-15T09:00:00Z'), datetime('2024-03-15T10:00:00+02:00')]) AS t RETURN t.hour ORDER BY t"))
        assertEquals(
            listOf(10L, 9L),
            query("UNWIND tail([datetime('2024-01-01T00:00:00Z'), datetime('2024-03-15T09:00:00Z'), datetime('2024-03-15T10:00:00+02:00')]) AS t RETURN t.hour ORDER BY t"),
        )
        assertEquals(listOf(10L, 9L), query("UNWIND [datetime('2024-03-15T09:00:00Z')] + [datetime('2024-03-15T10:00:00+02:00')] AS t RETURN t.hour ORDER BY t"))
        assertEquals(
            listOf(10L, 9L),
            query("UNWIND [1, 2] AS i WITH CASE i WHEN 1 THEN datetime('2024-03-15T10:00:00+02:00') ELSE datetime('2024-03-15T09:00:00Z') END AS t RETURN t.hour ORDER BY t"),
        )
    }

    @Test
    fun `a zoned value keeps its kind in a quantifier, reduce, a pattern comprehension and a statement that writes`() {
        assertEquals(
            listOf(true),
            query(
                """
                WITH [datetime('2024-03-15T10:00:00+02:00')] AS xs, [datetime('2024-03-15T09:00:00Z')] AS ys
                RETURN any(x IN xs WHERE any(y IN ys WHERE x < y))
                """
            ),
        )
        assertEquals(
            listOf(10L),
            query(
                """
                WITH [datetime('2024-03-15T10:00:00+02:00'), datetime('2024-03-15T09:00:00Z')] AS ts
                RETURN reduce(m = null, t IN ts | CASE WHEN m IS NULL OR t < m THEN t ELSE m END).hour
                """
            ),
        )
        assertEquals(
            listOf(true),
            query("WITH datetime('2024-03-15T10:00:00+02:00') AS a, datetime('2024-03-15T09:00:00Z') AS b RETURN reduce(s = false, x IN [1] | a < b)"),
        )
        assertEquals(
            listOf(true),
            query(
                "RETURN reduce(s = false, x IN [1] | \$a < \$b)",
                mapOf(
                    "a" to java.time.ZonedDateTime.of(2024, 3, 15, 10, 0, 0, 0, java.time.ZoneOffset.ofHours(2)),
                    "b" to java.time.ZonedDateTime.of(2024, 3, 15, 9, 0, 0, 0, java.time.ZoneOffset.UTC),
                ),
            ),
        )
        query(
            "CREATE (x:CyTemporal {id: 'x'}), (x)-[:AT]->(:CyTemporal {id: 'a', at: datetime('2024-03-15T10:00:00+02:00')}), " +
                "(x)-[:AT]->(:CyTemporal {id: 'b', at: datetime('2024-03-15T09:00:00Z')})"
        )
        assertEquals(listOf(10L, 9L), query("MATCH (x:CyTemporal {id: 'x'}) UNWIND [(x)-->(m) | datetime(m.at)] AS t RETURN t.hour ORDER BY t"))
        assertEquals(
            listOf(true),
            query("WITH datetime('2024-03-15T10:00:00+02:00') AS a, datetime('2024-03-15T09:00:00Z') AS b CREATE (:CyTemporal {id: 'c'}) RETURN a < b"),
        )
        // Through writes one after another, and of the value a later WITH gives a variable again.
        assertEquals(
            listOf(true),
            query(
                """
                CREATE (n:CyTemporal {id: 'd'}) WITH n, datetime('2024-03-15T10:00:00+02:00') AS a SET n.x = 1
                WITH n, a, datetime('2024-03-15T09:00:00Z') AS b SET n.y = 2 RETURN a < b
                """
            ),
        )
        assertEquals(
            listOf(10L),
            query("WITH duration('P1D') AS a CREATE (n:CyTemporal {id: 'e'}) WITH n, datetime('2024-03-15T10:00:00+02:00') AS a SET n.v = 1 RETURN a.hour"),
        )
        // A variable a FOREACH or a CALL { … } binds again within is, after it, the value it was.
        assertEquals(
            listOf(10L),
            query("WITH datetime('2024-03-15T10:00:00+02:00') AS x FOREACH (x IN [duration('P1D')] | CREATE (:CyTemporal {id: 'f'})) RETURN x.hour"),
        )
        assertEquals(
            listOf(10L),
            query("WITH datetime('2024-03-15T10:00:00+02:00') AS a CALL { WITH a WITH duration('P1D') AS a CREATE (:CyTemporal {id: 'g', d: a}) } RETURN a.hour"),
        )
    }

    @Test
    fun `a temporal value returned by CALL is a temporal value of its kind`() {
        assertEquals(listOf(120L), query("CALL { RETURN duration('PT1H') AS d } RETURN (d * 2).minutes"))
        assertEquals(listOf(4L), query("CALL { RETURN date('2024-03-01') AS d } RETURN (d + duration('P1M')).month"))
        query("CREATE (:CyTemporal {id: 'a', day: date('2024-03-15')})")
        assertEquals(listOf(16L), query("MATCH (n:CyTemporal) CALL { WITH n RETURN n.day AS d } RETURN (d + duration('P1D')).day"))
        assertEquals(
            listOf(17L),
            query("MATCH (n:CyTemporal) CALL { WITH n CALL { WITH n RETURN n.day + duration('P1D') AS e } RETURN e AS d } RETURN (d + duration('P1D')).day"),
        )
        // A value of a UNION is of a kind where each of its queries makes it of that one, else of the kind its text tells.
        query("CREATE (:CyTemporal {id: 'b', day: datetime('2023-12-31T23:30:00-02:00')})")
        assertEquals(
            listOf(2023L, 2024L, 2024L),
            query("CALL { RETURN date('2024-03-01') AS d UNION ALL MATCH (n:CyTemporal) RETURN n.day AS d } RETURN d.year ORDER BY toString(d)"),
        )
    }

    @Test
    fun `a temporal value is added to a list or a text as any value is`() {
        assertEquals(
            mapOf("appended" to 2L, "prepended" to 2L, "duration" to 2L, "durationFirst" to 2L, "text" to "Due 2024-03-15", "durationText" to "Every P1D"),
            one(
                """
                WITH ['x'] AS list, 'Due ' AS due
                RETURN {appended: size(list + date('2024-03-15')), prepended: size(datetime('2024-03-15T10:00:00Z') + list),
                        duration: size(list + duration('P1D')), durationFirst: size(duration('P1D') + list),
                        text: due + date('2024-03-15'), durationText: 'Every ' + duration('P1D')}
                """
            ),
        )
        // What a moment is added to is a list or a text again, of no temporal kind: it compares and is added to as one.
        assertEquals(listOf(true), query("WITH 'Due ' AS due WITH due + date('2024-03-15') AS label RETURN label = 'Due 2024-03-15'"))
        assertEquals(listOf(3L), query("WITH ['x'] AS list RETURN size((list + datetime('2024-03-15T10:00:00Z')) + duration('P1D'))"))
        query("CREATE (:CyTemporal {id: 'a', history: [date('2024-01-01')]})")
        assertEquals(listOf(listOf(1L, 15L)), query("MATCH (n:CyTemporal) SET n.history = n.history + date('2024-03-15') RETURN [d IN n.history | d.day]"))
    }

    @Test
    fun `an entry of a map named as a temporal value's field is the entry`() {
        assertEquals(
            mapOf("day" to 3L, "year" to 2020L, "hours" to 4L, "timezone" to "x"),
            one("UNWIND [{day: 3, year: 2020, hours: 4, timezone: 'x'}] AS r RETURN {day: r.day, year: r.year, hours: r.hours, timezone: r.timezone}"),
        )
    }

    @Test
    fun `a temporal value read from the store truncates, is made a duration, and gives the duration between it and another`() {
        query("CREATE (:CyTemporal {id: 'a', at: datetime('2024-03-15T10:30:00+02:00'), from: date('2024-01-15'), to: date('2024-03-20'), d: duration('P1M2D')})")
        assertEquals(
            mapOf("minute" to 0L, "zone" to "+02:00", "months" to 2L, "days" to 5L, "inDays" to 65L, "durationDays" to 2L),
            one(
                """
                MATCH (n:CyTemporal)
                RETURN {minute: datetime.truncate('hour', n.at).minute, zone: datetime.truncate('day', n.at).timezone,
                        months: duration.between(n.from, n.to).months, days: duration.between(n.from, n.to).days,
                        inDays: duration.inDays(n.from, n.to).days, durationDays: duration(toString(n.d)).days}
                """
            ),
        )
    }

    @Test
    fun `a parameter of a time of day, a local or zoned moment, or a Date is a temporal value of its kind`() {
        val offset = java.time.ZoneOffset.ofHours(2)
        assertEquals(
            mapOf("minute" to 30L, "hour" to 10L, "zoned" to "+02:00", "later" to 16L, "year" to 2024L),
            one(
                "RETURN {minute: \$time.minute, hour: \$ldt.hour, zoned: \$z.timezone, later: (\$ldt + duration('PT36H')).day, year: \$date.year}",
                mapOf(
                    "time" to java.time.LocalTime.of(10, 30),
                    "ldt" to java.time.LocalDateTime.of(2024, 3, 15, 10, 0),
                    "z" to java.time.ZonedDateTime.of(2024, 3, 15, 10, 0, 0, 0, offset), "date" to java.util.Date.from(Instant.parse("2024-06-01T00:00:00Z")),
                ),
            ),
        )
    }

    @Test
    fun `a date takes a duration's time in whole days`() {
        assertEquals(
            mapOf("hourBefore" to 15L, "dayAndHourBefore" to 14L, "dayAndHourAfter" to 16L),
            one("RETURN {hourBefore: (date('2024-03-15') - duration('PT1H')).day, dayAndHourBefore: (date('2024-03-15') - duration('PT25H')).day, dayAndHourAfter: (date('2024-03-15') + duration('PT25H')).day}"),
        )
        // A date read from the store, its kind told by its text.
        query("CREATE (:CyTemporal {id: 'a', day: date('2024-03-15')})")
        assertEquals(listOf(listOf(15L, 14L)), query("MATCH (n:CyTemporal) RETURN [(n.day - duration('PT1H')).day, (n.day - duration('PT25H')).day]"))
    }

    @Test
    fun `a temporal value is its iso text on a sql store`() {
        if (!compiles) return
        assertEquals(
            listOf(listOf("2024-03-15", "2024-03-15T10:30:45.123+02:00", "2024-03-15T10:30:00", "10:30:00", "P1Y2M3DT4H5M6S", "P1DT12H", "PT0S", "10:30:00+02:00")),
            query(
                """
                RETURN [date('2024-03-15'), datetime('2024-03-15T10:30:45.123+02:00'), localdatetime('2024-03-15T10:30'), localtime('10:30'),
                        duration('P1Y2M3DT4H5M6S'), duration({days: 1, hours: 12}), duration('PT0S'), time('10:30+0200')]
                """
            ),
        )
    }
}

class CypherTemporalNeo4jTest : CypherTemporalContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-cypher-temporal", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n:CyTemporal) DETACH DELETE n"))
}

@PostgresTests
class CypherTemporalPostgresTest : CypherTemporalContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = PostgresTestDatabase.clear(manager)
}

@OracleTests
class CypherTemporalOracleTest : CypherTemporalContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = OracleTestDatabase.clear(manager)
}
