package org.drivine.connection

import com.zaxxer.hikari.HikariDataSource
import org.drivine.DrivineException
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The graph's tables, made by a [JdbcConnectionProvider] before its first connection: Cypher through a
 * persistence manager alone, on a store where nothing has made the graph, writes and reads, whatever
 * else comes to the store before or after it. Each store's tests give a store with no graph in it.
 */
abstract class TablesOnFirstConnectionContract {

    /** A provider over a pool of its own, to the store with no graph in it that this test was given. */
    protected abstract fun provider(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider

    /**
     * A provider to the same store, for [graph], as a user who may read the graph's tables and not make
     * a table: the tables are there, made by [provider]'s user, and its pool is ended.
     */
    protected abstract fun readOnly(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider

    /** The store the test was given. */
    protected abstract val type: DatabaseType

    /**
     * A HikariCP pool the application made, to the same store, whose connections commit nothing of themselves
     * (`autoCommit = false`, as `spring.datasource.hikari.auto-commit=false` sets): what a connection leaves
     * uncommitted, the pool rolls back as it takes it back.
     */
    protected abstract fun notCommittingPool(): HikariDataSource

    /** A provider for [graph] over a [notCommittingPool], which the provider ends. */
    protected fun notCommitting(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider =
        JdbcConnectionProvider("fresh", type, notCommittingPool(), ownsDataSource = true, graphName = graph)

    /** The tables in the store whose names start with [graph]'s, in lower case, in order. */
    protected abstract fun tables(graph: String): List<String>

    /** Every provider a test made, ended after it. */
    private val made = mutableListOf<JdbcConnectionProvider>()

    /** Every pool a test made outside a provider, closed after it. */
    private val pools = mutableListOf<HikariDataSource>()

    /** [provider], ended after the test. */
    protected fun open(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider = provider(graph).also { made += it }

    /** Ends the providers a test made, and closes the pools it made outside one. */
    protected fun endAll() {
        made.forEach { runCatching { it.end() } }
        made.clear()
        pools.forEach { runCatching { it.close() } }
        pools.clear()
    }

    @AfterEach
    fun endProviders() = endAll()

    private fun manager(provider: JdbcConnectionProvider) =
        NonTransactionalPersistenceManager(provider, provider.name, provider.type, SubtypeRegistry())

    private fun cypher(text: String, parameters: Map<String, Any?> = emptyMap()) =
        QuerySpecification.withStatement(CypherStatement(text)).bind(parameters)

    private fun names(pm: PersistenceManager): List<String> =
        pm.query(cypher("MATCH (p:Person) RETURN p.name ORDER BY p.name").transform(String::class.java))

    private fun tablesOf(graph: String) = listOf("${graph}_edge", "${graph}_node", "${graph}_schema")

    @Test
    fun `Cypher through a persistence manager alone writes and reads on a store with no graph`() {
        val pm = manager(open())
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})-[:KNOWS {since: 2020}]->(:Person {name: 'Bob'})"))

        assertEquals(listOf("Ada", "Bob"), names(pm))
        assertEquals(listOf<Any>(2020L), pm.query(cypher("MATCH (:Person {name: 'Ada'})-[k:KNOWS]->(:Person {name: 'Bob'}) RETURN k.since")))
        assertEquals(tablesOf("drivine"), tables("drivine"))
    }

    @Test
    fun `a second persistence manager, and an object manager after it, find what the first wrote`() {
        manager(open()).execute(cypher("CREATE (:Person {name: 'Ada'})"))

        val second = manager(open())
        assertEquals(listOf("Ada"), names(second))
        // The object manager's backend makes the tables it finds there, and leaves what they hold.
        StatelessGraphObjectManager(second, Neo4jObjectMapper.instance, SubtypeRegistry())
        second.execute(cypher("CREATE (:Person {name: 'Bob'})"))
        assertEquals(listOf("Ada", "Bob"), names(second))
    }

    @Test
    fun `an object manager made first, and Cypher after it, share the tables`() {
        val pm = manager(open())
        StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
        assertEquals(listOf("Ada"), names(pm))
    }

    @Test
    fun `a provider for a named graph makes that graph's tables, and no others`() {
        val pm = manager(open("tenant_a"))
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))

        assertEquals(listOf("Ada"), names(pm))
        assertEquals(tablesOf("tenant_a"), tables("tenant_a"))
        assertEquals(emptyList(), tables("drivine"))
    }

