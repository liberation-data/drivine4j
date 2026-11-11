package org.drivine.connection

import com.falkordb.Graph
import org.drivine.mapper.ResultMapper
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What `FalkorDbConnection.plan` sends to `GRAPH.EXPLAIN`, and gives back. The graph is a mock here. */
class FalkorDbConnectionPlanTest {

    @Test
    fun `the plan is GRAPH_EXPLAIN's lines of the statement as it would run, its parameters written into it`() {
        val sent = mutableListOf<String>()
        val connection = FalkorDbConnection(Mockito.mock(Graph::class.java), Mockito.mock(ResultMapper::class.java)) { query ->
            sent += query
            listOf("Results", "    Project", "        Filter", "            Node By Label Scan | (n:Person)")
        }
        val spec = QuerySpecification.withStatement(CypherStatement("MATCH (n:Person) WHERE n.x = \$x RETURN n"))
            .bind(mapOf("x" to 1))
            .limit(5)
        val plan = connection.plan(spec)
        assertEquals(listOf("Results", "    Project", "        Filter", "            Node By Label Scan | (n:Person)"), plan.lines)
        assertEquals(null, plan.none)
        assertEquals(listOf("CYPHER `x`=1 MATCH (n:Person) WHERE n.x = \$x RETURN n  LIMIT 5"), sent)

        // With no parameters, the statement as it is.
        connection.plan(QuerySpecification.withStatement(CypherStatement("RETURN 1")))
        assertEquals("RETURN 1", sent.last())
    }

    @Test
    fun `a connection with no way to send GRAPH_EXPLAIN refuses a plan`() {
        val connection = FalkorDbConnection(Mockito.mock(Graph::class.java), Mockito.mock(ResultMapper::class.java))
        assertFailsWith<UnsupportedOperationException> { connection.plan(QuerySpecification.withStatement(CypherStatement("RETURN 1"))) }
    }
}
