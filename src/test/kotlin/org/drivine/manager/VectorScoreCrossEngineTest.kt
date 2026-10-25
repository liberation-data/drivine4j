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
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.vector.ScoredPairNode
import kotlin.test.assertEquals

/**
 * The score of a known pair of vectors, the same on every store: `(1 + cos) / 2` for cosine and
 * `1 / (1 + d²)` for Euclidean, from [GraphObjectOperations.loadNearest] and
 * [GraphObjectOperations.loadNearestAmong] alike.
 *
 * Each node is measured from Q, at `[1, 0]`:
 *
 * | node | cosine embedding | cos  | score | euclidean embedding | d² | score  |
 * |------|------------------|------|-------|---------------------|----|--------|
 * | Q    | [1, 0]           | 1    | 1     | [1, 0]              | 0  | 1      |
 * | P    | [0.6, 0.8]       | 0.6  | 0.8   | [0.6, 0.8]          | 0.8| 1/1.8  |
 * | N    | [-0.6, 0.8]      | -0.6 | 0.2   | [3, 4]              | 20 | 1/21   |
 * | O    | [-1, 0]          | -1   | 0     | [1, 2]              | 4  | 0.2    |
 *
 * The negative cosines are there for Memgraph, whose `similarity` is the absolute value of
 * `1 - distance`, so N and O would score as P and Q do were it read.
 */
private val QUERY = listOf(1f, 0f)
private val SET = listOf("Q", "P", "N", "O")

private val COSINE = listOf("Q" to 1.0, "P" to 0.8, "N" to 0.2, "O" to 0.0)
private val EUCLIDEAN = listOf("Q" to 1.0, "P" to 1 / 1.8, "O" to 0.2, "N" to 1 / 21.0)

/** Wide enough that every store, approximate or not, sees every node. */
private const val WIDE = 10

/** A quantizing Neo4j index reports 0.7998811 for an exact 0.8. */
private const val TOLERANCE = 1e-3

/** Seeds the nodes. [vec] writes an embedding literal as the store keeps one. */
private fun seedPairs(pm: PersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("MATCH (n:ScoredPair) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:ScoredPair {id: 'Q', cosine: ${vec("[1.0, 0.0]")}, euclidean: ${vec("[1.0, 0.0]")}})
            CREATE (:ScoredPair {id: 'P', cosine: ${vec("[0.6, 0.8]")}, euclidean: ${vec("[0.6, 0.8]")}})
            CREATE (:ScoredPair {id: 'N', cosine: ${vec("[-0.6, 0.8]")}, euclidean: ${vec("[3.0, 4.0]")}})
            CREATE (:ScoredPair {id: 'O', cosine: ${vec("[-1.0, 0.0]")}, euclidean: ${vec("[1.0, 2.0]")}})
            """.trimIndent()
        )
    )
}

private fun ensureIndexes(pm: PersistenceManager) {
    pm.indexes.ensure(VectorIndexSpec("ScoredPair", "cosine", 2, SimilarityFunction.COSINE))
    pm.indexes.ensure(VectorIndexSpec("ScoredPair", "euclidean", 2, SimilarityFunction.EUCLIDEAN))
}

/** The contract each store's subclass runs. */
abstract class VectorScoreContract {

    protected abstract val gom: GraphObjectOperations

    private fun assertScored(expected: List<Pair<String, Double>>, actual: List<Pair<String, Double>>) {
        assertEquals(expected.map { it.first }, actual.map { it.first }, "scored $actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.second, a.second, TOLERANCE, "${e.first} in $actual") }
    }

    private fun nearest(property: String, threshold: Double? = null, searchK: Int? = null) =
        gom.loadNearest(ScoredPairNode::class.java, property, QUERY, WIDE, threshold, searchK).map { it.value.id to it.score }

    private fun among(property: String, threshold: Double? = null) =
        gom.loadNearestAmong(ScoredPairNode::class.java, SET, topK = WIDE, threshold = threshold, property = property, searchK = WIDE)
            .getValue("Q").map { it.value to it.score }

    @Test
    fun `loadNearest gives a cosine pair (1 + cos) over 2`() {
        assertScored(COSINE, nearest("cosine"))
    }

    @Test
    fun `loadNearest gives a Euclidean pair 1 over (1 + d squared)`() {
        assertScored(EUCLIDEAN, nearest("euclidean"))
    }

    /** Asking for fewer than searched rescores on Neo4j: the rescored score is the same. */
    @Test
    fun `an over-fetched loadNearest gives the same scores`() {
        assertScored(COSINE.take(2), gom.loadNearest(ScoredPairNode::class.java, "cosine", QUERY, 2, null, WIDE).map { it.value.id to it.score })
        assertScored(EUCLIDEAN.take(2), gom.loadNearest(ScoredPairNode::class.java, "euclidean", QUERY, 2, null, WIDE).map { it.value.id to it.score })
    }

    @Test
    fun `loadNearestAmong gives the scores loadNearest gives`() {
        assertScored(COSINE.drop(1), among("cosine"))
        assertScored(EUCLIDEAN.drop(1), among("euclidean"))
    }

    @Test
    fun `a threshold is compared with that score`() {
        assertScored(COSINE.take(2), nearest("cosine", threshold = 0.5))
        assertScored(EUCLIDEAN.take(3), nearest("euclidean", threshold = 0.1))
        assertScored(COSINE.subList(1, 2), among("cosine", threshold = 0.5))
        assertScored(EUCLIDEAN.subList(1, 3), among("euclidean", threshold = 0.1))
    }
}

private fun stateless(pm: PersistenceManager, registry: SubtypeRegistry) = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class VectorScoreNeo4jTest : VectorScoreContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var manager: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-vector-score", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j", config = emptyMap(),
                subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            manager = stateless(pm, registry)
            ensureIndexes(pm)
            seedPairs(pm) { it }
            // A vector index is filled in the background; it must hold the nodes before it is searched.
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
}

class VectorScoreFalkorDbTest : VectorScoreContract() {
    companion object {
        private const val GRAPH = "vectorscore"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var manager: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-vector-score", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            manager = stateless(pm, registry)
            ensureIndexes(pm)
            // FalkorDB indexes a vector only when it is stored as its own vector type.
            seedPairs(pm) { "vecf32($it)" }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
}

class VectorScoreMemgraphTest : VectorScoreContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-vector-score", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            manager = stateless(pm, registry)
            ensureIndexes(pm)
            // Seeded once: Memgraph's vector index goes on yielding a node deleted from under it, and the
            // search then fails reading it (memgraph/memgraph#4975), so the nodes are not reseeded per test.
            seedPairs(pm) { it }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
}

@PostgresTests
class VectorScorePostgresTest : VectorScoreContract() {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)

    @BeforeEach
    fun seed() {
        ensureIndexes(pm)
        seedPairs(PostgresTestDatabase.fixtures(pm)) { it }
    }
}

@OracleTests
class VectorScoreOracleTest : VectorScoreContract() {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)

    @BeforeEach
    fun seed() {
        ensureIndexes(pm)
        seedPairs(OracleTestDatabase.fixtures(pm)) { it }
    }
}

class VectorScoreSqliteTest : VectorScoreContract() {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)

    @BeforeEach
    fun seed() {
        ensureIndexes(pm)
        seedPairs(SqliteTestDatabase.fixtures(pm)) { it }
    }
}
