package org.drivine.store

import org.drivine.connection.StoreLayoutException
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sql.SqlGraph
import org.drivine.backend.sqlite.SqliteGraph
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The store layout's verdict on a graph, on each SQL store (liberation-data/drivine-cypher-spec#161), as a manager's first
 * connection takes it. A clear, `MATCH (n) DETACH DELETE n`, deletes Drivine's own nodes too, the store stamp among them, as
 * on a graph engine: a graph of this layout cleared and loaded again is stamped again on the next manager's first
 * connection, and used, not refused as one an earlier Drivine wrote.
 */
abstract class StoreLayoutContract {

    /** A new manager of [graph], over a provider of its own, so its first connection asks the verdict. */
    abstract fun manager(graph: String): NonTransactionalPersistenceManager

    /** The store's graph of [name], for its stamp's statements. */
    internal abstract fun graph(name: String): SqlGraph

    /** Drops [graph]'s tables, so a test starts from none. */
    abstract fun drop(graph: String)

    /** The store's name in the spec's files: `postgres`, `oracle`, `sqlite`. */
    abstract val store: String

    /** Runs [statement] on the store, outside any graph's manager. */
    abstract fun run(statement: String)

    /** Whether the store has a table named [table]. */
    abstract fun tableThere(table: String): Boolean

    private fun cypher(pm: NonTransactionalPersistenceManager, statement: String): List<Any> =
        pm.query(QuerySpecification.withStatement(CypherStatement(statement)))

    private fun stamps(pm: NonTransactionalPersistenceManager, graph: String): List<Any> =
        pm.query(QuerySpecification.withStatement(StoreStampStatements.of(graph(graph).store).readStamps))

    @Test
    fun `a graph cleared and loaded again is stamped again on the next first connection, and used`() {
        val name = "cy_clear_reload"
        drop(name)
        val first = manager(name)
        cypher(first, "CREATE (:Person {name: 'Ada'})")
        // The clear deletes the stamp with every node, and the values written after it have none.
        cypher(first, "MATCH (n) DETACH DELETE n")
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        assertEquals(0, stamps(first, name).size, "the clear deleted the stamp")
        val next = manager(name)
        assertEquals(listOf<Any>("Grace"), cypher(next, "MATCH (p:Person) RETURN p.name"))
        val stamped = stamps(next, name).single() as Map<*, *>
        assertEquals(2L, (stamped["layout"] as Number).toLong(), "stamped again in this layout: $stamped")
    }

    @Test
    fun `an empty graph stamped with an earlier layout is stamped with this one on the next first connection, and used`() {
        val name = "cy_layout_restamp"
        drop(name)
        // A graph stamped as the clear above leaves one, its value then deleted and its stamp given the earlier layout.
        val first = manager(name)
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        cypher(first, "MATCH (n) DETACH DELETE n")
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        val stamped = manager(name)
        cypher(stamped, "MATCH (p:Person) DETACH DELETE p")
        cypher(stamped, "MATCH (s:DrivineStore) SET s.layout = 1")
        assertEquals(1L, ((stamps(stamped, name).single() as Map<*, *>)["layout"] as Number).toLong())
        val next = manager(name)
        val restamped = stamps(next, name).single() as Map<*, *>
        assertEquals(2L, (restamped["layout"] as Number).toLong(), "stamped again in this layout: $restamped")
        cypher(next, "CREATE (:Person {name: 'Ada'})")
        assertEquals(listOf<Any>("Ada"), cypher(next, "MATCH (p:Person) RETURN p.name"))
    }

    /**
     * `marker.yaml`'s `firstConnection` cases: an earlier layout's graph holding a value, with no stamp, set up on the store, is
     * refused on a manager's first connection before anything is made in it, this layout's lock table among them, as the
     * verdict reads that table as its evidence.
     */
    @Test
    fun `an earlier layout's graph with no stamp is refused on the first connection, and nothing is made in it`() {
        val pinned = ObjectMapper(YAMLFactory()).readTree(requireNotNull(javaClass.getResource("/cypher/marker.yaml")) { "No spec file cypher/marker.yaml" })
        val cases = pinned["storeLayout"]["firstConnection"].filter { it["store"].asText() == store }
        assertEquals(1, cases.size, "marker.yaml's firstConnection cases on $store")
        for (case in cases) {
            val name = "cy_first_conn"
            drop(name)
            case["setup"].forEach { run(it.asText().replace("{graph}", name)) }
            val refused = assertFailsWith<StoreLayoutException>(case["name"].asText()) { manager(name) }
            assertEquals(case["refuse"].asText().replace("{graph}", name), refused.message, case["name"].asText())
            for (absent in case["absent"]) assertFalse(tableThere(absent.asText().replace("{graph}", name)), "${case["name"].asText()}: ${absent.asText()} was made")
        }
    }

