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
import org.neo4j.driver.Record
import org.neo4j.driver.Result
import org.neo4j.driver.Session
import org.neo4j.driver.Values
import org.neo4j.driver.summary.Plan
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

    private fun operator(type: String, details: String?, vararg children: Plan): Plan {
        val plan = Mockito.mock(Plan::class.java)
        `when`(plan.operatorType()).thenReturn(type)
        `when`(plan.arguments()).thenReturn(if (details == null) emptyMap() else mapOf("Details" to Values.value(details)))
        `when`(plan.children()).thenReturn(children.toList())
        return plan
    }

    private fun planned(rows: List<Record>, plan: Plan?): org.drivine.query.QueryPlan {
        val session = Mockito.mock(Session::class.java)
        val result = Mockito.mock(Result::class.java)
        val summary = Mockito.mock(ResultSummary::class.java)
        `when`(result.list()).thenReturn(rows)
        `when`(result.consume()).thenReturn(summary)
        `when`(summary.hasPlan()).thenReturn(plan != null)
        `when`(summary.plan()).thenReturn(plan)
        `when`(session.run(anyString(), anyMap<String, Any>())).thenReturn(result)
        return Neo4jConnection(session, Mockito.mock(ResultMapper::class.java))
            .plan(QuerySpecification.withStatement(CypherStatement("MATCH (n) RETURN n")))
    }

    @Test
    fun `Neo4j's plan is an operator a line, without @neo4j, its details after two spaces, indented by its depth`() {
        val plan = planned(
            emptyList(),
            operator(
                "ProduceResults@neo4j", "n",
                operator("Filter@neo4j", "n.x = \$x", operator("AllNodesScan@neo4j", "n")),
                operator("Argument@neo4j", " "),
                operator("EmptyResult@neo4j", null),
            ),
        )
        assertEquals(
            listOf("ProduceResults  n", "  Filter  n.x = \$x", "    AllNodesScan  n", "  Argument", "  EmptyResult"),
            plan.lines,
        )
        assertEquals(null, plan.none)
    }

    @Test
    fun `Memgraph's plan, with no driver plan, is the rows of its EXPLAIN as they are`() {
        val rows = listOf(" * Produce {n}", " * ScanAll (n)", " * Once").map { line ->
            Mockito.mock(Record::class.java).also { `when`(it.values()).thenReturn(listOf(Values.value(line))) }
        }
        assertEquals(listOf(" * Produce {n}", " * ScanAll (n)", " * Once"), planned(rows, null).lines)
    }
}
