package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nodes enough that an index over them takes a while to build. */
private const val SEEDED = 20_000

private fun PersistenceManager.run(cypher: String, params: Map<String, Any?> = emptyMap()) =
    execute(QuerySpecification.withStatement(cypher).bind(params))

private fun PersistenceManager.rows(cypher: String, params: Map<String, Any?> = emptyMap()): List<Any?> =
    query(QuerySpecification.withStatement(cypher).bind(params))

/** Seeds [SEEDED] nodes of [label], each with a number `k`, a text `s` and an embedding `e` of four dimensions. */
private fun PersistenceManager.seed(label: String) =
    run("UNWIND range(1, $SEEDED) AS i CREATE (:$label {k: i, s: 'text of node ' + toString(i), e: [toFloat(i), 1.0, 2.0, 3.0]})")

/**
 * Neo4j builds an index in the background: `CREATE INDEX … IF NOT EXISTS` returns while it is still POPULATING.
 * A write too large for a range index that commits just as one comes online can stop the database, so `ensure`
 * and `recreate` wait until the index is ONLINE, and a build that fails is an error that names the index.
 */
class Neo4jEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo4j-await-indexes", type = DatabaseType.NEO4J, host = container.host,
                port = container.getMappedPort(7687), user = "neo4j", password = SharedNeo4j.PASSWORD,
                database = null, config = emptyMap(), cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    /** The state SHOW INDEXES gives the index [name]. */
    private fun state(name: String): String =
        manager.rows("SHOW INDEXES YIELD name, state WHERE name = \$name RETURN state", mapOf("name" to name)).single() as String

    @Test
    fun `ensure returns once the range, text, fulltext and vector indexes it made are ONLINE`() {
        manager.seed("AwaitNeo")
        val specs = listOf(
            RangeIndexSpec("AwaitNeo", "k"),
            TextIndexSpec("AwaitNeo", "s"),
            FullTextIndexSpec("AwaitNeo", listOf("s")),
            VectorIndexSpec("AwaitNeo", "e", 4),
        )
        specs.forEach { spec ->
            val created = manager.indexes.ensure(spec)
            assertTrue(created is EnsureResult.Created, "$created")
            assertEquals("ONLINE", state(created.info.name!!), "${spec.kind} right after ensure")
        }
    }

    @Test
    fun `recreate returns once the index it made again is ONLINE`() {
        manager.seed("AwaitNeoAgain")
        val spec = RangeIndexSpec("AwaitNeoAgain", "k")
        manager.indexes.ensure(spec)
        val recreated = manager.indexes.recreate(spec)
        assertEquals("ONLINE", state(recreated.current.name!!))
    }

    @Test
    fun `a constraint's own index is ONLINE once ensure returns`() {
        manager.seed("AwaitNeoUnique")
        val created = manager.constraints.ensure(UniquenessConstraintSpec("AwaitNeoUnique", "k"))
        assertTrue(created is EnsureResult.Created, "$created")
        assertEquals("ONLINE", state(created.info.name!!))
    }

    @Test
    fun `an index whose build fails is an error naming it and giving Neo4j's reason, on ensure and on every ensure after`() {
        // A text too large for a range index, there before the index is: its build fails, and the index stays FAILED.
        manager.run("CREATE (:AwaitNeoFailed {s: \$s})", mapOf("s" to "x".repeat(40_000)))
        val spec = RangeIndexSpec("AwaitNeoFailed", "s")
        try {
            val failed = assertFailsWith<DrivineException> { manager.indexes.ensure(spec) }
            assertContains(failed.message!!, spec.effectiveName)
            assertContains(failed.message!!, "too large", ignoreCase = true)
            assertEquals("FAILED", state(spec.effectiveName))

            // Found again, it is not taken for an index that serves: it is still the error.
            val again = assertFailsWith<DrivineException> { manager.indexes.ensure(spec) }
            assertContains(again.message!!, spec.effectiveName)
        } finally {
            manager.indexes.drop(spec)
        }
    }
}

/**
 * FalkorDB builds an index in the background, `CALL db.indexes()` giving its label UNDER CONSTRUCTION meanwhile,
 * and a vector query answers from the half-built index (FalkorDB/FalkorDB#3382). `ensure` waits until it is
 * OPERATIONAL.
 */
class FalkorDbEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkordb-await-indexes", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = "awaitindexes",
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.FALKORDB, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    /** The status `db.indexes()` gives the indexes of [label]. */
    private fun status(label: String): String =
        manager.rows("CALL db.indexes() YIELD label, status RETURN {label: label, status: status}")
            .map { it as Map<*, *> }.single { it["label"] == label }["status"] as String

    @Test
    fun `ensure returns once the range, fulltext and vector indexes it made are OPERATIONAL`() {
        listOf(
            RangeIndexSpec("AwaitFalkorRange", "k"),
            FullTextIndexSpec("AwaitFalkorText", listOf("s")),
            VectorIndexSpec("AwaitFalkorVector", "e", 4),
        ).forEach { spec ->
            manager.seed(spec.label)
            val created = manager.indexes.ensure(spec)
            assertTrue(created is EnsureResult.Created, "$created")
            assertEquals("OPERATIONAL", status(spec.label), "${spec.kind} right after ensure")
        }
    }

    @Test
    fun `recreate returns once the index it made again is OPERATIONAL`() {
        manager.seed("AwaitFalkorAgain")
        val spec = RangeIndexSpec("AwaitFalkorAgain", "k")
        manager.indexes.ensure(spec)
        manager.indexes.recreate(spec)
        assertEquals("OPERATIONAL", status("AwaitFalkorAgain"))
    }
}

/**
 * Memgraph builds an index inside the `CREATE INDEX` itself: it registers, populates and publishes the index in
 * the one transaction, letting other writes run meanwhile, and plans with an index only once it is published
 * (Memgraph's ADR 004, concurrent index creation). So `ensure` returns an index Memgraph plans with, and waits
 * for nothing more.
 */
class MemgraphEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-await-indexes", type = DatabaseType.MEMGRAPH, host = container.host,
                port = container.getMappedPort(7687), user = "", password = "", database = null,
                config = emptyMap(), cypherDialect = CypherDialect.MEMGRAPH,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.MEMGRAPH, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `the range index ensure made is planned with and holds every node once ensure returns`() {
        manager.seed("AwaitMemgraph")
        val created = manager.indexes.ensure(RangeIndexSpec("AwaitMemgraph", "k"))
        assertTrue(created is EnsureResult.Created, "$created")
        val plan = manager.rows("EXPLAIN MATCH (n:AwaitMemgraph) WHERE n.k = 7 RETURN n").joinToString("\n")
        assertContains(plan, "ScanAllByLabelPropert")
        val info = manager.rows("SHOW INDEX INFO").map { it.toString() }.single { "AwaitMemgraph" in it }
        assertContains(info, "$SEEDED")
    }
}
