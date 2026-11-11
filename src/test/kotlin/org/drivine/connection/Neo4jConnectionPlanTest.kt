package org.drivine.connection

import org.drivine.mapper.ResultMapper
import org.drivine.query.CypherStatement
import org.drivine.query.ParameterCoercer
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.neo4j.driver.Result
import org.neo4j.driver.Session
import org.neo4j.driver.summary.ResultSummary
import kotlin.test.assertEquals

/**
 * What `Neo4jConnection.plan` sends: the statement as [Neo4jConnection.query] would run it, its templates rendered, its
 * paging and its parameters, coerced, with `EXPLAIN` before it. Neo4j and Memgraph only: the driver is a mock here.
 */
class Neo4jConnectionPlanTest {

    @Test
    fun `the plan is of the statement as it would run, rendered, paged and with its parameters coerced`() {
        val session = Mockito.mock(Session::class.java)
        val result = Mockito.mock(Result::class.java)
        val summary = Mockito.mock(ResultSummary::class.java)
        `when`(result.list()).thenReturn(emptyList())
        `when`(result.consume()).thenReturn(summary)
        `when`(summary.hasPlan()).thenReturn(false)
        `when`(session.run(anyString(), anyMap<String, Any>())).thenReturn(result)
        val connection = Neo4jConnection(session, Mockito.mock(ResultMapper::class.java))

        val spec = QuerySpecification.withStatement(CypherStatement("MATCH (n:\$(\$label)) WHERE n.x = \$x RETURN n"))
            .renderParam("label", "Person")
            .bind(mapOf("x" to 1))
            .addParameterCoercers(ParameterCoercer { it + ("coerced" to true) })
            .skip(2)
            .limit(5)
        connection.plan(spec)

        val statement = ArgumentCaptor.forClass(String::class.java)
        @Suppress("UNCHECKED_CAST")
        val parameters = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any>>
        Mockito.verify(session).run(statement.capture(), parameters.capture())
        assertEquals("EXPLAIN MATCH (n:Person) WHERE n.x = \$x RETURN n SKIP 2 LIMIT 5", statement.value)
        assertEquals(mapOf("x" to 1, "coerced" to true), parameters.value)
    }
}
