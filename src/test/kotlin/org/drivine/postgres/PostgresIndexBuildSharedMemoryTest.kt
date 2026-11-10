package org.drivine.postgres

import org.drivine.connection.Connection
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.EnsureResult
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A Postgres index build leaves no pooled session holding shared memory (#626).
 *
 * A backend that has run anything in parallel, as a `CREATE INDEX` over a node table past `min_parallel_table_scan_size`
 * (8 MB) does, keeps a dynamic shared memory segment, a `/dev/shm/PostgreSQL.<n>` file, for as long as it lives, and a
 * pool keeps it alive. An HNSW build over such a table asks for nearly all of `maintenance_work_mem` (64 MB) as one
 * segment, so in the test container's 64 MB `/dev/shm`, Docker's default, it failed once pooled sessions held theirs.
 *
 * The graph's node table is filled past 8 MB once, with rows small enough to stay out of TOAST, where a row's size
 * would not count toward the table's. A session holds a segment where `/proc/<pid>/maps` of its backend maps one that
 * Postgres's own are not, which every backend maps: the container's user is a superuser, which may read it with
 * `pg_read_file`. The sessions are watched from a connection of no pool, which runs nothing in parallel. A local
 * instance whose segments are not files in `/dev/shm`, or whose user may not read them or `/proc`, skips the tests.
 */
@PostgresTests
class PostgresIndexBuildSharedMemoryTest {

    /**
     * The segments of its own that each backend of the pool's sessions maps, by its pid: those it maps that were not in
     * `/dev/shm` before the tests, which every backend maps, as Postgres's own are.
     */
    private fun pooledSessions(): Map<Int, Set<String>> = watch(
        "SELECT pid, ${mapped("pid")} FROM pg_stat_activity WHERE application_name = '$POOL'",
    ) { it.getInt(1) to own(it.getString(2)) }.toMap()

    /** The segments of its own that the backend [pid] maps (see [pooledSessions]). */
    private fun segmentsOf(pid: Int): Set<String> = watch("SELECT ${mapped("$pid")}") { own(it.getString(1)) }.single()

    /** The dynamic shared memory segments in `/dev/shm`, by name, with their sizes. */
    private fun segments(): Map<String, Long> = watch(
        "SELECT f, (pg_stat_file('/dev/shm/' || f, true)).size FROM pg_ls_dir('/dev/shm') f WHERE f LIKE 'PostgreSQL.%'",
    ) { it.getString(1) to it.getLong(2) }.toMap()

    /**
     * Waits, up to ten seconds, for [done]: a session closed out of its pool goes on a thread of the pool's, and its
     * backend ends after it. Fails with [state] where it is not done by then.
     */
    private fun eventually(state: () -> String, done: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            if (System.nanoTime() > until) fail(state())
            Thread.sleep(100)
        }
    }

    /**
     * Each session that builds a range index in parallel keeps a segment of about 192 KB, the area its parallel
     * queries share, as long as it lives. The HNSW build needs 63,999,616 bytes and Postgres's own segments about 1 MB,
     * which leaves room in 64 MB for ten: twelve sessions that each built one, kept by their pool, were enough that it
     * failed, `could not resize shared memory segment ... No space left on device`.
     */
    @Test
    fun `an HNSW build after range index builds in parallel passes, and no session of the pool holds shared memory after`() {
        val before = segments()
        // A connection held out of the pool while each range index builds, as an application's other threads hold
        // theirs, so each build is on a session of its own: the pool hands out the session given back last.
        val held = mutableListOf<Connection>()
        try {
            RANGE_PROPERTIES.forEach { property ->
                held += provider.connect()
                assertTrue(pm.indexes.ensure(RangeIndexSpec(LABEL, property)) is EnsureResult.Created)
            }
        } finally {
            held.forEach { it.release() }
        }

        // The largest segment seen while the HNSW index builds: one past 32 MB is the build's own, so it ran in parallel.
        val largest = AtomicLong()
        val building = AtomicBoolean(true)
        val watching = thread {
            DriverManager.getConnection(PostgresTestContainer.getJdbcUrl(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword()).use { c ->
                c.prepareStatement("SELECT max((pg_stat_file('/dev/shm/' || f, true)).size) FROM pg_ls_dir('/dev/shm') f WHERE f LIKE 'PostgreSQL.%'").use { st ->
                    while (building.get()) st.executeQuery().use { rs -> rs.next(); largest.accumulateAndGet(rs.getLong(1), ::maxOf) }
                }
            }
        }
        val vector = VectorIndexSpec(LABEL, "embedding", DIMENSIONS)
        try {
            assertTrue(pm.indexes.ensure(vector) is EnsureResult.Created)
        } finally {
            building.set(false)
            watching.join()
        }
        assertNotNull(pm.indexes.find(vector))
        assertTrue(largest.get() > 32L * 1024 * 1024, "the HNSW build ran with no parallel workers: its largest segment was ${largest.get()} bytes")

        eventually({ "pooled sessions holding a segment: ${pooledSessions().filterValues { it.isNotEmpty() }}; segments ${segments()}, before $before" }) {
            pooledSessions().values.all { it.isEmpty() }
        }
        eventually({ "segments ${segments()}, before $before" }) { (segments().keys - before.keys).isEmpty() }
    }

    /**
     * A data source other than HikariCP's has no way to close a session for good, so the build runs with no parallel
     * workers instead, and the session's own setting, whatever the application set it to, is put back after.
     */
    @Test
    fun `a build on a pool that cannot close a session runs with no parallel workers, and leaves the session as it was`() {
        val single = SingleConnectionDataSource(URL, PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(), true)
        try {
            // The data source keeps one session open across connections, as a pool does, and has no way to close it for good.
            val session = single.connection
            session.createStatement().use { it.execute("SET max_parallel_maintenance_workers = 3") }
            val pid = pid(session)
            val manager = NonTransactionalPersistenceManager(
                JdbcConnectionProvider("single", DatabaseType.POSTGRES, single, graphName = GRAPH), "single", DatabaseType.POSTGRES, SubtypeRegistry(),
            )

            assertTrue(manager.indexes.ensure(RangeIndexSpec(LABEL, "d")) is EnsureResult.Created)

            assertFalse(single.connection.isClosed)
            assertEquals(pid, pid(single.connection), "the same session")
            assertEquals(emptySet(), segmentsOf(pid), "the build ran in parallel")
            assertEquals("3", setting(single.connection))
        } finally {
            single.destroy()
        }
    }

    /**
     * Closing a Spring transaction's session would end the transaction partway through, and lose what it had written,
     * so the build runs on it with no parallel workers instead, and commits with the rest of the transaction.
     */
    @Test
    fun `a build in a caller's Spring transaction runs on its connection, which is neither closed nor evicted, and commits with it`() {
        val pool = provider.nativeDriver
        val manager = NonTransactionalPersistenceManager(
            JdbcConnectionProvider("spring", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(pool), graphName = GRAPH), "spring", DatabaseType.POSTGRES, SubtypeRegistry(),
        )
        val spec = RangeIndexSpec(LABEL, "e")
        val indexes = indexCount()

        TransactionTemplate(DataSourceTransactionManager(pool)).executeWithoutResult {
            val session = DataSourceUtils.getConnection(pool)
            val pid = pid(session)
            val workers = setting(session)

            assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)

            assertFalse(session.isClosed)
            assertEquals(pid, pid(DataSourceUtils.getConnection(pool)), "the transaction's session")
            assertEquals(workers, setting(session), "the setting is put back")
            assertEquals(emptySet(), segmentsOf(pid), "the build ran in parallel")
        }

        assertNotNull(pm.indexes.find(spec))
        assertEquals(indexes + 1, indexCount(), "the index is there after the commit")
    }

    private fun pid(session: java.sql.Connection): Int =
        session.createStatement().use { st -> st.executeQuery("SELECT pg_backend_pid()").use { it.next(); it.getInt(1) } }

    private fun setting(session: java.sql.Connection): String =
        session.createStatement().use { st -> st.executeQuery("SELECT current_setting('max_parallel_maintenance_workers')").use { it.next(); it.getString(1) } }

    /** The indexes over the graph's node table, as Postgres has them. */
    private fun indexCount(): Int = watch("SELECT count(*) FROM pg_indexes WHERE tablename = '${GRAPH}_node'") { it.getInt(1) }.single()

    companion object {
        private const val GRAPH = "shm626"
        private const val LABEL = "Chunk"
        private const val DIMENSIONS = 32

        /** The properties the HNSW test builds a range index over, one session each. */
        private val RANGE_PROPERTIES = listOf("a", "b", "c", "f", "g", "h", "i", "j", "k", "l", "m", "n")

        /** The application name of the pool's sessions, by which [pooledSessions] finds them. */
        private const val POOL = "drivine-626"

        private val URL = "${PostgresTestContainer.getJdbcUrl()}?stringtype=unspecified"

        private lateinit var provider: JdbcConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var watcher: java.sql.Connection

        /** The segments in `/dev/shm` before the tests: Postgres's own, which every backend maps. */
        private lateinit var common: Set<String>

        /** SQL for the names of the segments that the backend whose pid is [pid], an SQL expression, maps, joined by commas. */
        private fun mapped(pid: String) =
            "array_to_string(ARRAY(SELECT DISTINCT m[1] FROM regexp_matches(pg_read_file('/proc/' || $pid || '/maps'), 'PostgreSQL\\.[0-9]+', 'g') m), ',')"

        /** The segments named in [mapped], as [mapped] gives them, that are not Postgres's own. */
        private fun own(mapped: String): Set<String> = mapped.split(',').filter { it.isNotEmpty() }.toSet() - common

        /** Runs [sql] on the watching connection, and reads each of its rows with [row]. */
        private fun <T> watch(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
            watcher.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }

        /** The names of the segments in `/dev/shm` now. */
        private fun segmentsNow(): Set<String> =
            watch("SELECT f FROM pg_ls_dir('/dev/shm') f WHERE f LIKE 'PostgreSQL.%'") { it.getString(1) }.toSet()

        private fun watching(sql: String) = watcher.createStatement().use { it.execute(sql) }

        @JvmStatic
        @BeforeAll
        fun fill() {
            watcher = DriverManager.getConnection(PostgresTestContainer.getJdbcUrl(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword())
            val readable = runCatching {
                watch("SELECT current_setting('dynamic_shared_memory_type') = 'posix' AND ${mapped("pg_backend_pid()")} IS NOT NULL") { it.getBoolean(1) }.single() &&
                    segmentsNow().isNotEmpty()
            }.getOrDefault(false)
            assumeTrue(readable, "the server keeps no segments in /dev/shm, or its user may not read them and /proc/<pid>/maps, as a superuser on Linux may")
            watching("DROP TABLE IF EXISTS ${GRAPH}_edge, ${GRAPH}_node, ${GRAPH}_schema CASCADE")
            common = segmentsNow()
            provider = JdbcConnectionProvider.pooled(
                GRAPH, DatabaseType.POSTGRES, "${PostgresTestContainer.getJdbcUrl()}?ApplicationName=$POOL",
                PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(), poolMax = 16, graphName = GRAPH,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.POSTGRES, SubtypeRegistry())
            provider.connect().release() // makes the graph's tables
            // Some 700 bytes a row, under the 2 KB past which a row's props would go to TOAST: 20,000 of them are past 10 MB.
            watching(
                """
                INSERT INTO ${GRAPH}_node (labels, props)
                SELECT '["$LABEL"]'::jsonb, jsonb_build_object(
                    'a', i, 'b', 'b' || i, 'c', i % 100, 'd', i % 7, 'e', 'e' || i, 'f', i % 3, 'g', i % 5, 'h', i % 11, 'i', i % 13,
                    'j', i % 17, 'k', i % 19, 'l', i % 23, 'm', i % 29, 'n', i % 31, 'pad', repeat('p', 100),
                    'embedding', (SELECT jsonb_agg(round(random()::numeric, 6)) FROM generate_series(1, $DIMENSIONS) WHERE i > 0))
                FROM generate_series(1, 20000) i
                """.trimIndent(),
            )
            val size = watch("SELECT pg_relation_size('${GRAPH}_node')") { it.getLong(1) }.single()
            check(size > 10L * 1024 * 1024) { "the node table is $size bytes: a build over it would not be parallel" }
        }

        @JvmStatic
        @AfterAll
        fun drop() {
            runCatching { provider.end() }
            runCatching { watching("DROP TABLE IF EXISTS ${GRAPH}_edge, ${GRAPH}_node, ${GRAPH}_schema CASCADE") }
            runCatching { watcher.close() }
        }
    }
}
