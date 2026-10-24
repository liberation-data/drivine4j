package org.drivine.backend.sqlite

import org.drivine.backend.postgres.PostgresGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
}