    @Test
    fun `threads that ask for their first connections at once wait for the tables, and all write`() {
        val pm = manager(open())
        writeAtOnce(List(8) { pm })
        assertEquals(8, names(pm).size)
    }

    @Test
    fun `providers that first connect at once, as processes do, each find the tables made`() {
        val managers = List(4) { manager(open()) }
        writeAtOnce(managers)
        assertEquals(4, names(managers.first()).size)
        assertEquals(tablesOf("drivine"), tables("drivine"))
    }

    /**
     * Two sessions that each find the store missing make it at once from pools of their own: one
     * waits for the other's lock (`ORA-00054` on Oracle, the graph's own lock on Postgres, the file's
     * on SQLite) rather than failing, whether it is a provider or an object manager's backend.
     */
    @Test
    fun `providers and object managers that first connect at once from pools of their own each find the tables made`() {
        val managers = List(6) { manager(open()) }
        writeAtOnce(managers, objectManagers = setOf(0, 2, 4))
        assertEquals(6, names(managers.first()).size)
        assertEquals(tablesOf("drivine"), tables("drivine"))
    }

    /**
     * Whether the store [graph]'s tables are in is whole: on Oracle, the property graph over them, made last, is there
     * too. The tables themselves are checked through [tables].
     */
    protected open fun whole(graph: String): Boolean = true

    private fun merge(pm: PersistenceManager, i: Int) = pm.execute(cypher("MERGE (:Person {name: \$name})", mapOf("name" to "n$i")))

    /**
     * Each pool makes the tables on its first connection while the other pools' first statements, compiled writes, and
     * object managers' own making run: on Oracle, DDL over a table that other sessions' writes hold could deadlock on its
     * library cache lock (`ORA-04020`), and the provider then went on without tables, so every statement over them failed
     * (#450). Three rounds, each a graph of its own and six pools let go at once, each pool ended after its round.
     */
    @Test
    fun `many pools and object managers that first use a graph together make its tables once, round after round`() {
        repeat(3) { round ->
            val graph = "swarm$round"
            try {
                val managers = List(6) { manager(open(graph)) }
                val pool = Executors.newFixedThreadPool(30)
                val start = CountDownLatch(1)
                try {
                    val writes = List(24) { i ->
                        pool.submit {
                            start.await()
                            val pm = managers[i % managers.size]
                            if (writesAtOnce) merge(pm, i) else names(pm)
                        }
                    }
                    val objectManagers = managers.filterIndexed { i, _ -> i % 2 == 0 }.map { pm ->
                        pool.submit {
                            start.await()
                            StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
                        }
                    }
                    start.countDown()
                    (writes + objectManagers).forEach { it.get(3, TimeUnit.MINUTES) }
                } finally {
                    pool.shutdown()
                }
                if (!writesAtOnce) repeat(24) { i -> merge(managers[i % managers.size], i) }
                assertEquals(List(24) { "n$it" }.sorted(), names(managers.first()))
                assertEquals(tablesOf(graph), tables(graph))
                assertTrue(whole(graph), "the store of graph '$graph' is whole")
            } finally {
                // Each round's pools ended before the next's open: the store takes so many sessions at once.
                endAll()
            }
        }
    }

