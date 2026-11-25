package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QueryLanguage
import org.drivine.query.QueryResult
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** That [SqlCypherFixtures] sends a statement to a SQL store as it is, and what a clear is. */
class SqlCypherFixturesTest {

    /** Each statement that reached the store, in its language, in order. */
    private val sent = mutableListOf<Pair<QueryLanguage, String>>()

    private val store = store("g")

    private fun store(graph: String?): PersistenceManager =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PersistenceManager::class.java)) { _, method, arguments ->
            fun record(spec: Any?) = (spec as QuerySpecification<*>).statement!!.let { sent += it.language to it.text }
            when (method.name) {
                "getGraphName" -> graph
                "getType" -> DatabaseType.POSTGRES
                "execute" -> record(arguments[0]).let { null }
                "query" -> record(arguments[0]).let { listOf("row") }
                "getOne" -> record(arguments[0]).let { "row" }
                "maybeGetOne" -> record(arguments[0]).let { "row" }
                "optionalGetOne" -> record(arguments[0]).let { Optional.of("row") }
                "executeBatch" -> (arguments[0] as List<*>).forEach(::record).let { null }
                "queryBatch" -> (arguments[0] as List<*>).onEach(::record).map { listOf("row") }
                "queryResult" -> record(arguments[0]).let { QueryResult(listOf("x"), listOf(listOf("row"))) }
                "explain" -> record(arguments[0]).let { "SELECT 1" }
                "plan" -> record(arguments[0]).let { org.drivine.query.QueryPlan(listOf("SCAN")) }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PersistenceManager

    private val fixtures = SqlCypherFixtures.over(store)

    private fun statement(text: String) = QuerySpecification.withStatement(text)

    @Test
    fun `a statement goes to the store as it is, however it is run`() {
        fixtures.execute(statement("CREATE (:Human {id: 'ada'})"))
        fixtures.query(statement("MATCH (h:Human) RETURN h.id"))
        fixtures.getOne(statement("OPTIONAL MATCH (h:Human) RETURN h"))
        fixtures.maybeGetOne(statement("MERGE (h:Human {id: 'bob'}) RETURN h"))
        fixtures.optionalGetOne(statement("UNWIND [1, 2] AS x RETURN x"))
        fixtures.executeBatch(listOf(statement("RETURN 1")))
        fixtures.queryBatch(listOf(statement("MATCH (h) RETURN h")))
        fixtures.queryResult(statement("MATCH (h:Human) RETURN h"))
        fixtures.explain(statement("MATCH (h:Human) RETURN h"))
        fixtures.plan(statement("MATCH (h:Human) RETURN h"))
        fixtures.query(QuerySpecification.withStatement(sqlStatement("SELECT COUNT(*) FROM g_node")))

        // A statement of no language is Cypher on every store: the connection resolves it, so it goes unchanged.
        assertEquals(List(10) { QueryLanguage.PLATFORM_DEFAULT } + QueryLanguage.SQL, sent.map { it.first })
    }

    @Test
    fun `what the store gives back is given back`() {
        assertEquals(listOf("row"), fixtures.query(statement("MATCH (h) RETURN h")))
        assertSame("row", fixtures.getOne(statement("MATCH (h) RETURN h")))
    }

    @Test
    fun `the statement that empties a graph on the graph engines empties the two tables, the relationships first`() {
        fixtures.execute(statement("  MATCH (n) DETACH DELETE n  "))

        assertEquals(listOf("DELETE FROM g_edge", "DELETE FROM g_node"), sent.map { it.second })
    }

    @Test
    fun `a clear needs a graph to name the tables by`() {
        assertEquals("The manager names no graph.", assertFailsWith<IllegalArgumentException> { SqlCypherFixtures.clear(store(null)) }.message)
    }

    @Test
    fun `a fixture run on its own is Cypher, whatever space it is written in`() {
        assertEquals(listOf<Any?>("row"), SqlCypherFixtures.run(store, "\n  MATCH (h:Human) RETURN h.id  \n"))

        assertEquals(listOf(QueryLanguage.CYPHER to "MATCH (h:Human) RETURN h.id"), sent)
    }

    @Test
    fun `a store that speaks SQL has its clear run on its tables`() {
        SqlCypherFixtures.forStore(store).execute(statement("MATCH (n) DETACH DELETE n"))

        assertEquals(listOf(QueryLanguage.SQL to "DELETE FROM g_edge", QueryLanguage.SQL to "DELETE FROM g_node"), sent)
    }

    @Test
    fun `a store that speaks Cypher is given its statements as they are`() {
        val neo4j = object : PersistenceManager by store {
            override val type = DatabaseType.NEO4J
        }

        assertSame(neo4j, SqlCypherFixtures.forStore(neo4j))
    }
}
