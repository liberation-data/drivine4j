package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderExpression
import org.drivine.query.dsl.UnsupportedOrderFunctionException
import org.drivine.query.dsl.above
import org.drivine.query.dsl.atMost
import org.drivine.query.dsl.case
import org.drivine.query.dsl.coalesce
import org.drivine.query.dsl.div
import org.drivine.query.dsl.epochSeconds
import org.drivine.query.dsl.temporal.TemporalOrder
import org.drivine.query.dsl.temporal.epochSeconds
import org.drivine.query.dsl.exp
import org.drivine.query.dsl.floor
import org.drivine.query.dsl.hasValue
import org.drivine.query.dsl.minus
import org.drivine.query.dsl.query
import org.drivine.query.dsl.times
import org.drivine.query.dsl.unaryMinus
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.decay.Belief
import sample.decay.BeliefQueryDsl
import sample.decay.BeliefView
import sample.decay.BeliefViewQueryDsl
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * An order by an expression, verified on Neo4j, FalkorDB, Memgraph and Oracle: the roots ranked by
 * a score computed in the database from their properties and a bound `asOf`, the top of them taken
 * by a limit, ties broken by the next term, and an expression the engine cannot write refused
 * before anything runs.
 *
 * The score is the effective confidence of embabel/dice#162: confidence, times a boost where there
 * is one, decayed by the whole days since the belief was last read, and zero outside the window in
 * which it is valid.
 *
 * | id | confidence | decay | boost | last read     | window          | score          |
 * |----|------------|-------|-------|---------------|-----------------|----------------|
 * | a  | 0.9        | 0.1   |       | 2 days ago    |                 | 0.9 e^-0.2     |
 * | b  | 0.8        | 0     | 0.5   | 10 days ago   |                 | 0.4            |
 * | c  | 1.0        | 0.5   |       | 1 day ago     |                 | e^-0.5         |
 * | d  | 0.95       | 0     |       | now           | from tomorrow   | 0              |
 * | e  | 0.99       | 0     |       | now           | to yesterday    | 0              |
 * | g  | 0.8        | 0     |       | 3 days ago    | yesterday on    | 0.8            |
 * | h  | 0.9        | 0.1   |       | 2 days 23h ago|                 | 0.9 e^-0.2     |
 *
 * h ties with a, since the days are whole, and d with e: the id breaks both ties.
 */
private val asOf: Instant = Instant.parse("2026-01-10T00:00:00Z")

private fun belief(id: String, confidence: Double, decay: Double, readBefore: Duration, boost: Double? = null, validFrom: Instant? = null, validTo: Instant? = null) =
    asOf.minus(readBefore).let { read ->
        Belief(id, confidence, decay, read.epochSecond, read, boost, validFrom, validTo)
    }

private val BELIEFS = listOf(
    belief("a", 0.9, 0.1, Duration.ofDays(2)),
    belief("b", 0.8, 0.0, Duration.ofDays(10), boost = 0.5),
    belief("c", 1.0, 0.5, Duration.ofDays(1)),
    belief("d", 0.95, 0.0, Duration.ZERO, validFrom = asOf.plus(Duration.ofDays(1))),
    belief("e", 0.99, 0.0, Duration.ZERO, validTo = asOf.minus(Duration.ofDays(1))),
    belief("g", 0.8, 0.0, Duration.ofDays(3), validFrom = asOf.minus(Duration.ofDays(1))),
    belief("h", 0.9, 0.1, Duration.ofDays(2).plusHours(23)),
)

private val RANKED = listOf("g", "a", "h", "c", "b", "d", "e")

/** The days since last read from the seconds stored beside the moment, which every engine can do arithmetic on. */
private fun BeliefQueryDsl.daysFromSeconds(): OrderExpression = floor((asOf.epochSecond - lastReadSeconds) / 86400)

/** The days since last read from the moment itself, which an engine must be able to read as a number. */
@OptIn(TemporalOrder::class)
private fun BeliefQueryDsl.daysFromMoment(): OrderExpression = floor((epochSeconds(asOf) - epochSeconds(lastRead)) / 86400)

private fun BeliefQueryDsl.score(days: OrderExpression): OrderExpression = case(
    (validFrom above asOf) or (validTo atMost asOf) then 0.0,
    otherwise = confidence * coalesce(boost, 1.0) * exp(-decay * days),
)

/**
 * @param readsMoments whether the engine can read a stored moment as a number; where it cannot, an
 *   order that asks it to is refused before the load runs
 * @param refusesDivisionByZero whether the engine fails a statement that divides by a whole-number
 *   zero, as `div` documents; where it does not, the load runs and every root comes back
 */
