package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.transform
import org.drivine.session.SessionManager
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.sqlite.SqliteTestDatabase

/**
 * Cross-engine temporal consistency: every temporal type saved via `save()` must round-trip, be
 * range-queryable, and order chronologically on each backend — natively on Memgraph, as ISO strings
 * (via [org.drivine.query.TemporalCoercer]) on FalkorDB. The write path and the bound-param path
 * must agree per backend. Uses the shared [TemporalsNode] fixture (all java.time types).
 */
private fun verifyTemporal(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    val base = TemporalsNode.at("ce")
    gom.save(base)
    gom.save(TemporalsNode.at("later", 10))

    // round-trips, all native temporal types (FalkorDB stores them as ISO strings, still parseable)
    val loaded = gom.load("ce", TemporalsNode::class.java)!!
    assertEquals(base.instant, loaded.instant)
    assertTrue(base.zoned.isEqual(loaded.zoned), "expected same instant, got ${loaded.zoned}")
    assertEquals(base.date, loaded.date)
    assertEquals(base.dateTime, loaded.dateTime)

    // an instant exactly on a minute round-trips too: ISO text may drop zero seconds, and an Instant is not read without them
    val onTheMinute = java.time.Instant.parse("2040-01-01T00:00:00Z")
    gom.save(base.copy(id = "minute", instant = onTheMinute, zoned = onTheMinute.atZone(java.time.ZoneId.of("UTC")), optional = onTheMinute))
    val minute = gom.load("minute", TemporalsNode::class.java)!!
    assertEquals(onTheMinute, minute.instant)
    assertEquals(onTheMinute, minute.optional)
    assertTrue(minute.zoned.toInstant() == onTheMinute, "expected same instant, got ${minute.zoned}")
    gom.delete("minute", TemporalsNode::class.java)

    // range query with a bound Instant matches (and a future bound excludes)
    fun range(since: java.time.Instant) = pm.query(
        QuerySpecification.withStatement("MATCH (n:TemporalsNode) WHERE n.instant >= \$s RETURN n.id")
            .bind(mapOf("s" to since)).transform(String::class.java)
    )
    assertEquals(setOf("ce", "later"), range(base.instant.minusSeconds(60)).toSet())
    assertEquals(emptyList(), range(base.instant.plusSeconds(60)))

    // order by a temporal is chronological (native on Memgraph; lexicographic ISO on FalkorDB)
    val ordered = pm.query(
        QuerySpecification.withStatement("MATCH (n:TemporalsNode) RETURN n.id ORDER BY n.instant")
            .transform(String::class.java)
    )
    assertEquals(listOf("ce", "later"), ordered)
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): StatelessGraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return StatelessGraphObjectManager(pm, mapper, registry)
}

class StatelessTemporalMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()
        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager
        @JvmStatic @BeforeAll fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider("mg-temporal", DatabaseType.MEMGRAPH, container.host,
                container.getMappedPort(7687), "", "", null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry)
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
        }
        @JvmStatic @AfterAll fun teardown() = provider.end()
    }

    @Test fun `temporal round-trip and range on Memgraph`() = verifyTemporal(gom, pm)
}

class StatelessTemporalFalkorDbTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()
        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager
        @JvmStatic @BeforeAll fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider("falkor-temporal", container.host,
                container.getMappedPort(6379), null, "temporaltest", subtypeRegistry = registry)
            pm = NonTransactionalPersistenceManager(provider, "temporaltest", DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
        }
        @JvmStatic @AfterAll fun teardown() = provider.end()
    }

    @BeforeEach fun ensureGraph() {
        pm.execute(QuerySpecification.withStatement("CREATE (:_Init {x: 1})"))
    }

    @Test fun `temporal round-trip and range on FalkorDB (ISO string via coercer)`() = verifyTemporal(gom, pm)
}

@OracleTests
class StatelessTemporalOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = OracleTestDatabase.clear(pm)

    @Test fun `temporal round-trip and range on Oracle`() = verifyTemporal(buildGom(pm, registry), OracleTestDatabase.fixtures(pm))
}

@PostgresTests
class StatelessTemporalPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = PostgresTestDatabase.clear(pm)

    @Test fun `temporal round-trip and range on Postgres`() = verifyTemporal(buildGom(pm, registry), PostgresTestDatabase.fixtures(pm))
}

class StatelessTemporalSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clean() = SqliteTestDatabase.clear(pm)

    @Test fun `temporal round-trip and range on SQLite`() = verifyTemporal(buildGom(pm, registry), SqliteTestDatabase.fixtures(pm))
}
