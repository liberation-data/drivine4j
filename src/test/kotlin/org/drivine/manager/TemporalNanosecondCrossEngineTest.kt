package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NANOS = 645876123

private val VALUES: List<Pair<Temporal, String>> = listOf(
    LocalTime.of(12, 0, 0, NANOS) to "12:00:00.645876123",
    LocalDateTime.of(2024, 1, 1, 0, 0, 0, NANOS) to "2024-01-01T00:00:00.645876123",
    OffsetTime.of(12, 0, 0, NANOS, ZoneOffset.ofHours(1)) to "12:00:00.645876123+01:00",
    OffsetDateTime.of(2024, 1, 1, 0, 0, 0, NANOS, ZoneOffset.ofHours(1)) to "2024-01-01T00:00:00.645876123+01:00",
    ZonedDateTime.of(2024, 1, 1, 0, 0, 0, NANOS, ZoneOffset.ofHours(1)) to "2024-01-01T00:00:00.645876123+01:00",
    Instant.parse("2024-01-01T00:00:00.645876123Z") to "2024-01-01T00:00:00.645876123Z",
)

/** [value] a nanosecond later. */
private fun later(value: Temporal): Temporal = value.plus(1, java.time.temporal.ChronoUnit.NANOS)

/** Whether [read], read back, is the same value as [a]: a moment with an offset, the same instant at UTC (see [verifyNanoseconds]). */
private fun same(a: Any?, read: Any?): Boolean = when {
    a is OffsetDateTime && read is OffsetDateTime -> a.isEqual(read) && read.offset == ZoneOffset.UTC
    a is ZonedDateTime && read is ZonedDateTime -> a.isEqual(read) && read.offset == ZoneOffset.UTC
    else -> a == read
}

/**
 * A java.time value with all nine digits of its second, as a parameter, keeps them on every store: in its text, its
 * nanosecond field, a comparison a nanosecond apart, a property it is stored in and read back, and read back as its
 * own type (#421). The Cypher spec's parameters are JSON, so they can't give a typed java.time value, and the round
 * trip an application makes with one is pinned here instead: drivine4j hands the value to the compiler's module, which
 * writes its text on a SQL store (`StoreValues`), and drivine4j reads the store's tag back as the value (`HeldTemporals`,
 * the result mapper and Jackson). An Instant, a ZonedDateTime and a LocalDateTime are also saved and loaded by the
 * object manager.
 *
 * A moment with an offset read back as its own type is the same instant at UTC on every store, Neo4j's too: that is
 * drivine4j's mapping, not the store's, so it's compared as that instant at UTC.
 */
private fun verifyNanoseconds(cypher: PersistenceManager, gom: StatelessGraphObjectManager) {
    fun <T : Any> one(statement: String, parameters: Map<String, Any?>, type: Class<T>): T =
        cypher.query(QuerySpecification.withStatement(statement).bind(parameters).transform(type)).single()

    for ((value, text) in VALUES) {
        val kind = value.javaClass.simpleName
        val t = mapOf("t" to value)
        assertEquals(text, one("RETURN toString(\$t) AS s", t, String::class.java), "$kind: its text")
        assertEquals(NANOS.toLong(), one("RETURN \$t.nanosecond AS n", t, Long::class.java), "$kind: its nanosecond")
        assertTrue(one("RETURN \$later > \$t AS gt", t + ("later" to later(value)), Boolean::class.java), "$kind: a nanosecond later is later")
        assertTrue(same(value, one("RETURN \$t AS t", t, value.javaClass)), "$kind: read back as its type")
        assertEquals(text, one("RETURN toString([\$t][0]) AS s", t, String::class.java), "$kind: in a list")

        cypher.execute(QuerySpecification.withStatement("CREATE (:NanoParameter {kind: \$kind, t: \$t})").bind(t + ("kind" to kind)))
        val stored = mapOf("kind" to kind)
        assertEquals(text, one("MATCH (n:NanoParameter {kind: \$kind}) RETURN toString(n.t) AS s", stored, String::class.java), "$kind: stored, its text")
        assertTrue(same(value, one("MATCH (n:NanoParameter {kind: \$kind}) RETURN n.t AS t", stored, value.javaClass)), "$kind: stored, read back as its type")
        assertTrue(one("MATCH (n:NanoParameter {kind: \$kind}) RETURN n.t = \$t AS eq", stored + t, Boolean::class.java), "$kind: stored, equal to the parameter")
    }

    val instant = Instant.parse("2026-06-03T02:25:23.645876123Z")
    val node = TemporalsNode("nanos", instant, instant.atZone(ZoneOffset.UTC), LocalDate.of(2026, 6, 3), LocalDateTime.of(2026, 6, 3, 2, 25, 23, NANOS))
    gom.save(node)
    val loaded = gom.load("nanos", TemporalsNode::class.java)!!
    assertEquals(node.instant, loaded.instant, "saved and loaded: an Instant")
    assertTrue(node.zoned.isEqual(loaded.zoned), "saved and loaded: a ZonedDateTime, got ${loaded.zoned}")
    assertEquals(node.dateTime, loaded.dateTime, "saved and loaded: a LocalDateTime")
}

private fun gom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class TemporalNanosecondNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()
        private val registry = SubtypeRegistry()
        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo-nanos", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `a parameter keeps its nanoseconds on Neo4j`() = verifyNanoseconds(pm, gom(pm, registry))
}

@PostgresTests
class TemporalNanosecondPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = PostgresTestDatabase.clear(pm)

    @Test fun `a parameter keeps its nanoseconds on Postgres`() = verifyNanoseconds(PostgresTestDatabase.fixtures(pm), gom(pm, registry))
}

@OracleTests
class TemporalNanosecondOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = OracleTestDatabase.clear(pm)

    @Test fun `a parameter keeps its nanoseconds on Oracle`() = verifyNanoseconds(OracleTestDatabase.fixtures(pm), gom(pm, registry))
}

class TemporalNanosecondSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = SqliteTestDatabase.clear(pm)

    @Test fun `a parameter keeps its nanoseconds on SQLite`() = verifyNanoseconds(SqliteTestDatabase.fixtures(pm), gom(pm, registry))
}
