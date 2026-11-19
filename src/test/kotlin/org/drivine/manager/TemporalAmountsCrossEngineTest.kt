package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.query.grammar.CypherDialect
import org.drivine.sqlite.AmountsNode
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import java.time.Duration
import java.time.OffsetTime
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A save of an `OffsetTime`, a `Duration` and a `Period` (liberation-data/drivine4j#862), on every store: each loads back
 * as it was saved, alone, in a list and in a property bag; where the store has the kind, it is stored as that kind, so
 * Cypher's `time(...)` and `duration(...)` find it; and a node a save wrote before, with the text or the number, still
 * loads. Neo4j holds each natively; the SQL stores hold the store layout's `time` and `duration` tags; Memgraph has no time
 * with an offset and no period, and holds those two as text; FalkorDB holds all three as text.
 */
abstract class TemporalAmountsContract {

    abstract val pm: PersistenceManager

    private val om: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun ids(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<String> =
        pm.query(QuerySpecification.withStatement(cypher).bind(parameters).transform(String::class.java)).sorted()

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n:Amounts) DETACH DELETE n"))

    @Test
    fun `each loads back as it was saved, alone, in a list and in a property bag`() {
        val node = AmountsNode.full("a")
        om.save(node)
        assertEquals(node, om.load("a", AmountsNode::class.java))
    }

    @Test
    fun `each is stored as its kind where the store has it, and found by Cypher's own value of that kind`() {
        assumeTrue(pm.type != DatabaseType.FALKORDB, "FalkorDB holds each as text")
        om.save(AmountsNode.full("a"))
        om.save(AmountsNode("b", OffsetTime.parse("10:11:12+03:00"), Duration.ofHours(25), Period.ofMonths(13)))

        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.duration = duration('PT26H') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.`waits.queue` = duration('PT3M') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE duration('PT-1.5S') IN n.durations RETURN n.id"))
        if (pm.type == DatabaseType.MEMGRAPH) {
            // Memgraph has no time with an offset and no period: each is its text, as a save wrote it before.
            assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.time = '10:11:12+02:00' AND n.period = 'P1Y2M' RETURN n.id"))
            return
        }
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.time = time('10:11:12+02:00') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE n.period = duration('P1Y2M') RETURN n.id"))
        assertEquals(listOf("a"), ids("MATCH (n:Amounts) WHERE time('23:00Z') IN n.times AND duration('P3D') IN n.periods RETURN n.id"))
        assertEquals(listOf("b"), ids("MATCH (n:Amounts) WHERE n.time < time('10:11:12+02:00') RETURN n.id"))
        assertEquals(listOf("b"), ids("MATCH (n:Amounts) WHERE n.period.months = 13 RETURN n.id"))
        // Each part of a duration is compared on its own: 26 hours are not a day and two hours.
        assertEquals(emptyList(), ids("MATCH (n:Amounts) WHERE n.duration = duration('P1DT2H') RETURN n.id"))
    }

    @Test
    fun `a duration Cypher writes loads into a Duration, and one of months and days into a Period`() {
        assumeTrue(pm.type != DatabaseType.FALKORDB, "FalkorDB holds a saved duration as text")
        pm.execute(QuerySpecification.withStatement("CREATE (:Amounts {id: 'c', duration: duration('P1DT2H')})"))
        assertEquals(Duration.ofHours(26), om.load("c", AmountsNode::class.java)?.duration)
        assumeTrue(pm.type != DatabaseType.MEMGRAPH, "Memgraph has no duration of months")
        pm.execute(QuerySpecification.withStatement("CREATE (:Amounts {id: 'p', period: duration('P1Y2M3D')})"))
        assertEquals(Period.of(1, 2, 3), om.load("p", AmountsNode::class.java)?.period)
    }

    @Test
    fun `a period and a duration of nothing load back as they were saved`() {
        om.save(AmountsNode("z", duration = Duration.ZERO, period = Period.ZERO))
        val loaded = assertNotNull(om.load("z", AmountsNode::class.java))
        assertEquals(Duration.ZERO to Period.ZERO, loaded.duration to loaded.period)
    }

    @Test
    fun `a node a save wrote before, with the text or the number, still loads, and its duration is found once saved again`() {
        // As a save wrote them before #862: JavaTimeModule's text, and a duration's seconds.
        pm.execute(
            QuerySpecification.withStatement("CREATE (:Amounts {id: 'old', time: \$time, duration: \$duration, period: \$period})")
                .bind(mapOf("time" to "10:11:12+02:00", "duration" to 93600.0, "period" to "P1Y2M"))
        )
        val old = assertNotNull(om.load("old", AmountsNode::class.java))
        assertEquals(AmountsNode("old", AmountsNode.TIME, AmountsNode.DURATION, AmountsNode.PERIOD), old)

        om.save(AmountsNode("new", AmountsNode.TIME, AmountsNode.DURATION, AmountsNode.PERIOD))
        fun found() = om.loadAll(AmountsNode::class.java, Any()) {
            conditions += PropertyCondition("n.duration", ComparisonOperator.EQUALS, AmountsNode.DURATION)
        }.map { it.id }.sorted()
        assertEquals(listOf("new"), found(), "a duration stored as its seconds is not the duration")
        om.save(old)
        assertEquals(listOf("new", "old"), found())
    }
}

class TemporalAmountsNeo4jTest : TemporalAmountsContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-temporal-amounts", type = DatabaseType.NEO4J,
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

    @Test
    fun `each is stored as the driver's own value of its kind`() {
        pm.execute(QuerySpecification.withStatement("MATCH (n:Amounts) DETACH DELETE n"))
        StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()).save(AmountsNode.full("a"))
        assertEquals(
            "ZONED TIME NOT NULL, DURATION NOT NULL, DURATION NOT NULL, LIST<DURATION NOT NULL> NOT NULL",
            pm.query(
                QuerySpecification.withStatement(
                    "MATCH (n:Amounts {id: 'a'}) RETURN valueType(n.time) + ', ' + valueType(n.duration) + ', ' + valueType(n.period) + ', ' + valueType(n.durations)"
                ).transform(String::class.java)
            ).single(),
        )
    }
}

class TemporalAmountsFalkorDbTest : TemporalAmountsContract() {
    companion object {
        private const val GRAPH = "temporalamounts"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-temporal-amounts", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
}

class TemporalAmountsMemgraphTest : TemporalAmountsContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-temporal-amounts", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
}

@OracleTests
class TemporalAmountsOracleTest : TemporalAmountsContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))
}

@PostgresTests
class TemporalAmountsPostgresTest : TemporalAmountsContract() {
    override val pm: PersistenceManager = PostgresTestDatabase.fixtures(PostgresTestDatabase.graphManager("contracts"))
}

/** SQLite's run of the contract; [org.drivine.sqlite.SqliteTemporalAmountsTest] reads what its tables hold. */
class TemporalAmountsSqliteTest : TemporalAmountsContract() {
    override val pm: PersistenceManager = SqliteTestDatabase.fixtures(SqliteTestDatabase.graphManager("contracts"))
}
