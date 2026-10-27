package org.drivine.connection

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
import kotlin.test.assertEquals

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
