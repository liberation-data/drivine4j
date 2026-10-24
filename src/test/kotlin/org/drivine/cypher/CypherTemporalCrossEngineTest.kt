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

    @Test
    fun `a fraction of a second gives its milliseconds and microseconds whole, though a real number misses it`() {
        // 33.001 in binary times a million is 33000999.999999996.
        val fields = pm.query(
            QuerySpecification.withStatement(
                CypherStatement(
                    "WITH localdatetime('2024-03-15T10:30:33.001') AS t, duration('PT33.007S') AS d " +
                        "RETURN [t.millisecond, t.microsecond, d.microseconds, d.microsecondsOfSecond, localtime('10:59:59.999999').hour, " +
                        "datetime('2024-03-15T10:30:33.123999Z').epochMillis]"
                )
            )
        )
        assertEquals(listOf(listOf(1L, 1000L, 33007000L, 7000L, 10L, 1710498633123L)), fields)
    }

    @Test
    fun `a duration of a map keeps the fraction of a second its smaller units make`() {
        // SQLite divides one whole number by another to a whole number.
        val durations = pm.query(
            QuerySpecification.withStatement(
                CypherStatement("RETURN [toString(duration({milliseconds: 1500})), toString(duration({seconds: 1, microseconds: 7})), toString(duration({nanoseconds: 1000}))]")
            )
        )
        assertEquals(listOf(listOf("PT1.5S", "PT1.000007S", "PT0.000001S")), durations)
    }
}
