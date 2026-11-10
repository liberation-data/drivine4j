package org.drivine.query

import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.drivine.cypher.sql.ColumnKind
import org.drivine.cypher.sql.ResultColumn
import org.drivine.mapper.JdbcTypedValues
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [QueryResult] and its values, read with no store but SQLite's file: a node's labels sorted, equality by value, a path's
 * shape checked, a compiled row's tags read only in the columns that hold them, and a mapped spec refused.
 */
class QueryResultTest {

    @Test
    fun `a node's labels are sorted, so two nodes the stores give in other orders are equal`() {
        val a = GraphNode(listOf("Person", "CyTest"), mapOf("name" to "Keanu"))
        val b = GraphNode(setOf("CyTest", "Person"), mapOf("name" to "Keanu"))
        assertEquals(listOf("CyTest", "Person"), a.labels)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, GraphNode(listOf("Person"), mapOf("name" to "Keanu")))
        assertEquals("GraphNode(labels=[CyTest, Person], properties={name=Keanu})", a.toString())
    }

    @Test
    fun `values are copied, so changing what they were made of changes nothing`() {
        val properties = mutableMapOf<String, Any?>("a" to 1L)
        val labels = mutableListOf("A")
        val node = GraphNode(labels, properties)
        properties["b"] = 2L
        labels += "B"
        assertEquals(mapOf("a" to 1L), node.properties)
        assertEquals(listOf("A"), node.labels)
        val row = mutableListOf<Any?>(1L)
        val result = QueryResult(listOf("n"), listOf(row))
        row += 2L
        assertEquals(listOf(listOf(1L)), result.rows)
    }

    @Test
    fun `a path has one more node than relationships, and a direction for each relationship`() {
        val n = GraphNode(listOf("A"), emptyMap())
        val r = GraphRelationship("R", emptyMap())
        assertEquals(listOf(false), GraphPath(listOf(n, n), listOf(r), listOf(false)).forward)
        assertEquals(GraphPath(listOf(n), emptyList(), emptyList()), GraphPath(listOf(n), emptyList(), emptyList()))
        assertThrows<IllegalArgumentException> { GraphPath(listOf(n), listOf(r), listOf(true)) }
        assertThrows<IllegalArgumentException> { GraphPath(listOf(n, n), listOf(r), emptyList()) }
    }

    @Test
    fun `a compiled row's tags are read only in a column of nodes or relationships`() {
        val node = mapOf("\$t" to "node", "labels" to listOf("B", "A"), "properties" to mapOf("x" to 1L))
        val relationship = mapOf("\$t" to "relationship", "type" to "R", "properties" to emptyMap<String, Any?>())
        val result = JdbcTypedValues.result(
            listOf(
                ResultColumn("n", ColumnKind.NODE), ResultColumn("r", ColumnKind.RELATIONSHIP),
                ResultColumn("ns", ColumnKind.NODES), ResultColumn("m", ColumnKind.VALUE),
            ),
            listOf(listOf(node, relationship, listOf(node, null), node), listOf(null, null, null, null)),
        )
        val graphNode = GraphNode(listOf("A", "B"), mapOf("x" to 1L))
        assertEquals(listOf("n", "r", "ns", "m"), result.columns)
        assertEquals(listOf(graphNode, GraphRelationship("R", emptyMap()), listOf(graphNode, null), node), result.rows[0])
        assertEquals(listOf(null, null, null, null), result.rows[1])
    }

    @Test
    fun `a compiled row of one column is its value, and a statement of no columns gives no rows`() {
        assertEquals(QueryResult(listOf("x"), listOf(listOf(listOf(1L, 2L)))), JdbcTypedValues.result(listOf(ResultColumn("x", ColumnKind.VALUE)), listOf(listOf(1L, 2L))))
        assertEquals(QueryResult(emptyList(), emptyList()), JdbcTypedValues.result(emptyList(), listOf(1L)))
        assertThrows<IllegalStateException> { JdbcTypedValues.result(listOf(ResultColumn("n", ColumnKind.NODE)), listOf(mapOf("\$t" to "map"))) }
    }

    @Test
    fun `a spec that maps its results is refused, and plain SQL gives the driver's columns, a result with no rows too`() {
        val pm = SqliteTestDatabase.graphManager("result")
        val mapped = QuerySpecification.withStatement(CypherStatement("RETURN 1 AS n")).transform(String::class.java)
        val failure = assertThrows<DrivineException> { pm.queryResult(mapped) }
        assertContains(failure.message!!, "use query for mapped results")
        assertEquals(QueryResult(listOf("a", "b"), listOf(listOf(1L, "x"))), pm.queryResult(QuerySpecification.withStatement("SELECT 1 AS a, 'x' AS b")))
        assertEquals(QueryResult(listOf("a"), emptyList()), pm.queryResult(QuerySpecification.withStatement("SELECT 1 AS a WHERE 1 = 0")))
    }

    @Test
    fun `a connection written before queryResult refuses it`() {
        val connection = object : Connection {
            override fun sessionId() = "old"
            override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList()
            override fun startTransaction() {}
            override fun commitTransaction() {}
            override fun rollbackTransaction() {}
            override fun release(err: Throwable?) {}
        }
        val spec = QuerySpecification.withStatement("RETURN 1")
        assertContains(assertThrows<UnsupportedOperationException> { connection.queryResult(spec) }.message!!, "use query")
    }
}
