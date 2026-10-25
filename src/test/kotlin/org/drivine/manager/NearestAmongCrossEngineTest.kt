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
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.VectorIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.vector.DocNode
import sample.vector.DocView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GraphObjectOperations.loadNearestAmong] on every store with a vector search: each of a set of ids
 * gets its nearest among the same set, and nothing else.
 *
 * The embeddings are 4-dimensional and lie in one plane, so each cosine is plain to see. Each store
 * scores a pair as its `loadNearest` does (see [NearestAmongContract.score]): `(1 + cos) / 2` on Neo4j
 * and the SQL stores, the cosine itself on FalkorDB and Memgraph.
 *
 * | pair | cos  |
 * |------|------|
 * | A–B  | 0.8  |
 * | A–C  | 0.6  |
 * | A–D  | 0.0  |
 * | B–C  | 0.96 |
 * | B–D  | 0.6  |
 * | C–D  | 0.8  |
 *
 * X lies nearest A of all (cos ≈ 0.994) but is outside the set, so it must never come back. E is in
 * the set with no embedding, and Z names no node: neither is a key.
 */
private val SET = listOf("A", "B", "C", "D", "E", "Z")

/** Asks the index for more than there are nodes, so every store, approximate or not, sees them all. */
private const val WIDE = 10

private const val TOLERANCE = 1e-6

