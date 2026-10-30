package org.drivine.postgres

import org.drivine.backend.postgres.PostgresGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.test.SqlCypherFixtures
import org.drivine.test.TestCleanup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tables of a graph on Postgres: made once, by however many processes, and read and written by the test kit's fixtures. */
@PostgresTests
class PostgresGraphIntegrationTest {

    private val graph = "made"

    /** A manager for [graph], which is dropped first and made by nothing but what a test does. */
    private val manager = NonTransactionalPersistenceManager(
        JdbcConnectionProvider(graph, DatabaseType.POSTGRES, PostgresTestDatabase.provider.nativeDriver, graphName = graph), graph, DatabaseType.POSTGRES, SubtypeRegistry(),
    )

    private fun spec(sql: String) = QuerySpecification.withStatement(sql.trimIndent())

    private fun tables(): List<String> = PostgresTestDatabase.pm.query(
        spec("SELECT table_name FROM information_schema.tables WHERE table_name LIKE '${graph}\\_%' ORDER BY table_name").transform(String::class.java)
    )

    @BeforeEach
    fun dropGraph() = PostgresTestDatabase.drop(graph)

    @Test
    fun `ensuring makes the tables and marks them, and ensuring again changes nothing`() {
        PostgresGraph(graph).ensure(manager)
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())
        val comment = PostgresTestDatabase.pm.getOne(spec("SELECT obj_description(to_regclass('made_node'), 'pg_class')").transform(String::class.java))
        assertEquals(PostgresGraph.VERSION, comment)

        manager.execute(spec("INSERT INTO made_node (labels, props) VALUES ('[\"Person\"]', '{\"id\": \"ada\"}')"))
        PostgresGraph(graph).ensure(manager)
        assertEquals(listOf(1L), manager.query(spec("SELECT COUNT(*) FROM made_node")))
    }

    @Test
    fun `a store an earlier version made is made again over its rows, with the functions this version adds`() {
        PostgresGraph(graph).ensure(manager)
        manager.execute(spec("INSERT INTO made_node (labels, props) VALUES ('[\"Person\"]', '{\"id\": \"ada\"}')"))
        // As drivine:6 left it: no function for the lowercased copies (@CaseInsensitive).
        manager.execute(spec("DROP FUNCTION made_lowered(jsonb, jsonb)"))
        manager.execute(spec("COMMENT ON TABLE made_node IS 'drivine:6'"))

        PostgresGraph(graph).ensure(manager)
        val comment = PostgresTestDatabase.pm.getOne(spec("SELECT obj_description(to_regclass('made_node'), 'pg_class')").transform(String::class.java))
        assertEquals(PostgresGraph.VERSION, comment)
        assertEquals(listOf(true), manager.query(spec("SELECT to_regprocedure('made_lowered(jsonb, jsonb)') IS NOT NULL").transform(Boolean::class.java)))
        assertEquals(listOf(1L), manager.query(spec("SELECT COUNT(*) FROM made_node")))
    }

    @Test
    fun `processes that each find the store missing make it one after the other`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val made = (1..8).map { pool.submit { PostgresGraph(graph).ensure(manager) } }
            made.forEach { it.get(1, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())
    }

    @Test
    fun `a node carries any number of labels, and the index over labels answers whether it carries one`() {
        PostgresGraph(graph).ensure(manager)
        SqlCypherFixtures.run(manager, "CREATE (:Person:Author {id: 'ada'}), (:Person {id: 'bob'}), (:Robot {id: 'r2'})")

        val authors = "SELECT props ->> 'id' FROM made_node WHERE labels @> '[\"Person\", \"Author\"]' ORDER BY 1"
        assertEquals(listOf<Any>("ada"), manager.query(spec(authors)))
        assertEquals(listOf<Any>("ada", "bob"), manager.query(spec("SELECT props ->> 'id' FROM made_node WHERE labels @> '[\"Person\"]' ORDER BY 1")))

        // With a scan of the table ruled out, the label is found through the index: so the index can answer it.
        val connection = PostgresTestDatabase.provider.connect()
        try {
            connection.startTransaction()
            connection.query(spec("SET LOCAL enable_seqscan = off"))
            val plan = connection.query(spec("EXPLAIN $authors")).joinToString("\n")
            assertTrue("made_node_labels" in plan, plan)
        } finally {
            connection.release()
        }
    }

    @Test
    fun `the fixtures write nodes and relationships, and read them back in Cypher`() {
        PostgresGraph(graph).ensure(manager)
        val fixtures = SqlCypherFixtures.over(manager)
        fixtures.execute(spec("CREATE (a:Person {id: 'ada', name: 'Ada'})-[:KNOWS {since: 2020}]->(b:Person {id: 'bob'})"))
        fixtures.execute(spec("MATCH (p:Person {id: 'bob'}) SET p.name = 'Bob'"))

        assertEquals(listOf("Ada", "Bob"), fixtures.query(spec("MATCH (p:Person) RETURN p.name ORDER BY p.name").transform(String::class.java)))
        assertEquals(listOf<Any>(2020L), fixtures.query(spec("MATCH (:Person {id: 'ada'})-[k:KNOWS]->(:Person {id: 'bob'}) RETURN k.since")))
        assertEquals(listOf<Any>(listOf("KNOWS", mapOf("since" to 2020L))), manager.query(spec("SELECT type, props FROM made_edge")))

        fixtures.execute(spec("MATCH (n) DETACH DELETE n"))
        assertEquals(listOf<Any>(0L), manager.query(spec("SELECT COUNT(*) FROM made_node")))
    }

    @Test
    fun `a test's cleanup deletes the nodes it tagged, on a graph it makes if it must`() {
        TestCleanup.beforeEach(manager, "tagged")
        assertEquals(listOf("made_edge", "made_node", "made_schema"), tables())
        SqlCypherFixtures.run(manager, "CREATE (:Thing {createdBy: 'tagged'}), (:Thing {createdBy: 'kept'})")

        TestCleanup.beforeEach(manager, "tagged")
        assertEquals(listOf<Any>("kept"), manager.query(spec("SELECT props ->> 'createdBy' FROM made_node")))
    }
}
