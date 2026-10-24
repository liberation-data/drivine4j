package org.drivine.sqlite

import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.test.TestCleanup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** The tables of a graph on SQLite: made once, by however many connections, and kept as they are made again. */
class SqliteGraphIntegrationTest {

    private val graph = "made"

    /** A manager for [graph], which is dropped first and made by nothing but what a test does. */
    private val manager = NonTransactionalPersistenceManager(
        JdbcConnectionProvider(graph, DatabaseType.SQLITE, SqliteTestDatabase.provider.nativeDriver, graphName = graph), graph, DatabaseType.SQLITE, SubtypeRegistry(),
    )

    private fun spec(sql: String) = QuerySpecification.withStatement(sql.trimIndent())

    private fun tables(): List<String> = manager.query(
        spec("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'made\\_%' ESCAPE '\\' ORDER BY name").transform(String::class.java)
    )

    @BeforeEach
    fun dropGraph() = SqliteTestDatabase.drop(graph)

    @Test
    fun `ensuring makes the tables, and ensuring again keeps what they hold`() {
        SqliteGraph(graph).ensure(manager)
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())

        manager.execute(spec("INSERT INTO made_node (labels, props) VALUES ('[\"Person\"]', '{\"id\": \"ada\"}')"))
        SqliteGraph(graph).ensure(manager)
        assertEquals(listOf(1L), manager.query(spec("SELECT COUNT(*) FROM made_node")))

        // A name in another case names the same tables, as Postgres folds it to.
        SqliteGraph("MADE").ensure(manager)
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())
        assertEquals(listOf(1L), manager.query(spec("SELECT COUNT(*) FROM MADE_node")))
    }

    @Test
    fun `connections that each find the store missing make it one after the other`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..8).map { pool.submit { SqliteGraph(graph).ensure(manager) } }.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())
    }

    @Test
    fun `a node's relationships go with it, and the test kit's cleanup removes the nodes it tagged`() {
        SqliteGraph(graph).ensure(manager)
        val seed = SqliteSeed(manager)
        seed.node("Person", properties = mapOf("id" to "ada", "createdBy" to "kit"))
        seed.node("Person", properties = mapOf("id" to "bob"))
        seed.edge("ada", "KNOWS", "bob")

        TestCleanup.beforeEach(manager, "kit")

        assertEquals(listOf(1L), manager.query(spec("SELECT COUNT(*) FROM made_node")))
        assertEquals(listOf(0L), manager.query(spec("SELECT COUNT(*) FROM made_edge")))
    }
}
