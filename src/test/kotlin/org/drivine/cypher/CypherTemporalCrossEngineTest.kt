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
        // In a WITH, skipped and limited, and the rows read in that order after it.
        assertEquals(
            listOf(listOf(4L, 6L), listOf(5L, 6L), listOf(1L, 3L)),
            query("$walk WITH a, b ORDER BY $minute DESC, a.id, b.id SKIP 1 LIMIT 3 RETURN a.id AS a, b.id AS bb"),
        )
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
