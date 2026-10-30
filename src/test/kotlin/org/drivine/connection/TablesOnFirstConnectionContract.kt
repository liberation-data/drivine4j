package org.drivine.connection

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

    /**
     * A provider to the same store, for [graph], over a HikariCP pool the application made whose connections commit
     * nothing of themselves (`autoCommit = false`, as `spring.datasource.hikari.auto-commit=false` sets): what a
     * connection leaves uncommitted, the pool rolls back as it takes it back. The provider ends the pool.
     */
    protected abstract fun notCommitting(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider

    /** The tables in the store whose names start with [graph]'s, in lower case, in order. */
    protected abstract fun tables(graph: String): List<String>

    /** Every provider a test made, ended after it. */
    private val made = mutableListOf<JdbcConnectionProvider>()

    /** [provider], ended after the test. */
    protected fun open(graph: String = JdbcConnectionProvider.DEFAULT_GRAPH): JdbcConnectionProvider = provider(graph).also { made += it }

    /** Ends the pools a test made. */
    protected fun endAll() {
        made.forEach { runCatching { it.end() } }
        made.clear()
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
