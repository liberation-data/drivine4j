package org.drivine.connection

import com.falkordb.Graph
import org.drivine.mapper.ResultMapper
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * A statement the library gives a timeout of the shared module's plan (drivine4j#868) runs under it, in milliseconds, as
 * `GRAPH.QUERY`'s `TIMEOUT`, with its parameters or with none; one with no timeout runs as before. The graph is a mock
 * here, whose results have no columns.
 */
class FalkorDbConnectionTimeoutTest {

    @Test
    fun `a statement with a timeout runs under it, with its parameters`() {
        val graph = Mockito.mock(Graph::class.java, Mockito.RETURNS_MOCKS)
        val connection = FalkorDbConnection(graph, Mockito.mock(ResultMapper::class.java))
        val spec = QuerySpecification.withStatement(CypherStatement("MATCH (n) WHERE n.x = \$x RETURN n")).bind(mapOf("x" to 1))
            .also { it.timeoutMillis = 300_000 }

        connection.query(spec)

        Mockito.verify(graph).query(Mockito.anyString(), Mockito.anyMap(), Mockito.eq(300_000L))
        Mockito.verifyNoMoreInteractions(graph)
    }

    @Test
    fun `a statement with a timeout and no parameters runs under it, as the statement it is`() {
        val graph = Mockito.mock(Graph::class.java, Mockito.RETURNS_MOCKS)
        val connection = FalkorDbConnection(graph, Mockito.mock(ResultMapper::class.java))
        val spec = QuerySpecification.withStatement(CypherStatement("RETURN 1")).also { it.timeoutMillis = 300_000 }

        connection.query(spec)

        Mockito.verify(graph).query("RETURN 1", 300_000L)
        Mockito.verifyNoMoreInteractions(graph)
    }

    @Test
    fun `a statement with no timeout runs as it did`() {
        val graph = Mockito.mock(Graph::class.java, Mockito.RETURNS_MOCKS)
        val connection = FalkorDbConnection(graph, Mockito.mock(ResultMapper::class.java))

        connection.query(QuerySpecification.withStatement(CypherStatement("RETURN 1")))
        connection.query(QuerySpecification.withStatement(CypherStatement("RETURN \$x")).bind(mapOf("x" to 1)))

        Mockito.verify(graph).query("RETURN 1")
        Mockito.verify(graph).query(Mockito.anyString(), Mockito.anyMap())
        Mockito.verifyNoMoreInteractions(graph)
    }
}
