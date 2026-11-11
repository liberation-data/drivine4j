package org.drivine.schema

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.transaction.TransactionContextHolder
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.store.StoreIdentityResolver
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SchemaLock] on Oracle (liberation-data/drivine4j#777): managers making one graph's schema at once take turns, so none
 * waits out the library-cache deadlock their DDL can meet together; a lock held past its wait is gone without, in either
 * schema mode, so the DDL runs unlocked with one warning that names the graph, as it does on a pool of one connection; and a
 * graph with no lock table runs its DDL unlocked.
 */
@OracleTests
class SchemaLockTest {

    private fun open(graph: String): PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager(graph))

    private fun connect() = DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword())

    @Test
    fun `eight managers making a graph's schema at once take turns, so no round waits out a deadlock`() {
        val graph = "lock_race"
        val slowest = (1..20).map {
            open(graph).constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
            OracleTestDatabase.clear(OracleTestDatabase.graphManager(graph))
            val managers = List(8) { open(graph) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val t0 = System.nanoTime()
                val ids = managers.map { manager -> pool.submit<String> { start.await(); manager.storeIdentity.id } }
                    .also { start.countDown() }
                    .map { it.get(60, TimeUnit.SECONDS) }
                assertEquals(1, ids.toSet().size, "the managers agree on one identity")
                (System.nanoTime() - t0) / 1_000_000
            } finally {
                pool.shutdownNow()
            }
        }
        // Unlocked, about one round in twenty-five waited out a deadlock of about 9 s: each such wait is 9 s or more.
        assertTrue(slowest.all { it < 5_000 }, "each round's slowest manager, ms: $slowest")
    }

    @Test
    fun `a graph with no lock table, as one made before layout 2, makes its schema unlocked`() {
        val graph = "lock_none"
        open(graph).constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
        connect().use { c -> c.createStatement().use { it.execute("DROP TABLE ${graph}_lock2") } }
        try {
            OracleTestDatabase.clear(OracleTestDatabase.graphManager(graph))
            open(graph).storeIdentity.id
        } finally {
            // Made again with the graph, for the next run.
            OracleTestDatabase.graphManager(graph)
        }
    }

    /** The warnings [SchemaLock] logs while [block] runs. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(SchemaLock::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
    }

    /**
     * While another session holds [graph]'s schema lock, the [SchemaManager] of a uniqueness constraint on it, under
     * [mode], makes the constraint unlocked once the wait is out, and warns of it once for the graph however many plans
     * meet it.
     */
    private fun madeUnlockedWhileHeld(graph: String, mode: SchemaMode) {
        OracleTestDatabase.graphManager(graph)
        val registry = DatabaseRegistry(DataSourceMap()).also {
            it.register(JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph))
        }
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
        val pm = factory.get(graph, PersistenceManagerType.NON_TRANSACTIONAL)
        val spec = UniquenessConstraintSpec("Ledger", "id")
        val other = RangeIndexSpec("Ledger", "name")
        pm.constraints.drop(spec)
        pm.indexes.drop(other)
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("SELECT name FROM ${graph}_lock2 WHERE name = 'schema' FOR UPDATE").close() }
            try {
                val started = System.nanoTime()
                val logged = warnings {
                    SchemaManager(factory, registry, listOf(SchemaCatalog.of(spec)), SchemaPolicy(mode = mode)).enforce()
                    SchemaManager(factory, registry, listOf(SchemaCatalog.of(other)), SchemaPolicy(mode = mode)).enforce()
                }
                assertTrue((System.nanoTime() - started) / 1_000_000 >= SchemaLock.WAIT_SECONDS * 1_000L - 500, "it waited for the lock first")
                kotlin.test.assertNotNull(pm.constraints.find(spec), "the constraint is made, unlocked, not skipped")
                kotlin.test.assertNotNull(pm.indexes.find(other), "and so is the index after it")
                val lockWarnings = logged.filter { "schema lock of graph $graph" in it }
                assertEquals(1, lockWarnings.size, "warned of once for the graph: $logged")
                assertTrue(Regex("in \\d+ ms").containsMatchIn(lockWarnings.single()), lockWarnings.single())
            } finally {
                holder.rollback()
            }
        }
        registry.databaseNames.forEach { registry.connectionProvider(it)?.end() }
    }

    @Test
    fun `under FAIL_FAST a lock not had is gone without after its wait, and the constraint is made unlocked`() =
        madeUnlockedWhileHeld("lock_fail", SchemaMode.FAIL_FAST)

    @Test
    fun `under WARN a lock not had is gone without after its wait, and the constraint is made unlocked`() =
        madeUnlockedWhileHeld("lock_warn", SchemaMode.WARN)

    /**
     * More of one pool's managers making a graph's schema at once than the pool has connections: ungated, each waiting
     * for the row would hold a connection while it waited, and the one holding it would have none for its DDL until the
     * others' waits ran out, ten seconds on. Gated, one at a time takes the lock, with a connection to spare.
     */
    @Test
    fun `more managers of one pool making a graph's schema at once than the pool has connections take turns through its gate`() {
        val graph = "lock_pool"
        OracleTestDatabase.graphManager(graph)
        val provider = JdbcConnectionProvider.pooled(
            graph, DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(),
            OracleTestContainer.getConnectionPassword(), poolMax = 2, graphName = graph,
        )
        val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
        val pm = factory.get(graph, PersistenceManagerType.NON_TRANSACTIONAL)
        val spec = UniquenessConstraintSpec("Ledger", "id")
        val pool = Executors.newFixedThreadPool(6)
        try {
            pm.constraints.drop(spec)
            val start = CountDownLatch(1)
            val logged = warnings {
                val t0 = System.nanoTime()
                List(6) {
                    pool.submit {
                        start.await()
                        SchemaManager(factory, registry, listOf(SchemaCatalog.of(spec)), SchemaPolicy(mode = SchemaMode.FAIL_FAST)).enforce()
                    }
                }.also { start.countDown() }.forEach { it.get(60, TimeUnit.SECONDS) }
                val took = (System.nanoTime() - t0) / 1_000_000
                assertTrue(took < SchemaLock.WAIT_SECONDS * 1_000L - 500, "no manager waited out another's lock: $took ms")
            }
            kotlin.test.assertNotNull(pm.constraints.find(spec), "the constraint is made")
            assertEquals(emptyList(), logged.filter { "schema lock of graph $graph" in it }, "every manager had the lock")
        } finally {
            pool.shutdownNow()
            provider.end()
        }
    }

    /**
     * A pool of one connection, which a schema command's DDL needs: the lock would hold it, and the DDL wait out the
     * pool's timeout for another, so no lock is taken, and the schema is made unlocked, with one warning for the graph.
     * Both a manager's plan, whose statements each borrow the connection, and a Cypher schema command, which holds it for
     * its length, make what they're asked for.
     */
    @Test
    fun `a pool of one connection makes the schema unlocked, with one warning`() {
        val graph = "lock_one"
        OracleTestDatabase.graphManager(graph)
        val provider = JdbcConnectionProvider.pooled(
            graph, DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(),
            OracleTestContainer.getConnectionPassword(), poolMax = 1, graphName = graph,
        )
        val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
        val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
        val pm = factory.get(graph, PersistenceManagerType.NON_TRANSACTIONAL)
        val spec = UniquenessConstraintSpec("Ledger", "id")
        val other = RangeIndexSpec("Ledger", "name")
        try {
            pm.constraints.drop(spec)
            pm.indexes.drop(other)
            val started = System.nanoTime()
            val logged = warnings {
                SchemaManager(factory, registry, listOf(SchemaCatalog.of(spec)), SchemaPolicy(mode = SchemaMode.FAIL_FAST)).enforce()
                pm.execute(org.drivine.query.QuerySpecification.withStatement(org.drivine.query.CypherStatement("CREATE INDEX ledger_name IF NOT EXISTS FOR (l:Ledger) ON (l.name)")))
            }
            val took = (System.nanoTime() - started) / 1_000_000
            assertTrue(took < SchemaLock.WAIT_SECONDS * 1_000L, "nothing waited out the pool's timeout: $took ms")
            kotlin.test.assertNotNull(pm.constraints.find(spec), "the constraint is made, unlocked")
            kotlin.test.assertNotNull(pm.indexes.find(other), "and so is the index of the Cypher command")
            val lockWarnings = logged.filter { "schema lock of graph $graph" in it }
            assertEquals(1, lockWarnings.size, "warned of once for the graph: $logged")
            assertTrue("pool holds 1 connection" in lockWarnings.single(), lockWarnings.single())
        } finally {
            pm.indexes.drop(other)
            provider.end()
        }
    }

    @Test
    fun `a lock not had within its wait is none, and the DDL runs unlocked`() {
        val graph = "lock_held"
        OracleTestDatabase.graphManager(graph)
        val provider = JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph)
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("SELECT name FROM ${graph}_lock2 WHERE name = 'schema' FOR UPDATE").close() }
            try {
                assertEquals(null, SchemaLock.take(provider))
            } finally {
                holder.rollback()
            }
        }
        // Freed, the lock is had at once.
        SchemaLock.take(provider)!!.close()
        provider.end()
    }
}
