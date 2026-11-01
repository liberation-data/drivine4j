package org.drivine.cypher

import org.drivine.connection.DatabaseType
import org.drivine.cypher.spec.CypherSpec
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
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.testcontainers.containers.Neo4jContainer
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * openCypher's temporal values give the same fields, arithmetic and comparisons on a SQL store, compiled, as on
 * Neo4j: the statements of the spec's `cypher/temporal.yaml` are run on Neo4j, Postgres, Oracle and SQLite. Here are only
 * the parameters of java.time's types, which the spec cannot write. A graph engine's driver gives a temporal
 * value as one of its own, and a SQL store as its ISO text, so the tests read values' fields, and the text
 * where only a SQL store gives it.
 */
abstract class CypherTemporalContract {

    abstract val pm: PersistenceManager

    abstract fun clear()

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    private fun one(cypher: String, parameters: Map<String, Any?> = emptyMap()): Map<*, *> = query(cypher, parameters).single() as Map<*, *>

    @BeforeEach
    fun graph() = clear()

    @TestFactory
    fun spec() = CypherSpec.load("temporal.yaml").tests(CypherSpec.storeOf(pm.type), before = ::clear) { step -> query(step.cypher, step.parameters) }

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
    fun `a year before 1 is numbered as Neo4j numbers it, made, worked out, written and read`() {
        // #469: Postgres and Oracle number a year BC from -1, with no year 0, and wrote 1 BC back as 0001; none read a year before 0.
        assertEquals(
            listOf(listOf("0000-12-31", "-0044-03-15", "-0001-01-01", "0000-01-01", "-1000-01-01", "0000-01-01T00:00:00Z", -44L, 0L, "P1Y", true)),
            query(
                """
                RETURN [toString(date('0001-01-01') - duration('P1D')), toString(date({year: -44, month: 3, day: 15})), toString(date({year: -1})),
                        toString(date.truncate('millennium', date('0999-12-31'))), toString(date.truncate('millennium', date('-1234-06-01'))),
                        toString(datetime.truncate('century', datetime('0050-06-01T10:00:00Z'))), date('-0044-03-15').year, date('0000-06-01').year,
                        toString(duration.between(date('0000-01-01'), date('0001-01-01'))), date('-0044-03-15') < date('0001-01-01')]
                """,
            ),
        )
        // The ISO week's year, a truncation's own year under fields given, and a truncation to the millisecond, which Oracle writes and reads with its sign.
        assertEquals(
            listOf(listOf(-44L, "-0044-01-02", "-0044-03-15T12:00:00.123Z", "-0040-02-01T00:00:00")),
            query(
                """
                RETURN [date('-0044-03-15').weekYear, toString(date.truncate('year', date('-0044-03-15'), {day: 2})),
                        toString(datetime.truncate('millisecond', datetime('-0044-03-15T12:00:00.1234Z'))),
                        toString(localdatetime.truncate('decade', localdatetime('-0044-03-15T12:00:00'), {month: 2}))]
                """,
            ),
        )
        // A parameter of java.time's, written and read back, its kind told by its text.
        query("CREATE (:CyTemporal {id: 'a', day: \$day, at: datetime('-0044-03-15T12:00:00+02:00')})", mapOf("day" to LocalDate.of(-44, 3, 15)))
        assertEquals(
            listOf(listOf("-0044-03-16", -44L, "-0043-03-15T12:00:00+02:00", true)),
            query("MATCH (n:CyTemporal) RETURN [toString(n.day + duration('P1D')), n.day.year, toString(n.at + duration('P1Y')), n.day < date('0001-01-01')]"),
        )
    }