    @Test
    fun `a graph that holds values and no stamp, and none of this layout's objects, is refused`() {
        val name = "cy_layout_old"
        drop(name)
        val first = manager(name)
        cypher(first, "CREATE (:Person {name: 'Ada'})")
        cypher(first, "MATCH (n) DETACH DELETE n")
        cypher(first, "CREATE (:Person {name: 'Grace'})")
        // As an earlier Drivine leaves a graph: no lock table, which only this layout makes, and no stamp.
        first.execute(QuerySpecification.withStatement("DROP TABLE ${graph(name).store.lock}"))
        val refused = assertFailsWith<StoreLayoutException> { cypher(manager(name), "MATCH (p:Person) RETURN p.name") }
        assertEquals(name, refused.graph)
    }
}

@PostgresTests
class PostgresStoreLayoutTest : StoreLayoutContract() {
    override val store = "postgres"
    override fun run(statement: String) = PostgresTestDatabase.run(statement)
    override fun tableThere(table: String) =
        PostgresTestDatabase.pm.query(QuerySpecification.withStatement("SELECT to_regclass('${table.lowercase()}')::text AS t")).single() != null
    override fun manager(graph: String) = PostgresTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = PostgresGraph(name)
    override fun drop(graph: String) {
        val store = PostgresGraph(graph).store
        PostgresTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}, ${store.nodes}, ${store.catalog}, ${store.lock} CASCADE")
    }
}

@OracleTests
class OracleStoreLayoutTest : StoreLayoutContract() {
    override val store = "oracle"
    override fun run(statement: String) = OracleTestDatabase.run(statement)
    override fun tableThere(table: String) =
        (OracleTestDatabase.pm.query(QuerySpecification.withStatement("SELECT COUNT(*) FROM user_tables WHERE table_name = UPPER('$table')")).single() as Number).toInt() > 0
    override fun manager(graph: String) = OracleTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = OracleGraph(name)
    override fun drop(graph: String) {
        val store = OracleGraph(graph).store
        OracleTestDatabase.drop("TABLE ${store.edges}", "TABLE ${store.nodes}", "TABLE ${store.catalog}", "TABLE ${store.lock}")
    }
}

class SqliteStoreLayoutTest : StoreLayoutContract() {

    /**
     * A Spring transaction's connection is only looked at, as its statements are the transaction's (#395, #713): a graph of
     * an earlier layout is refused there too, on every connection, and nothing is made in it.
     */
    @Test
    fun `a Spring transaction's connection to a graph of an earlier layout is refused, and nothing is made in it`(@TempDir directory: Path) {
        val name = "spring_earlier"
        val source = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:${directory.resolve("earlier.db")}" }
        source.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE ${name}_node (nid INTEGER PRIMARY KEY AUTOINCREMENT, labels TEXT NOT NULL, props TEXT NOT NULL, stamp TEXT)")
                s.execute("INSERT INTO ${name}_node (labels, props) VALUES ('[\"Person\"]', '{\"name\": \"Ada\"}')")
            }
        }
        val held = source.connection.apply { autoCommit = false }
        val provider = JdbcConnectionProvider("earlier", DatabaseType.SQLITE, TransactionAwareDataSourceProxy(source), graphName = name)
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(source, ConnectionHolder(held))
        try {
            repeat(2) { assertEquals(name, assertFailsWith<StoreLayoutException> { provider.connect() }.graph) }
        } finally {
            TransactionSynchronizationManager.unbindResource(source)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
            held.close()
        }
        source.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT group_concat(name) FROM (SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name)").use {
                    it.next()
                    assertEquals("${name}_node", it.getString(1))
                }
            }
        }
    }

    override val store = "sqlite"
    override fun run(statement: String) = SqliteTestDatabase.run(statement)
    override fun tableThere(table: String) =
        SqliteTestDatabase.pm.query(QuerySpecification.withStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'")).isNotEmpty()
    override fun manager(graph: String) = SqliteTestDatabase.graphManager(graph)
    override fun graph(name: String): SqlGraph = SqliteGraph(name)
    override fun drop(graph: String) {
        val store = SqliteGraph(graph).store
        SqliteTestDatabase.run("DROP TABLE IF EXISTS ${store.edges}", "DROP TABLE IF EXISTS ${store.nodes}", "DROP TABLE IF EXISTS ${store.catalog}", "DROP TABLE IF EXISTS ${store.lock}")
    }
}