    /** Before 0.4.0 the provider made no tables, and plain SQL worked whatever the graph's name (#364). */
    @Test
    fun `a graph name that cannot name tables leaves plain SQL working, and Cypher is refused, naming the graph and why`() {
        val pm = manager(open("my-graph"))

        assertEquals(1, pm.query(QuerySpecification.withStatement("SELECT 1 AS one")).size)
        val refused = assertFailsWith<DrivineException> { names(pm) }
        assertContains(refused.message.orEmpty(), "'my-graph' cannot name a graph on")
        assertContains(refused.message.orEmpty(), "it names tables")
        assertEquals(1, pm.query(QuerySpecification.withStatement("SELECT 1 AS one")).size)
    }

    /** The tables are made committing each statement, whatever the pool's connections do of themselves (#365). */
    @Test
    fun `on a pool whose connections commit nothing of themselves, the tables are made and stay made`() {
        val provider = notCommitting().also { made += it }
        val pm = manager(provider)

        // The first Cypher, on the provider's first connections.
        assertEquals(emptyList(), names(pm))
        assertEquals(tablesOf("drivine"), tables("drivine"))

        // A connection after them writes in a transaction, and another reads what it wrote.
        provider.connect().apply {
            startTransaction()
            query(cypher("CREATE (:Person {name: 'Ada'})"))
            commitTransaction()
            release()
        }
        assertEquals(listOf("Ada"), names(pm))
    }

    /** Run outside a transaction, a statement commits as it runs, as on a pool whose connections commit each one (#395). */
    @Test
    fun `on a pool whose connections commit nothing of themselves, Cypher and SQL run outside a transaction are committed, and another session sees them`() {
        val pm = manager(notCommitting().also { made += it })
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
        pm.execute(QuerySpecification.withStatement("CREATE TABLE written (n INTEGER)"))
        pm.execute(QuerySpecification.withStatement("INSERT INTO written (n) VALUES (1)"))

        val other = manager(open())
        assertEquals(listOf("Ada"), names(other))
        assertEquals(1, other.query(QuerySpecification.withStatement("SELECT n FROM written")).size)
    }

    @Test
    fun `on a pool whose connections commit nothing of themselves, a Drivine transaction rolled back leaves nothing`() {
        val provider = notCommitting().also { made += it }
        val pm = manager(provider)
        assertEquals(emptyList(), names(pm))

        provider.connect().apply {
            startTransaction()
            query(cypher("CREATE (:Person {name: 'Ada'})"))
            rollbackTransaction()
            release()
        }
        pm.execute(cypher("CREATE (:Person {name: 'Bob'})"))
        assertEquals(listOf("Bob"), names(manager(open())))
    }

    /**
     * Spring's transaction-aware data source hands a Spring transaction's connection to whoever asks. A statement Drivine
     * runs on it is the transaction's: committed with it, and rolled back with it. The connection is not turned to commit
     * each statement, nor are the graph's tables made on it, either of which would commit the transaction partway through.
     */
    @Test
    fun `on a Spring transaction's connection, what the application wrote first is not committed partway, nor are the tables made on it`() {
        val pool = notCommittingPool().also { pools += it }
        pool.connection.use { c ->
            c.createStatement().use { it.execute("CREATE TABLE app_rows (n INTEGER)") }
            c.commit()
        }
        val pm = manager(JdbcConnectionProvider("fresh", type, TransactionAwareDataSourceProxy(pool)).also { made += it })

        TransactionTemplate(DataSourceTransactionManager(pool)).executeWithoutResult { status ->
            JdbcTemplate(pool).update("INSERT INTO app_rows (n) VALUES (1)")
            // The provider's first connection, and a statement on it, in the application's transaction.
            assertEquals(1, pm.query(QuerySpecification.withStatement("SELECT n FROM app_rows")).size)
            status.setRollbackOnly()
        }

        val other = manager(open())
        assertEquals(0, other.query(QuerySpecification.withStatement("SELECT n FROM app_rows")).size)
        // Outside the application's transaction, the next connection makes the tables, and commits each statement.
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
        assertEquals(listOf("Ada"), names(other))
    }

