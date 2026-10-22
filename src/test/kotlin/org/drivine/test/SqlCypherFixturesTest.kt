package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Which statements [SqlCypherFixtures] sends to a SQL store as Cypher, which as they are, and what a clear is. */
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
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PersistenceManager

    private val fixtures = SqlCypherFixtures.over(store)

    private fun statement(text: String) = QuerySpecification.withStatement(text)

    @Test
    fun `a statement of no language that is Cypher goes to the store as Cypher, however it is run`() {
        fixtures.execute(statement("CREATE (:Human {id: 'ada'})"))
        fixtures.query(statement("MATCH (h:Human) RETURN h.id"))
        fixtures.getOne(statement("  // the one\n  OPTIONAL MATCH (h:Human) RETURN h"))
        fixtures.maybeGetOne(statement("merge (h:Human {id: 'bob'}) RETURN h"))
        fixtures.optionalGetOne(statement("UNWIND [1, 2] AS x RETURN x"))
        fixtures.executeBatch(listOf(statement("RETURN 1")))
        fixtures.queryBatch(listOf(statement("MATCH (h) RETURN h")))

        assertEquals(List(7) { QueryLanguage.CYPHER }, sent.map { it.first })
    }

    @Test
    fun `a statement in the store's own language goes as it is, and one given a language keeps it`() {
        val sql = listOf(
            "SELECT COUNT(*) FROM g_node",
            "CREATE INDEX g_ix ON g_node (nid)",
            "WITH roots AS (SELECT nid FROM g_node) SELECT COUNT(*) FROM roots",
            "BEGIN NULL; END;",
        )
        sql.forEach { fixtures.execute(statement(it)) }
        fixtures.query(QuerySpecification.withStatement(sqlStatement("MATCH (h) RETURN h")))

        assertEquals(sql.map { QueryLanguage.PLATFORM_DEFAULT to it } + (QueryLanguage.SQL to "MATCH (h) RETURN h"), sent)
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
    fun `a store that speaks Cypher is given its statements as they are`() {
        val neo4j = object : PersistenceManager by store {
            override val type = DatabaseType.NEO4J
        }

        assertSame(neo4j, SqlCypherFixtures.forStore(neo4j))
    }
}