/** Seeds the nodes. [vec] writes an embedding literal as the store keeps one. */
private fun seedAmong(pm: PersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (auth:Author {id: 'au1', name: 'Ada'})
            CREATE (a:Doc {id: 'A', title: 'Alpha', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (b:Doc {id: 'B', title: 'Beta',  embedding: ${vec("[0.8, 0.6, 0.0, 0.0]")}})
            CREATE (c:Doc {id: 'C', title: 'Gamma', embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
            CREATE (d:Doc {id: 'D', title: 'Delta', embedding: ${vec("[0.0, 1.0, 0.0, 0.0]")}})
            CREATE (x:Doc {id: 'X', title: 'Outsider', embedding: ${vec("[0.9, 0.1, 0.0, 0.0]")}})
            CREATE (e:Doc {id: 'E', title: 'Empty'})
            CREATE (a)-[:WRITTEN_BY]->(auth)
            CREATE (b)-[:WRITTEN_BY]->(auth)
            CREATE (c)-[:WRITTEN_BY]->(auth)
            """.trimIndent()
        )
    )
}

/** Adds W, whose embedding has 3 dimensions where the index has 4. [vec] writes it as [seedAmong]'s does. */
private fun seedMisSized(pm: PersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("CREATE (:Doc {id: 'W', title: 'Wrong size', embedding: ${vec("[1.0, 0.0, 0.0]")}})"))
}

/**
 * The contract each store's subclass runs. [exact] for a store that measures every node rather than
 * asking an approximate index, where the default searchK changes nothing.
 */
abstract class NearestAmongContract {

    protected abstract val gom: GraphObjectOperations
    protected abstract val exact: Boolean

    /**
     * Adds W (see [seedMisSized]) to the nodes, or does nothing and returns false where the store will not
     * keep a node whose embedding is not of its index's size.
     */
    protected abstract fun addMisSized(): Boolean

    /**
     * Whether the store refuses to search from an embedding its index cannot hold, as Neo4j's and
     * FalkorDB's vector procedures refuse a vector of another size, rather than leaving that id out.
     */
    protected open val refusesMisSized = false

    /** The score the store's `loadNearest` gives a pair of cosine [cos]. */
    protected open fun score(cos: Double): Double = (1 + cos) / 2

    private fun ids(near: List<Scored<String>>?) = near?.map { it.value }
    private fun assertScores(expected: List<Double>, near: List<Scored<String>>?) {
        val scores = near.orEmpty().map { it.score }
        assertEquals(expected.size, scores.size, "scores $scores")
        expected.zip(scores).forEach { (e, a) -> assertEquals(e, a, 1e-4, "scores $scores") }
    }

    @Test
    fun `each id gets its nearest among the set only, never itself, best first`() {
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 10, searchK = WIDE)

        assertEquals(listOf("A", "B", "C", "D"), found.keys.toList())
        assertEquals(listOf("B", "C", "D"), ids(found["A"]))
        assertEquals(listOf("C", "A", "D"), ids(found["B"]))
        assertEquals(listOf("B", "D", "A"), ids(found["C"]))
        assertEquals(listOf("C", "B", "A"), ids(found["D"]))
        assertScores(listOf(0.8, 0.6, 0.0).map(::score), found["A"])
        assertScores(listOf(0.96, 0.8, 0.6).map(::score), found["B"])
        assertTrue(found.values.flatten().none { it.value == "X" }, "X is outside the set: $found")
        found.forEach { (seed, near) -> assertTrue(near.none { it.value == seed }, "$seed found itself") }
    }

    @Test
    fun `topK caps each id's neighbours`() {
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 1, searchK = WIDE)

        assertEquals(mapOf("A" to listOf("B"), "B" to listOf("C"), "C" to listOf("B"), "D" to listOf("C")), found.mapValues { ids(it.value) })
    }

    @Test
    fun `a threshold drops the neighbours that score under it, and an id left with none maps to an empty list`() {
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 10, threshold = score(0.7), searchK = WIDE)
        assertEquals(
            mapOf("A" to listOf("B"), "B" to listOf("C", "A"), "C" to listOf("B", "D"), "D" to listOf("C")),
            found.mapValues { ids(it.value) },
        )

        val none = gom.loadNearestAmong(DocNode::class.java, listOf("A", "D"), topK = 10, threshold = score(0.2), searchK = WIDE)
        assertEquals(mapOf("A" to emptyList<String>(), "D" to emptyList()), none.mapValues { ids(it.value) })
    }

    @Test
    fun `an id with no embedding, or no node, is no key`() {
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 10, searchK = WIDE)

        assertTrue("E" !in found && "Z" !in found, "keys ${found.keys}")
        assertEquals(emptyMap(), gom.loadNearestAmong(DocNode::class.java, listOf("E", "Z"), topK = 3, searchK = WIDE))
        assertEquals(emptyMap(), gom.loadNearestAmong(DocNode::class.java, emptyList(), topK = 3))
    }

    @Test
    fun `an embedding of another size than the index's is measured against none of the others, or refused`() {
        assumeTrue(addMisSized(), "the store does not keep an embedding of another size")
        val search = { gom.loadNearestAmong(DocNode::class.java, listOf("A", "B", "W"), topK = 10, searchK = WIDE) }
        if (refusesMisSized) {
            assertTrue(runCatching(search).isFailure, "a vector of another size is searched from")
            return
        }
        val found = search()

        assertEquals(listOf("B"), ids(found["A"]))
        assertEquals(listOf("A"), ids(found["B"]))
        assertTrue(found["W"].isNullOrEmpty(), "W measured: $found")
    }

    @Test
    fun `each score is the one loadNearest gives the same pair`() {
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 10, searchK = WIDE)
        val embeddings = gom.loadAll(DocNode::class.java).associate { it.id to it.embedding }

        for ((seed, near) in found) {
            val searched = gom.loadNearest(DocNode::class.java, embeddings.getValue(seed)!!, topK = WIDE, searchK = WIDE)
                .associate { it.value.id to it.score }
            near.forEach { assertEquals(searched.getValue(it.value), it.score, TOLERANCE, "$seed–${it.value}") }
        }
    }

    @Test
    fun `a view searches its root fragment's embedding, and its relationships play no part`() {
        val byView = gom.loadNearestAmong(DocView::class.java, SET, topK = 10, searchK = WIDE)
        val byFragment = gom.loadNearestAmong(DocNode::class.java, SET, topK = 10, searchK = WIDE)

        // D has no author, which a view requires, yet it is searched and found: the set is named by id.
        assertEquals(byFragment.mapValues { ids(it.value) }, byView.mapValues { ids(it.value) })
        assertEquals(listOf("C", "B", "A"), ids(byView["D"]))
    }

    @Test
    fun `the embedding can be named, and the map keeps the order the ids were given in`() {
        val found = gom.loadNearestAmong(DocNode::class.java, listOf("D", "A", "C", "A"), topK = 10, threshold = null, property = "embedding", searchK = WIDE)

        assertEquals(listOf("D", "A", "C"), found.keys.toList())
        assertEquals(listOf("C", "A"), ids(found["D"]))
    }

    @Test
    fun `the reified form hands the class form its arguments`() {
        assertEquals(
            gom.loadNearestAmong(DocNode::class.java, SET, topK = 2, threshold = score(0.5), property = "embedding", searchK = WIDE),
            gom.loadNearestAmong<DocNode>(SET, topK = 2, threshold = score(0.5), property = "embedding", searchK = WIDE),
        )
    }

    @Test
    fun `the default searchK asks an approximate index for one more than topK, before the set filters`() {
        // A's nearest overall are A itself, then X, then B. Asked for topK + 1 = 3, an index finds those
        // three, and of them only B is in the set; a store that measures every node finds B and C.
        val found = gom.loadNearestAmong(DocNode::class.java, SET, topK = 2)

        assertEquals(if (exact) listOf("B", "C") else listOf("B"), ids(found["A"]))
    }

    @Test
    fun `an empty set is refused as any set is when the embedding named has no index`() {
        val failure = runCatching { gom.loadNearestAmong(DocNode::class.java, emptyList(), topK = 3, property = "title") }.exceptionOrNull()
        assertTrue(failure is DrivineException, "refused: $failure")
    }

    @Test
    fun `a searchK under topK is refused`() {
        val failure = runCatching { gom.loadNearestAmong(DocNode::class.java, SET, topK = 3, threshold = null, property = null, searchK = 2) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "refused: $failure")
    }
}

private fun stateless(pm: PersistenceManager, registry: SubtypeRegistry) = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class NearestAmongNeo4jTest : NearestAmongContract() {
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
                name = "neo-nearest-among", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j", config = emptyMap(),
                subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            manager = stateless(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
    override val exact = false
    override val refusesMisSized = true
    override fun addMisSized() = true.also { seedMisSized(pm) { it } }

    @BeforeEach
    fun seed() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(pm) { it }
        // A vector index is filled in the background; it must hold the nodes before it is searched.
        pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
    }
}

class NearestAmongFalkorDbTest : NearestAmongContract() {
    companion object {
        private const val GRAPH = "nearestamong"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var manager: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-nearest-among", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            manager = stateless(pm, registry)
            pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
    override val exact = false

    /** FalkorDB and Memgraph give the cosine itself, as their `loadNearest` does. */
    override fun score(cos: Double) = cos

    override val refusesMisSized = true
    override fun addMisSized() = true.also { seedMisSized(pm) { "vecf32($it)" } }

    /** FalkorDB indexes a vector only when it is stored as its own vector type. */
    @BeforeEach
    fun seed() = seedAmong(pm) { "vecf32($it)" }
}

class NearestAmongMemgraphTest : NearestAmongContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var manager: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-nearest-among", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            manager = stateless(pm, registry)
            pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
            // Seeded once: Memgraph's vector index goes on yielding a node deleted from under it, and the
            // search then fails reading it (memgraph/memgraph#4975), so the nodes are not reseeded per test.
            seedAmong(pm) { it }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val gom: GraphObjectOperations get() = manager
    override val exact = false

    /** FalkorDB and Memgraph give the cosine itself, as their `loadNearest` does. */
    override fun score(cos: Double) = cos

    /** Memgraph refuses to write an embedding of another size than its index's. */
    override fun addMisSized() = false
}

/** Searched through the vector index made for the label, so approximate, as on the graph engines. */
@OracleTests
class NearestAmongOracleTest : NearestAmongContract() {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)
    private val fixtures = OracleTestDatabase.fixtures(pm)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)
    override fun addMisSized() = true.also { seedMisSized(fixtures) { it } }
    override val exact = false

    @BeforeEach
    fun seed() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(fixtures) { it }
    }
}

/** Searched through the vector index made for the label, so approximate, as on the graph engines. */
@PostgresTests
class NearestAmongPostgresTest : NearestAmongContract() {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)
    private val fixtures = PostgresTestDatabase.fixtures(pm)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)
    override fun addMisSized() = true.also { seedMisSized(fixtures) { it } }
    override val exact = false

    @BeforeEach
    fun seed() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(fixtures) { it }
    }
}

/** No vector index: every pair of the set is measured, so the answer is exact. */
class NearestAmongSqliteTest : NearestAmongContract() {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)
    private val fixtures = SqliteTestDatabase.fixtures(pm)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)
    override fun addMisSized() = true.also { seedMisSized(fixtures) { it } }
    override val exact = true

    @BeforeEach
    fun seed() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(fixtures) { it }
    }
}

/** No vector index made for the label: every pair of the set is measured, so the answer is exact. */
@OracleTests
class NearestAmongOracleUnindexedTest : NearestAmongContract() {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)
    private val fixtures = OracleTestDatabase.fixtures(pm)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)
    override fun addMisSized() = true.also { seedMisSized(fixtures) { it } }
    override val exact = true

    @BeforeEach
    fun seed() {
        pm.indexes.drop(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(fixtures) { it }
    }
}

/** No vector index made for the label: every pair of the set is measured, so the answer is exact. */
@PostgresTests
class NearestAmongPostgresUnindexedTest : NearestAmongContract() {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)
    private val fixtures = PostgresTestDatabase.fixtures(pm)

    override val gom: GraphObjectOperations get() = stateless(pm, registry)
    override fun addMisSized() = true.also { seedMisSized(fixtures) { it } }
    override val exact = true

    @BeforeEach
    fun seed() {
        pm.indexes.drop(VectorIndexSpec("Doc", "embedding", 4))
        seedAmong(fixtures) { it }
    }
}
