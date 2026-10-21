package org.drivine.backend.postgres

import org.drivine.backend.oracle.OracleGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresGraphTest {

    @Test
    fun `the graph names its tables after itself, as Oracle's does`() {
        val graph = PostgresGraph("drivine")
        assertEquals("drivine_node", graph.nodes)
        assertEquals("drivine_edge", graph.edges)
        assertEquals("drivine_schema", graph.catalog)
        val oracle = OracleGraph("drivine")
        assertEquals(listOf(oracle.nodes, oracle.edges, oracle.catalog), listOf(graph.nodes, graph.edges, graph.catalog))
    }

    @Test
    fun `a graph's name is as long as leaves room within Postgres's 63 for what it names`() {
        // A table beside the nodes, `_v__` and an 8-digit hash with nothing readable, and its `_t` trigger.
        val longest = "g" + "x".repeat(48)
        assertEquals(longest, PostgresGraph(longest).name)
        val failure = assertThrows<IllegalArgumentException> { PostgresGraph(longest + "x") }
        assertTrue("Postgres" in failure.message.orEmpty() && "49" in failure.message.orEmpty(), failure.message)
        assertThrows<IllegalArgumentException> { PostgresGraph("1graph") }
        assertThrows<IllegalArgumentException> { PostgresGraph("my-graph") }
    }

    @Test
    fun `an object's name fits within 63 with what is made beside it`() {
        val graph = PostgresGraph("g" + "x".repeat(48))
        val labels = listOf("A".repeat(60), "embedding".repeat(5))
        // A table beside the nodes has a trigger and an index named after it; an index on the nodes stands alone.
        for (side in listOf(graph.vectors(labels[0], labels[1]), graph.texts(labels[0], labels))) {
            assertTrue(side.length + "_t".length <= 63, "$side is ${side.length} long")
        }
        assertTrue(graph.objectName("uq", labels).length <= 63)
    }

    @Test
    fun `an object a store of either kind makes for the same parts has the same name, where the name fits`() {
        assertEquals(OracleGraph("drivine").vectors("Doc", "embedding"), PostgresGraph("drivine").vectors("Doc", "embedding"))
    }

    @Test
    fun `the store is made by one block that takes a lock, and is marked with its version last`() {
        val ensuring = PostgresGraph("g").ensuring
        assertTrue(ensuring.startsWith("DO \$ensure\$"), ensuring)
        assertTrue("pg_advisory_xact_lock(hashtext('drivine:g'))" in ensuring, ensuring)
        assertTrue("USING GIN (labels jsonb_path_ops)" in ensuring, ensuring)
        assertTrue(ensuring.indexOf("COMMENT ON TABLE g_node IS '${PostgresGraph.VERSION}'") > ensuring.indexOf("CREATE INDEX IF NOT EXISTS g_node_labels"), ensuring)
    }
}
