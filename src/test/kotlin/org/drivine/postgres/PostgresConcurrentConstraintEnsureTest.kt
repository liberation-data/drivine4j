package org.drivine.postgres

import org.drivine.DrivineException
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.schema.EnsureResult
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A uniqueness constraint's index built online on Postgres (drivine-cypher-spec#382), and two ensures of it at once,
 * each from its own manager and connection pool, as two processes would run them. Whatever each reports, a constraint
 * reported made is enforced, and none is reported made where nothing enforces it.
 */
@PostgresTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConcurrentConstraintEnsureTest {

    private val spec = UniquenessConstraintSpec("Big", "id")

    /**
     * A pool of the class's own, ended after its tests, which every statement over the big tables runs on: a session that
     * ran anything in parallel over them (a sample of the keys that repeat, the build the invalid-index test has refused)
     * keeps a segment of /dev/shm until it disconnects, which in PostgresTestDatabase's pool would last the test JVM and
     * leave a later HNSW build in the container's 64 MB too little (PostgresIndexBuildSharedMemoryTest).
     */
    private lateinit var provider: JdbcConnectionProvider
    private lateinit var own: NonTransactionalPersistenceManager

    @BeforeAll
    fun open() {
        provider = JdbcConnectionProvider.pooled(
            "cc382", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
            PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
        )
        own = NonTransactionalPersistenceManager(provider, "cc382", DatabaseType.POSTGRES, SubtypeRegistry())
    }

    @AfterAll
    fun end() {
        provider.end()
    }

    /** Runs each statement on its own, committed as it runs, on the class's own pool. */
    private fun run(vararg statements: String) = statements.forEach { own.execute(QuerySpecification.withStatement(it)) }

    /** A manager for the graph [graph], with its tables made, as [PostgresTestDatabase.graphManager] makes one, over the class's own pool. */
    private fun manager(graph: String): NonTransactionalPersistenceManager = SubtypeRegistry().let { registry ->
        NonTransactionalPersistenceManager(
            JdbcConnectionProvider(graph, DatabaseType.POSTGRES, provider.nativeDriver, registry, graphName = graph), graph, DatabaseType.POSTGRES, registry,
        ).also { PostgresGraph(graph).ensure(it) }
    }

    /** A fresh graph of [nodes] nodes labelled Big, each with its own id, and [repeats] more repeating ids 1 to [repeats]. */
    private fun seeded(graph: String, nodes: Int = NODES, repeats: Int = 0): NonTransactionalPersistenceManager {
        PostgresTestDatabase.drop(graph)
        val pm = manager(graph)
        run(
            "INSERT INTO ${graph}_node (labels, props) SELECT '[\"Big\"]'::jsonb, jsonb_build_object('id', g) FROM generate_series(1, $nodes) g",
        )
        if (repeats > 0) {
            run(
                "INSERT INTO ${graph}_node (labels, props) SELECT '[\"Big\"]'::jsonb, jsonb_build_object('id', g) FROM generate_series(1, $repeats) g",
            )
        }
        // Without parallel workers: a pooled session that ran a vacuum in parallel keeps a segment of /dev/shm until it
        // disconnects, which leaves a later HNSW build in the container's 64 MB too little (PostgresIndexBuildSharedMemoryTest).
        run("VACUUM (PARALLEL 0, ANALYZE) ${graph}_edge", "VACUUM (PARALLEL 0, ANALYZE) ${graph}_node")
        return pm
    }

    /** Each unique index Drivine names a constraint's on the graph's node table (not its key), by name, and whether it is valid. */
    private fun uniqueIndexes(graph: String): Map<String, Boolean> = PostgresTestDatabase.pm.query(
        QuerySpecification.withStatement(
            sqlStatement(
                "SELECT c.relname || '=' || i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                    "WHERE i.indrelid = '${graph}_node'::regclass AND i.indisunique AND c.relname LIKE '${graph}\\_uq%'",
            ),
        ).transform(String::class.java),
    ).associate { it.substringBefore('=') to (it.substringAfter('=') == "true") }

    /** Whether a second node of id 1 is refused. */
    private fun enforced(pm: NonTransactionalPersistenceManager): Boolean = try {
        PostgresTestDatabase.fixtures(pm).execute(QuerySpecification.withStatement(cypherStatement("CREATE (:Big {id: 1})")))
        false
    } catch (refused: DrivineException) {
        true
    }

    /** Two ensures of [spec] at once, each of its own manager over [graph], started together; what each gave, or threw. */
    private fun together(graph: String): List<Any> {
        val managers = List(2) { manager(graph) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = managers.map { manager ->
                pool.submit<Any> {
                    barrier.await()
                    try {
                        manager.constraints.ensure(spec)
                    } catch (failed: Exception) {
                        failed
                    }
                }
            }
            return runs.map { it.get(5, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** What enforces nothing is never reported made: each result is made, matching, a violation, or a failure. */
    private fun reportsMade(result: Any) = result is EnsureResult.Created || result is EnsureResult.AlreadyMatching

    @Test
    fun `a write to the label commits while the constraint's index is being built, and the constraint then enforces`() {
        val graph = "cc382_online"
        // As many nodes as the range index's test, so the build outlasts the first write's compile.
        val pm = seeded(graph, nodes = 1_000_000)
        val building = {
            PostgresTestDatabase.pm.query(
                QuerySpecification.withStatement(sqlStatement("SELECT count(*) FROM pg_stat_progress_create_index WHERE relid = '${graph}_node'::regclass"))
                    .transform(Long::class.java),
            ).single()
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val ensure = pool.submit<EnsureResult> { pm.constraints.ensure(spec) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (building() == 0L) {
                check(!ensure.isDone) { "the build ended before it was seen under way: ${ensure.get()}" }
                check(System.nanoTime() < deadline) { "no build was seen under way" }
                Thread.sleep(5)
            }
            PostgresTestDatabase.fixtures(pm).execute(QuerySpecification.withStatement(cypherStatement("CREATE (:Big {id: -1})")))
            val stillBuilding = building() > 0
            assertIs<EnsureResult.Created>(ensure.get(5, TimeUnit.MINUTES))
            assertTrue(stillBuilding, "the write committed only once the build had ended")
            assertEquals(listOf(true), uniqueIndexes(graph).values.toList())
            assertTrue(enforced(pm))
        } finally {
            pool.shutdownNow()
            PostgresTestDatabase.drop(graph)
        }
    }

    @Test
    fun `two ensures at once over data that holds each id once both end with one valid index that enforces`() {
        repeat(ROUNDS) { round ->
            val graph = "cc382_both$round"
            val pm = seeded(graph)
            try {
                val results = together(graph)
                assertTrue(results.all(::reportsMade), "round $round: $results")
                assertTrue(results.any { it is EnsureResult.Created }, "round $round: $results")
                assertEquals(listOf(true), uniqueIndexes(graph).values.toList(), "round $round: ${uniqueIndexes(graph)}")
                assertTrue(enforced(pm), "round $round")
            } finally {
                PostgresTestDatabase.drop(graph)
            }
        }
    }

    @Test
    fun `two ensures at once over data that repeats an id neither report it made, and leave no index`() {
        repeat(ROUNDS) { round ->
            val graph = "cc382_dup$round"
            val pm = seeded(graph, repeats = 3)
            try {
                val results = together(graph)
                assertTrue(results.none(::reportsMade), "round $round: $results")
                assertTrue(results.any { it is EnsureResult.Violation }, "round $round: $results")
                assertEquals(emptyMap(), uniqueIndexes(graph), "round $round")
                assertTrue(!enforced(pm), "round $round: a second id 1 was refused, with no constraint made")
            } finally {
                PostgresTestDatabase.drop(graph)
            }
        }
    }

    @Test
    fun `two ensures at once over an index left invalid both end with one valid index that enforces`() {
        // The window #382 closes: an earlier build of the constraint's index was refused, which left it invalid under its
        // name, recorded; the data is then put right, and two processes ensure the constraint at once.
        repeat(ROUNDS) { round ->
            val graph = "cc382_invalid$round"
            val pm = seeded(graph)
            try {
                assertIs<EnsureResult.Created>(pm.constraints.ensure(spec))
                val (name, valid) = uniqueIndexes(graph).entries.single()
                assertTrue(valid)
                val definition = PostgresTestDatabase.pm.query(
                    QuerySpecification.withStatement(sqlStatement("SELECT pg_get_indexdef('$name'::regclass)")).transform(String::class.java),
                ).single()
                run("DROP INDEX $name")
                run("INSERT INTO ${graph}_node (labels, props) VALUES ('[\"Big\"]'::jsonb, '{\"id\": 7}'::jsonb)")
                assertFailsWith<DrivineException> {
                    run(definition.replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX CONCURRENTLY "))
                }
                assertEquals(mapOf(name to false), uniqueIndexes(graph), "the refused build left it invalid")
                run("DELETE FROM ${graph}_node WHERE nid = (SELECT max(nid) FROM ${graph}_node)")

                val results = together(graph)
                assertTrue(results.all(::reportsMade), "round $round: $results")
                assertEquals(mapOf(name to true), uniqueIndexes(graph), "round $round")
                assertTrue(enforced(pm), "round $round")
            } finally {
                PostgresTestDatabase.drop(graph)
            }
        }
    }

    @Test
    fun `a claim of the turn older than 60 seconds, left by a session that ended, is taken over at once`() {
        // A session that claimed its turn to build the constraint and ended without giving it up (drivine-cypher-spec#382):
        // with no online build under way, the next ensure takes the claim over, rather than wait 60 seconds for it.
        val graph = "cc382_stale"
        val pm = seeded(graph, nodes = 1_000)
        try {
            run(
                "INSERT INTO ${graph}_schema (kind, label, properties, details) VALUES ('CLAIM:UNIQUENESS_CONSTRAINT', 'Big', '[\"id\"]', " +
                    "jsonb_build_object('at', (clock_timestamp() - interval '2 minutes')::text))",
            )
            val started = System.nanoTime()
            assertIs<EnsureResult.Created>(pm.constraints.ensure(spec))
            val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            assertTrue(seconds < 30, "the stale claim was waited out, not taken over: $seconds s")
            assertEquals(listOf(true), uniqueIndexes(graph).values.toList())
            assertTrue(enforced(pm))
            val claims = PostgresTestDatabase.pm.query(
                QuerySpecification.withStatement(sqlStatement("SELECT count(*) FROM ${graph}_schema WHERE kind LIKE 'CLAIM:%'")).transform(Long::class.java),
            ).single()
            assertEquals(0L, claims, "the claim taken over was given up once done")
        } finally {
            PostgresTestDatabase.drop(graph)
        }
    }

    @Test
    fun `two ensures at once of different items on one table both make theirs`() {
        // A constraint and a range index on the same node table, built online at once: Postgres cancels one as a deadlock,
        // which waits for the other's build to end, and is made then.
        repeat(ROUNDS) { round ->
            val graph = "cc382_apart$round"
            val pm = seeded(graph)
            try {
                run("UPDATE ${graph}_node SET props = props || jsonb_build_object('n', props -> 'id')")
                val managers = List(2) { manager(graph) }
                val barrier = CyclicBarrier(2)
                val pool = Executors.newFixedThreadPool(2)
                val results = try {
                    listOf(
                        pool.submit<Any> { barrier.await(); runCatching { managers[0].constraints.ensure(spec) }.getOrElse { it } },
                        pool.submit<Any> { barrier.await(); runCatching { managers[1].indexes.ensure(org.drivine.schema.RangeIndexSpec("Big", "n")) }.getOrElse { it } },
                    ).map { it.get(5, TimeUnit.MINUTES) }
                } finally {
                    pool.shutdownNow()
                }
                assertTrue(results.all { it is EnsureResult.Created }, "round $round: $results")
                assertEquals(listOf(true), uniqueIndexes(graph).values.toList(), "round $round")
                assertTrue(enforced(pm), "round $round")
            } finally {
                PostgresTestDatabase.drop(graph)
            }
        }
    }

    private companion object {
        /** Enough nodes that a build takes about a second, so two ensures overlap. */
        const val NODES = 300_000
        const val ROUNDS = 3
    }
}
