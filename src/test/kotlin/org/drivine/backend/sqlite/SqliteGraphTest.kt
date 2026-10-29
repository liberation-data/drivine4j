package org.drivine.backend.sqlite

import org.drivine.backend.postgres.PostgresGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.mapper.JdbcResultMapper
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteGraphTest {

    @Test
    fun `the graph names its tables after itself, as Oracle's and Postgres's do`() {
        val graph = SqliteGraph("drivine")
        val postgres = PostgresGraph("drivine")
        assertEquals(listOf(postgres.nodes, postgres.edges, postgres.catalog), listOf(graph.nodes, graph.edges, graph.catalog))
        assertEquals(postgres.objectName("ix", listOf("Person", "email")), graph.objectName("ix", listOf("Person", "email")))
    }

    @Test
    fun `a graph's name is one a table can be named after`() {
        assertThrows<IllegalArgumentException> { SqliteGraph("1graph") }
        assertThrows<IllegalArgumentException> { SqliteGraph("my-graph") }
        assertThrows<IllegalArgumentException> { SqliteGraph("x'; DROP TABLE y; --") }
    }

    @Test
    fun `the store is the tables Postgres keeps, with JSON as text, each statement safe to run again`() {
        val ddl = SqliteGraph("g").ddl
        assertTrue(ddl.all { "IF NOT EXISTS" in it }, ddl.joinToString("\n"))
        assertTrue(ddl[0].startsWith("CREATE TABLE IF NOT EXISTS g_node") && "labels TEXT NOT NULL" in ddl[0] && "props  TEXT NOT NULL" in ddl[0], ddl[0])
        assertTrue("REFERENCES g_node (nid) ON DELETE CASCADE" in ddl[1] && "DEFAULT '{}'" in ddl[1], ddl[1])
        assertTrue("PRIMARY KEY (kind, label, properties)" in ddl[2], ddl[2])
        assertEquals(listOf("CREATE INDEX IF NOT EXISTS g_edge_src ON g_edge (src, type)", "CREATE INDEX IF NOT EXISTS g_edge_dst ON g_edge (dst, type)"), ddl.drop(3))
    }

    @Test
    fun `the store is made where it is not all there, and nothing is run but the look where it is`(@TempDir directory: Path) {
        val graph = SqliteGraph("g")
        val connection = JdbcConnection(DriverManager.getConnection("jdbc:sqlite:${directory.resolve("g.db")}"), JdbcResultMapper(), DatabaseType.SQLITE, "g")
        val sent = mutableListOf<String>()
        val run = { statement: String -> sent += statement; connection.query(QuerySpecification.withStatement(statement)) }
        try {
            graph.ensure(run)
            assertEquals(listOf(graph.made) + graph.ddl, sent)

            // A store all there: only looked for.
            sent.clear()
            graph.ensure(run)
            assertEquals(listOf(graph.made), sent)

            // A store with an index missing, as one an earlier version made: made again, and the index is back.
            connection.query(QuerySpecification.withStatement("DROP INDEX g_edge_dst"))
            sent.clear()
            graph.ensure(run)
            assertEquals(listOf(graph.made) + graph.ddl, sent)
            assertEquals(listOf<Any?>(graph.ddl.size), connection.query(QuerySpecification.withStatement(graph.made)).map { (it as Number).toInt() })
        } finally {
            connection.release()
        }
    }
}