    /**
     * On a new store whose first connection is a Spring transaction's, the tables are not made yet: a Cypher statement
     * in the transaction fails, saying why they are not there and how to have them made.
     */
    @Test
    fun `on a new store, Cypher in a Spring transaction fails saying the tables are not made on its connection, and how to have them made`() {
        val pool = notCommittingPool().also { pools += it }
        val pm = manager(JdbcConnectionProvider("fresh", type, TransactionAwareDataSourceProxy(pool)).also { made += it })

        val failure = assertFailsWith<DrivineException> {
            TransactionTemplate(DataSourceTransactionManager(pool)).executeWithoutResult {
                pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
            }
        }

        assertContains(failure.message!!, "Spring transaction")
        assertContains(failure.message!!, "outside one")
        pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
        assertEquals(listOf("Ada"), names(manager(open())))
    }

    @Test
    fun `on a Spring transaction's connection, a statement run outside a transaction is committed with the transaction, and rolled back with it`() {
        // Another provider makes the tables, before the pool's first connection, so pm's first connection, in the
        // transaction, finds them there.
        val other = manager(open())
        assertEquals(emptyList(), names(other))
        val pool = notCommittingPool().also { pools += it }
        val pm = manager(JdbcConnectionProvider("fresh", type, TransactionAwareDataSourceProxy(pool)).also { made += it })
        val transactions = TransactionTemplate(DataSourceTransactionManager(pool))

        transactions.executeWithoutResult {
            pm.execute(cypher("CREATE (:Person {name: 'Ada'})"))
            assertEquals(listOf("Ada"), names(pm))
            assertEquals(emptyList(), names(other), "not committed before the transaction is")
        }
        assertEquals(listOf("Ada"), names(other))

        transactions.executeWithoutResult { status ->
            pm.execute(cypher("CREATE (:Person {name: 'Bob'})"))
            status.setRollbackOnly()
        }
        assertEquals(listOf("Ada"), names(other))

        // Outside one, the proxy hands out a connection of the pool's own, which commits each statement.
        pm.execute(cypher("CREATE (:Person {name: 'Cy'})"))
        assertEquals(listOf("Ada", "Cy"), names(other))
    }

    @Test
    fun `a user who may read the tables and not make them reads the graph`() {
        manager(open()).execute(cypher("CREATE (:Person {name: 'Ada'})"))
        endAll()

        val reader = manager(readOnly().also { made += it })
        assertEquals(listOf("Ada"), names(reader))
        // An object manager's backend looks for the tables as the provider did, and makes nothing.
        StatelessGraphObjectManager(reader, Neo4jObjectMapper.instance, SubtypeRegistry())
        assertEquals(listOf("Ada"), names(reader))
    }

    /**
     * Whether Cypher writes through a persistence manager, each committed as it runs, may run at once.
     * Not on SQLite: there a write is a `SELECT` of a function, whose read is turned into a write only as
     * it runs, and one that finds another writer at work is refused rather than made to wait.
     */
    protected open val writesAtOnce: Boolean = true

    private fun write(i: Int, pm: PersistenceManager) = pm.execute(cypher("CREATE (:Person {name: \$name})", mapOf("name" to "p$i")))

    /**
     * Each of [managers] writes a person, their first statements let go at once: the writes themselves,
     * or where they cannot run at once ([writesAtOnce]), a read, with the writes after it one at a time.
     * Those at [objectManagers] make an object manager first instead, whose backend makes the tables
     * from its own side, at once with the others.
     */
    private fun writeAtOnce(managers: List<PersistenceManager>, objectManagers: Set<Int> = emptySet()) {
        val pool = Executors.newFixedThreadPool(managers.size)
        val start = CountDownLatch(1)
        try {
            val first = managers.mapIndexed { i, pm ->
                pool.submit {
                    start.await()
                    when {
                        i in objectManagers -> StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())
                        writesAtOnce -> write(i, pm)
                        else -> names(pm)
                    }
                }
            }
            start.countDown()
            first.forEach { it.get(3, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        managers.forEachIndexed { i, pm -> if (i in objectManagers || !writesAtOnce) write(i, pm) }
    }
}