private fun verify(gom: StatelessGraphObjectManager, readsMoments: Boolean, refusesDivisionByZero: Boolean) {
    BELIEFS.forEach { gom.save(it) }

    fun fragments(spec: GraphQuerySpec<BeliefQueryDsl>.() -> Unit) =
        gom.loadAll(Belief::class.java, BeliefQueryDsl.INSTANCE, spec).map { it.id }
    fun views(spec: GraphQuerySpec<BeliefViewQueryDsl>.() -> Unit) =
        gom.loadAll(BeliefView::class.java, BeliefViewQueryDsl.INSTANCE, spec).map { it.belief.id }

    // Ranked in the database, the id breaking the ties of the score.
    assertEquals(RANKED, fragments { orderBy { query.score(query.daysFromSeconds()).desc(); query.id.asc() } })
    assertEquals(RANKED, views { orderBy { query.belief.score(query.belief.daysFromSeconds()).desc(); query.belief.id.asc() } })

    // The top k, and only the top k, come back.
    assertEquals(RANKED.take(3), fragments { orderBy { query.score(query.daysFromSeconds()).desc(); query.id.asc() }; limit(3) })
    assertEquals(RANKED.take(3), views { orderBy { query.belief.score(query.belief.daysFromSeconds()).desc(); query.belief.id.asc() }; limit(3) })

    // The next term breaks the ties the other way when it runs the other way.
    assertEquals(listOf("g", "h", "a", "c", "b", "e", "d"), fragments { orderBy { query.score(query.daysFromSeconds()).desc(); query.id.desc() } })

    // Ascending, with a where beside it: d and e tie at zero, a and h at 0.9 e^-0.2.
    assertEquals(
        listOf("d", "e", "c", "a", "h"),
        fragments {
            where { query.confidence gt 0.85 }
            orderBy { query.score(query.daysFromSeconds()).asc(); query.id.asc() }
        },
    )

    // Division is of real numbers: h, 2.96 days since read, comes before a, 2 days, where whole-number
    // division would tie them at 2 and the id would put a first.
    assertEquals(
        listOf("h", "a", "c"),
        fragments {
            where { query.decay gt 0.05 }
            orderBy { ((asOf.epochSecond - query.lastReadSeconds) / 86400).desc(); query.id.asc() }
        },
    )

    // A test for a value: only b has a boost.
    assertEquals(
        listOf("b", "a", "c", "d", "e", "g", "h"),
        fragments { orderBy { case(query.boost.hasValue() then 1, otherwise = 0).desc(); query.id.asc() } },
    )

    // Division by a whole-number zero: the engine's own behaviour, as `div` documents it.
    val byZero = { fragments { orderBy { (query.confidence / 0).desc(); query.id.asc() } } }
    if (refusesDivisionByZero) {
        // The store fails it: an error of the engine, not a refusal as the statement is built.
        assertThrows<DrivineException> { byZero() }
    } else {
        assertEquals(RANKED.sorted(), byZero().sorted())
    }

    // From the moment itself, where the engine can read one as a number; refused before anything runs where it cannot.
    if (readsMoments) {
        assertEquals(RANKED.take(3), fragments { orderBy { query.score(query.daysFromMoment()).desc(); query.id.asc() }; limit(3) })
        assertEquals(RANKED, views { orderBy { query.belief.score(query.belief.daysFromMoment()).desc(); query.belief.id.asc() } })
    } else {
        assertThrows<UnsupportedOrderFunctionException> { fragments { orderBy { query.score(query.daysFromMoment()).desc() }; limit(3) } }
        assertThrows<UnsupportedOrderFunctionException> { views { orderBy { query.belief.score(query.belief.daysFromMoment()).desc() } } }
    }
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class OrderExpressionNeo4jTest {
    companion object {
        private const val PASSWORD = SharedNeo4j.PASSWORD

        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-order-expression", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `roots are ranked by a computed score on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()), readsMoments = true, refusesDivisionByZero = true)
}

class OrderExpressionFalkorDbTest {
    companion object {
        private const val GRAPH = "orderexpressiontest"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-order-expression", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `roots are ranked by a computed score on FalkorDB, which refuses to read a moment as a number`() =
        verify(buildGom(pm, SubtypeRegistry()), readsMoments = false, refusesDivisionByZero = false)
}

class OrderExpressionMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-order-expression", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `roots are ranked by a computed score on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()), readsMoments = true, refusesDivisionByZero = false)
}

@OracleTests
class OrderExpressionOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clear() = OracleTestDatabase.clear(pm)

    @Test
    fun `roots are ranked by a computed score on Oracle`() = verify(buildGom(pm, registry), readsMoments = true, refusesDivisionByZero = true)
}

@PostgresTests
class OrderExpressionPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clear() = PostgresTestDatabase.clear(pm)

    @Test
    fun `roots are ranked by a computed score on Postgres`() = verify(buildGom(pm, registry), readsMoments = true, refusesDivisionByZero = true)
}

class OrderExpressionSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun clear() = SqliteTestDatabase.clear(pm)

    @Test
    fun `roots are ranked by a computed score on SQLite`() = verify(buildGom(pm, registry), readsMoments = true, refusesDivisionByZero = false)
}