    @Test
    fun `a stored value of any year compares and gives its year as Neo4j does, beside a value written or a parameter`() {
        // #487: Postgres and Oracle read a text whose year is 1000 or later as it is, with no test of its era, where a test of
        // its kind has told them so; and read a year off the text. A year after 0 but before 1000, and one BC, take the other way.
        val years = listOf(9999, 2024, 1000, 999, 1, 0, -1, -44, -4000)
        query(
            """
            UNWIND range(0, size(${'$'}years) - 1) AS i WITH i, ${'$'}years[i] AS y
            CREATE (:CyTemporal {id: i, day: date({year: y, month: 3, day: 15}), ldt: localdatetime({year: y, month: 3, day: 15, hour: 12}),
                                 at: datetime({year: y, month: 3, day: 15, hour: 12, timezone: '+02:00'})})
            """,
            mapOf("years" to years),
        )
        assertEquals(
            listOf(years.map { listOf(it.toLong(), it.toLong(), it.toLong()) }),
            query("MATCH (n:CyTemporal) WITH n ORDER BY n.id RETURN collect([n.day.year, n.ldt.year, n.at.year])"),
        )
        assertEquals(
            listOf(years.map { y -> listOf(y <= 0, y >= 0, y == -44, y <= 0, y <= 0, y >= 1000) }),
            query(
                """
                MATCH (n:CyTemporal) WITH n ORDER BY n.id
                RETURN collect([n.day < date('0001-01-01'), date('0000-03-15') <= n.day, n.day = date('-0044-03-15'), n.day < ${'$'}p,
                                n.at < datetime('0000-06-01T00:00:00Z'), n.ldt >= localdatetime('1000-01-01T00:00')])
                """,
                mapOf("p" to LocalDate.of(0, 6, 1)),
            ),
        )
        // Two years BC are not compared here: two stored values, and two on SQLite, compare by their text, which orders them backwards (#374).
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
    fun `a zoned parameter keeps its kind in reduce`() {
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
    }

    @Test
    fun `aggregates of temporal arithmetic give what they give of the arithmetic worked out first, grouped, distinct and over nulls`() {
        // #333: an aggregate's argument is worked out first, for each row, where its values are bound; it gives what it did.
        query(
            """
            UNWIND [
              {id: 1, g: 'a', at: '2024-03-15T10:00:00+02:00', m: 1}, {id: 2, g: 'a', at: '2024-03-15T10:00:00+02:00', m: 1},
              {id: 3, g: 'a', at: '2024-03-15T10:00:00+02:00', m: 4}, {id: 4, g: 'b', at: '2024-03-15T11:00:00Z', m: 5},
              {id: 5, g: 'b', at: null, m: 3}, {id: 6, g: 'b', at: '2024-03-15T12:30:00+02:00', m: 0}
            ] AS r
            CREATE (:CyAgg {id: r.id, g: r.g, at: r.at, m: r.m})
            """,
        )
        val t = "datetime(n.at) + duration({minutes: n.m})"
        // b's 12:30+02:00 is 10:30 UTC, before its 11:05Z: min() and max() compare moments, not their texts. Its null is passed over.
        assertEquals(
            listOf(
                mapOf("g" to "a", "count" to 3L, "distinct" to 2L, "lo" to listOf(10L, 1L), "hi" to listOf(10L, 4L), "sum" to 6L, "avg" to 2.0, "times" to listOf(listOf(10L, 1L), listOf(10L, 1L), listOf(10L, 4L))),
                mapOf("g" to "b", "count" to 2L, "distinct" to 2L, "lo" to listOf(12L, 30L), "hi" to listOf(11L, 5L), "sum" to 35L, "avg" to 17.5, "times" to listOf(listOf(11L, 5L), listOf(12L, 30L))),
            ),
            query(
                """
                MATCH (n:CyAgg) WITH n ORDER BY n.id
                WITH n.g AS g, count($t) AS c, count(DISTINCT $t) AS d, min($t) AS lo, max($t) AS hi, sum(($t).minute) AS s, avg(($t).minute) AS a, collect($t) AS l
                RETURN {g: g, count: c, distinct: d, lo: [lo.hour, lo.minute], hi: [hi.hour, hi.minute], sum: s, avg: a, times: [x IN l | [x.hour, x.minute]]}
                ORDER BY g
                """,
            ),
        )
        // Kept distinct by collect(DISTINCT …), and read in an item beside the aggregate.
        assertEquals(
            listOf(mapOf("g" to "a", "minutes" to listOf(1L, 4L), "n" to 2L), mapOf("g" to "b", "minutes" to listOf(5L, 30L), "n" to 2L)),
            query("MATCH (n:CyAgg) WITH n ORDER BY n.id WITH n.g AS g, collect(DISTINCT $t) AS l RETURN {g: g, minutes: [x IN l | x.minute], n: size(l)} ORDER BY g"),
        )
        // Sorted by an aggregate of it, which is none of the items.
        assertEquals(listOf(listOf("b", 3L), listOf("a", 3L)), query("MATCH (n:CyAgg) RETURN n.g AS g, count(*) AS c ORDER BY max(($t).minute) DESC"))
        // The size of a list of temporal values that a comprehension keeps, summed: 2 for each of b's moments after 09:30 UTC.
        assertEquals(
            listOf(4L),
            query("MATCH (n:CyAgg) RETURN sum(size([x IN [datetime(n.at), datetime(n.at) + duration('PT1H')] WHERE x > datetime('2024-03-15T09:30:00Z')]))"),
        )
        // Over no grouping key, the issue's statements.
        val z = "datetime('2024-03-15T10:00:00+02:00') + duration({minutes: i})"
        assertEquals(listOf(listOf(3L, 1L, 3L, 6L)), query("UNWIND range(1, 3) AS i RETURN [count($z), min($z).minute, max($z).minute, sum(($z).minute)]"))
        assertEquals(listOf(1L, 0L), query("UNWIND range(1, 4) AS i WITH i % 2 AS k, i WITH k, collect($z) AS l RETURN k ORDER BY l"))
        // Inside a CASE and arithmetic, in a WITH a WHERE filters, and in a CALL { … }: Oracle reads no imported variable in an aggregate there, worked out first or not, so this one reads none.
        assertEquals(listOf(listOf("many", 4L)), query("UNWIND range(1, 3) AS i RETURN [CASE WHEN count($z) > 1 THEN 'many' END, count($z) + 1]"))
        assertEquals(listOf(1L), query("UNWIND range(1, 4) AS i WITH i % 2 AS k, max(($z).minute) AS m WHERE m < 4 RETURN k"))
        assertEquals(listOf(8L), query("UNWIND [2] AS o CALL { WITH o UNWIND range(1, 3) AS i RETURN sum(($z).minute) AS s } RETURN s + o"))
        // A reduce() in the argument, which Oracle declares a function for.
        assertEquals(listOf(6L), query("UNWIND range(1, 3) AS i RETURN sum(reduce(a = 0, x IN [i] | a + ($z).minute - i + x))"))
        // A list of temporal values, kept distinct, read back by its items' fields: Oracle reads no temporal list by JSON_TABLE (#331).
        assertEquals(
            listOf(listOf(listOf(2L, 3L), listOf(3L, 4L))),
            query("WITH [datetime('2024-01-01T00:00Z'), datetime('2024-01-02T00:00Z')] AS l UNWIND [1, 2, 1] AS i WITH collect(DISTINCT [x IN l | x + duration({days: i})]) AS c RETURN [y IN c | [x IN y | x.day]]"),
        )
        // Beside a variable-length walk in the same MATCH.
        query("MATCH (a:CyAgg {id: 1}), (b:CyAgg {id: 2}), (c:CyAgg {id: 3}) CREATE (a)-[:NEXT]->(b)-[:NEXT]->(c)")
        assertEquals(
            listOf(listOf(1L, 2L, 5L), listOf(2L, 1L, 4L)),
            query("MATCH (a:CyAgg)-[:NEXT*1..2]->(b:CyAgg) RETURN a.id AS a, count(datetime(b.at) + duration({minutes: b.m})) AS c, sum((datetime(b.at) + duration({minutes: b.m})).minute) AS s ORDER BY a"),
        )
        // Sorted there by an aggregate of it that is none of the items, which binds no value beside the walk: see `project`.
        assertEquals(
            listOf(listOf(2L, 1L), listOf(1L, 2L)),
            query("MATCH (a:CyAgg)-[:NEXT*1..2]->(b:CyAgg) RETURN a.id AS a, count(*) AS c ORDER BY sum((datetime(b.at) + duration({minutes: b.m})).minute)"),
        )
    }

    @Test
    fun `a truncation's map of fields is laid over the value cut short, as Neo4j lays it`() {
        // #422: the third argument was refused on the SQL stores. The spec holds the rest; this adds a field that is null as read.
        // A field that is null is given as its least, written or read: no hour, the first month, Monday, no fraction.
        assertEquals(
            mapOf("hour" to 0L, "month" to 1L, "monday" to 8L, "fraction" to 0L, "read" to listOf(0L, 7L)),
            one(
                """
                WITH {} AS m
                WITH datetime.truncate('minute', datetime('2017-10-11T12:31:14+01:00'), {hour: m.h, second: coalesce(m.s, 7)}) AS read
                RETURN {hour: datetime.truncate('second', datetime('2017-10-11T12:31:14+01:00'), {hour: null}).hour,
                        month: date.truncate('day', date('1984-10-11'), {month: null}).month,
                        monday: date.truncate('day', date('1984-10-11'), {dayOfWeek: null}).day,
                        fraction: datetime.truncate('millisecond', datetime('2017-10-11T12:31:14.645876+01:00'), {millisecond: null, microsecond: 8}).microsecond,
                        read: [read.hour, read.second]}
                """,
            ),
        )
    }

    @Test
    fun `a value read from the store truncates with a map of fields, whatever its kind`() {
        // On a SQL store a stored value is of the kind its text tells, so each kind is cut and given its fields as such.
        // Neo4j fails a truncation of null, where the SQL stores give null, so no node here is without one.
        query("CREATE (:CyTrunc {id: 1, at: datetime('2017-10-11T12:31:14.645876+01:00')}), (:CyTrunc {id: 2, at: date('2017-10-11')})")
        assertEquals(
            listOf(listOf(1L, 2010L, 2L, 3L, "+01:00"), listOf(2L, 2010L, 2L, 3L, "Z")),
            query(
                "MATCH (n:CyTrunc) WITH n, datetime.truncate('decade', n.at, {day: 2, hour: 3}) AS d " +
                    "RETURN n.id AS id, d.year AS year, d.day AS day, d.hour AS hour, d.timezone AS zone ORDER BY id",
            ),
        )
    }

    @Test
    fun `a date or a moment is made from a week, an ordinal day or a quarter, as on Neo4j`() {
        // #495: refused on the SQL stores before. The year is the week-based year; week 53 of a year of 52 weeks and a day
        // of the quarter past its last run on into the next, and a field that is null is its least. The spec holds these
        // in statements of its own, and the moments.
        assertEquals(
            mapOf(
                "week" to listOf(1984L, 3L, 5L), "weekDay" to listOf(1984L, 3L, 7L), "ordinal" to listOf(1984L, 7L, 20L),
                "quarter" to listOf(1984L, 7L, 1L), "quarterDay" to listOf(1984L, 8L, 14L),
                "long" to listOf(2020L, 12L, 28L), "sunday" to listOf(2021L, 1L, 3L), "before" to listOf(2018L, 12L, 31L), "friday" to listOf(2016L, 1L, 1L),
                "rolled" to listOf(2019L, 12L, 30L), "q1" to listOf(2023L, 4L, 1L), "leap" to listOf(2024L, 12L, 31L), "q4" to listOf(2024L, 12L, 31L),
                "nulls" to listOf(listOf(1L, 2L), listOf(1L, 9L), listOf(1L, 1L), listOf(4L, 1L)),
            ),
            one(
                """
                WITH date({year: 1984, week: 10}) AS w, date({year: 1984, week: 10, dayOfWeek: 3}) AS wd, date({year: 1984, ordinalDay: 202}) AS o,
                     date({year: 1984, quarter: 3}) AS q, date({year: 1984, quarter: 3, dayOfQuarter: 45}) AS qd,
                     date({year: 2020, week: 53}) AS long, date({year: 2020, week: 53, dayOfWeek: 7}) AS sunday, date({year: 2019, week: 1}) AS before,
                     date({year: 2015, week: 53, dayOfWeek: 5}) AS friday, date({year: 2019, week: 53}) AS rolled, date({year: 2023, quarter: 1, dayOfQuarter: 91}) AS q1,
                     date({year: 2024, ordinalDay: 366}) AS leap, date({year: 2024, quarter: 4, dayOfQuarter: 92}) AS q4,
                     [date({year: 2023, week: null}), date({year: 2023, week: 2, dayOfWeek: null}), date({year: 2023, ordinalDay: null}),
                      date({year: 2023, quarter: 2, dayOfQuarter: null})] AS nulls
                RETURN {week: [w.year, w.month, w.day], weekDay: [wd.year, wd.month, wd.day], ordinal: [o.year, o.month, o.day], quarter: [q.year, q.month, q.day],
                        quarterDay: [qd.year, qd.month, qd.day], long: [long.year, long.month, long.day], sunday: [sunday.year, sunday.month, sunday.day],
                        before: [before.year, before.month, before.day], friday: [friday.year, friday.month, friday.day], rolled: [rolled.year, rolled.month, rolled.day],
                        q1: [q1.year, q1.month, q1.day], leap: [leap.year, leap.month, leap.day], q4: [q4.year, q4.month, q4.day],
                        nulls: [d IN nulls | [d.month, d.day]]}
                """,
            ),
        )
    }

    @Test
    fun `a date is made from a week, an ordinal day or a quarter an expression gives, and fails past their range as the statement runs`() {
        // #495: worked out by the store, which fails on a field past its range as Neo4j does. The spec holds the rows, and these
        // fields past their range but for a week's day, a quarter's day and a moment's.
        listOf(
            "UNWIND [[2023, 2, 0]] AS p RETURN date({year: p[0], week: p[1], dayOfWeek: p[2]})",
            "UNWIND [[2024, 4, 93]] AS p RETURN date({year: p[0], quarter: p[1], dayOfQuarter: p[2]})",
            // A moment's too, which Oracle reads as a JSON value's, null where it is no moment.
            "UNWIND [[2023, 366]] AS p RETURN localdatetime({year: p[0], ordinalDay: p[1], hour: 3})",
            "UNWIND [[2020, 54]] AS p RETURN datetime({year: p[0], week: p[1], timezone: '+01:00'})",
            "UNWIND [[2024, 4, 93]] AS p RETURN localdatetime({year: p[0], quarter: p[1], dayOfQuarter: p[2]})",
        ).forEach { cypher -> assertFails(cypher) { query(cypher) } }
    }

    @Test
    fun `a date or a moment gives the day of its quarter, and the fields of its week at the year's turn, as on Neo4j`() {
        // #427 item 4: dayOfQuarter was null on the SQL stores. The spec holds these in two statements, and a value read from the store.
        assertEquals(
            listOf(11L, 92L, 90L, 91L, 1L, 50L, 32L, 53L, 2020L, 7L, 1L, 2019L, 53L, 366L, 1L),
            query(
                """
                RETURN [date('1984-10-11').dayOfQuarter, date('2024-12-31').dayOfQuarter, date('2023-03-31').dayOfQuarter, date('2024-03-31').dayOfQuarter,
                        date('2024-01-01').dayOfQuarter, datetime('2024-05-20T10:00+02:00').dayOfQuarter, localdatetime('2024-08-01T23:00').dayOfQuarter,
                        date('2021-01-03').week, date('2021-01-03').weekYear, date('2021-01-03').dayOfWeek, date('2018-12-31').week, date('2018-12-31').weekYear,
                        date('2020-12-31').week, date('2024-12-31').ordinalDay, date('2021-01-03').quarter]
                """,
            ).single(),
        )
    }

    @Test
    fun `a date, a time or a moment is made from another temporal value, with the map's fields laid over its own, as on Neo4j`() {
        // #508: refused on the SQL stores before. The spec's temporal steps hold the rest; it hasn't this one.
        listOf(
            // Beside a day of the week a year moves the date so many years, to February 28th from the 29th, then to the day of its week.
            "WITH date({date: date('2024-02-29'), year: 2023, dayOfWeek: 3}) AS a, date({date: date('2021-01-02'), year: 2021, dayOfWeek: 1}) AS b, date({date: date('2020-12-31'), year: 2021, dayOfWeek: 1}) AS c, localdatetime({date: date('2024-02-29'), year: 2023, dayOfWeek: 2, hour: 1}) AS e RETURN [[a.year, a.month, a.day], [b.year, b.month, b.day], [c.year, c.month, c.day], [e.year, e.month, e.day, e.hour]]" to listOf(listOf(listOf(2023L, 3L, 1L), listOf(2020L, 12L, 28L), listOf(2021L, 12L, 27L), listOf(2023L, 2L, 28L, 1L))),
        ).forEach { (cypher, rows) -> assertEquals(rows, query(cypher), cypher) }
    }

    @Test
    fun `another temporal value an expression gives that is null or of the wrong kind fails as the statement runs`() {
        // #508: as Neo4j fails (22007, 22G05), not given as null. The spec's temporal steps hold the rest; it hasn't these.
        listOf(
            "UNWIND [date('2024-03-15'), null] AS x RETURN date({date: x, day: 1})",
            "UNWIND ['hello'] AS x RETURN localtime({time: x})",
            "UNWIND [localtime('10:00')] AS x RETURN date({date: x})",
            "UNWIND [date('2024-03-15')] AS x RETURN time({time: x, timezone: '+01:00'})",
        ).forEach { cypher -> assertFails(cypher) { query(cypher) } }
    }

    @Test
    fun `rows beside a variable-length walk sort by temporal arithmetic that is none of the items, as they do on Neo4j`() {
        // #378: beside a walk, such a term bound nothing, and wrote the arithmetic's operands out again at each level: too
        // long for SQLite. It is worked out first, for each row, and sorts as it did.
        query(
            """
            UNWIND [
              {id: 1, at: '2024-03-15T10:00:00+02:00', m: 1}, {id: 2, at: '2024-03-15T10:00:00+02:00', m: 1},
              {id: 3, at: '2024-03-15T10:00:00+02:00', m: 4}, {id: 4, at: '2024-03-15T11:00:00Z', m: 5},
              {id: 5, at: null, m: 3}, {id: 6, at: '2024-03-15T12:30:00+02:00', m: 0}
            ] AS r
            CREATE (:CyAgg {id: r.id, at: r.at, m: r.m})
            """,
        )
        query("MATCH (a:CyAgg), (b:CyAgg) WHERE b.id = a.id + 1 AND NOT a.id IN [3] CREATE (a)-[:NEXT]->(b)")
        val walk = "MATCH (a:CyAgg)-[:NEXT*1..2]->(b:CyAgg)"
        val minute = "(datetime(b.at) + duration({minutes: b.m})).minute"
        // The issue's statement: 5's null minute sorts last.
        assertEquals(
            listOf(listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L), listOf(4L, 6L), listOf(5L, 6L), listOf(4L, 5L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY $minute, a, bb"),
        )
        // Descending, and reading an item by its name: a is a.id here, not the node; the null sorts first.
        assertEquals(
            listOf(listOf(4L, 5L), listOf(5L, 6L), listOf(4L, 6L), listOf(2L, 3L), listOf(1L, 3L), listOf(1L, 2L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY (datetime(b.at) + duration({minutes: b.m + a})).minute DESC, a"),
        )
        // Reading it in a comprehension's projection.
        assertEquals(
            listOf(listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L), listOf(4L, 6L), listOf(5L, 6L), listOf(4L, 5L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY [x IN [b.m] | (datetime(b.at) + duration({minutes: x + a})).minute][0], a, bb"),
        )
        // An item named as a variable is, which the term reads: b is b.m.
        assertEquals(
            listOf(listOf(4L, 0L), listOf(5L, 0L), listOf(1L, 1L), listOf(4L, 3L), listOf(1L, 4L), listOf(2L, 4L)),
            query("$walk RETURN a.id AS a, b.m AS b ORDER BY (datetime('2024-03-15T10:00Z') + duration({minutes: b * 7})).minute, a"),
        )
        // Items named as each other's variables: a is b.id and b is a.id, each read as its own item.
        assertEquals(
            listOf(listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L), listOf(4L, 5L), listOf(4L, 6L), listOf(5L, 6L)),
            query("$walk RETURN a.id AS b, b.id AS a ORDER BY (datetime('2024-03-15T10:00Z') + duration({minutes: a * 7 + b})).minute, a, b"),
        )
        // An item's name read in a subquery: a is a.id there too, not the node.
        assertEquals(
            listOf(listOf(4L, 5L), listOf(5L, 6L), listOf(4L, 6L), listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY COUNT { MATCH (x:CyAgg) WHERE x.id <= a } * datetime(b.at).minute DESC, a, bb"),
        )
        // An item's name read in a pattern, or as a map projection's variable, where it is not replaced: a is a.id there too.
        assertEquals(
            listOf(listOf(4L, 5L), listOf(5L, 6L), listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L), listOf(4L, 6L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY size([(b)<-[:NEXT]-(x:CyAgg {id: a}) | x.id]) * datetime(b.at).minute DESC, a, bb"),
        )
        assertEquals(
            listOf(listOf(4L, 5L), listOf(5L, 6L), listOf(4L, 6L), listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L)),
            query("$walk RETURN a.id AS a, b.id AS bb ORDER BY b{.m, a}.a * datetime(b.at).minute DESC, a, bb"),
        )
        // In a WITH, skipped and limited, and the rows read in that order after it.
        assertEquals(
            listOf(listOf(4L, 6L), listOf(5L, 6L), listOf(1L, 3L)),
            query("$walk WITH a, b ORDER BY $minute DESC, a.id, b.id SKIP 1 LIMIT 3 RETURN a.id AS a, b.id AS bb"),
        )
        // Limited, then filtered by the WITH's WHERE.
        assertEquals(
            listOf(listOf(4L, 5L), listOf(4L, 6L), listOf(5L, 6L)),
            query("$walk WITH a, b ORDER BY $minute DESC, a.id, b.id LIMIT 4 WHERE a.id > 1 RETURN a.id AS a, b.id AS bb"),
        )
        // An item made anew for each row, randomUUID(), read by its name: sorted by as it is given. Made again for the term,
        // the rows came out of order; left to `project`, the statement was too long for SQLite.
        val made = query("$walk RETURN randomUUID() AS u ORDER BY u + coalesce(toString($minute), '')")
        assertEquals(made.map { it as String }.sorted(), made)
        // Beside it, an item made anew that no term reads, and one not made anew, are given as they were.
        val beside = query("$walk RETURN randomUUID() AS u, randomUUID() AS v, b.id AS bb ORDER BY v + coalesce(toString($minute), '')").map { it as List<*> }
        assertEquals(beside.map { it[1] as String }.sorted(), beside.map { it[1] })
        assertEquals(listOf(2L, 3L, 3L, 5L, 6L, 6L), beside.map { it[2] as Long }.sorted())
        assertEquals(12, beside.flatMap { it.take(2) }.toSet().size)
        // After DISTINCT, over the items, as Neo4j sorts there.
        assertEquals(
            listOf(1L, 4L, 0L, 3L),
            query("$walk RETURN DISTINCT b.m AS m, b.at AS at ORDER BY (datetime(at) + duration({minutes: m})).minute").map { (it as List<*>).first() },
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

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

@PostgresTests
class CypherTemporalPostgresTest : CypherTemporalContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override fun clear() = PostgresTestDatabase.clear(manager)
}

@OracleTests
class CypherTemporalOracleTest : CypherTemporalContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override fun clear() = OracleTestDatabase.clear(manager)
}

class CypherTemporalSqliteTest : CypherTemporalContract() {
    private val manager = SqliteTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override fun clear() = SqliteTestDatabase.clear(manager)
}
