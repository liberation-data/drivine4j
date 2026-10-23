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
            mapOf("firstOfMonth" to 1L, "onTheHour" to 0L, "midnight" to 0L, "one" to true),
            one(
                """
                RETURN {firstOfMonth: date.truncate('month', date('2024-03-15')).day,
                        onTheHour: datetime.truncate('hour', datetime('2024-03-15T10:30:45Z')).minute,
                        midnight: datetime.truncate('day', datetime('2024-03-15T10:30:45Z')).hour,
                        one: date.statement() = date()}
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
    fun `a temporal value is its iso text on a sql store`() {
        if (!compiles) return
        assertEquals(
            listOf(listOf("2024-03-15", "2024-03-15T10:30:45.123+02:00", "2024-03-15T10:30:00", "10:30:00", "P1Y2M3DT4H5M6S", "P1DT12H", "PT0S")),
            query(
                """
                RETURN [date('2024-03-15'), datetime('2024-03-15T10:30:45.123+02:00'), localdatetime('2024-03-15T10:30'), localtime('10:30'),
                        duration('P1Y2M3DT4H5M6S'), duration({days: 1, hours: 12}), duration('PT0S')]
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
