package org.drivine.test

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which statements [OracleCypherFixtures] works out as a fixture and which it hands to the store as
 * they are, over an in-memory store.
 */
class OracleCypherFixturesTest {

    private val fake = FakeGraphStore()

    /** What reached the store's own `execute` and `query` untouched, in order. */
    private val handedOn = mutableListOf<String>()

    /** The fake store, which also takes the two statements a clear is made of, and answers any other SQL with one row. */
    private val store: PersistenceManager = object : PersistenceManager by fake {
        override fun execute(spec: QuerySpecification<*>) {
            val text = spec.statement!!.text
            when {
                text == "DELETE FROM g_edge" -> fake.edges.clear().also { handedOn += text }
                text == "DELETE FROM g_node" -> fake.nodes.clear().also { handedOn += text }
                MiniCypher.reads(text) -> throw AssertionError("Cypher reached the store: $text")
                text.startsWith("DECLARE") || text.startsWith("UPDATE g_") || text.startsWith("INSERT INTO g_") || text.startsWith("DELETE FROM g_") -> fake.execute(spec)
                else -> handedOn += text
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
            val text = spec.statement!!.text
            if (MiniCypher.reads(text)) throw AssertionError("Cypher reached the store: $text")
            if (text.startsWith("SELECT JSON_OBJECT(") || text.startsWith("DECLARE")) return fake.query(spec)
            handedOn += text
            return listOf("from the store") as List<T>
        }
    }

    private val fixtures = OracleCypherFixtures.over(store)

    private fun statement(text: String, vararg parameters: Pair<String, Any?>) = QuerySpecification.withStatement(text).bind(mapOf(*parameters))

    @Test
    fun `a Cypher statement that is executed is worked out over the graph, and never reaches the store as Cypher`() {
        fixtures.execute(statement("CREATE (a:Human {id: \$id, name: 'Ada'})-[:KNOWS]->(:Human {id: 'bob'})", "id" to "ada"))

        assertEquals(setOf("ada", "bob"), fake.nodes("Human").map { it.props["id"] }.toSet())
        assertEquals("KNOWS", fake.edges.values.single().type)
        assertTrue(handedOn.isEmpty())
    }

    @Test
    fun `a Cypher statement that is queried gives one value a row, mapped as a row of the store would be`() {
        fixtures.execute(statement("CREATE (:Human {id: 'ada', name: 'Ada'}), (:Human {id: 'bob', name: 'Bob'})"))

        val names = fixtures.query(statement("MATCH (h:Human) RETURN h.name ORDER BY h.name").transform(String::class.java))
        val count = fixtures.getOne(statement("MATCH (h:Human) RETURN count(h)").transform(Long::class.java))

        assertEquals(listOf("Ada", "Bob"), names)
        assertEquals(2L, count)
    }

    @Test
    fun `one row or none is asked of the fixture too, and more than one is refused as the store would refuse it`() {
        fixtures.execute(statement("CREATE (:Human {id: 'ada'}), (:Human {id: 'bob'})"))

        assertEquals("ada", fixtures.maybeGetOne(statement("MATCH (h:Human {id: 'ada'}) RETURN h.id").transform(String::class.java)))
        assertNull(fixtures.maybeGetOne(statement("MATCH (h:Human {id: 'nobody'}) RETURN h.id").transform(String::class.java)))
        assertFailsWith<NoSuchElementException> { fixtures.getOne(statement("MATCH (h:Human {id: 'nobody'}) RETURN h.id").transform(String::class.java)) }
        assertFailsWith<IllegalArgumentException> { fixtures.getOne(statement("MATCH (h:Human) RETURN h.id").transform(String::class.java)) }
    }

    @Test
    fun `a statement in the store's own language is handed to the store as it is`() {
        val sql = listOf(
            "SELECT COUNT(*) FROM g_node",
            "CREATE INDEX g_ix ON g_node (nid)",
            "WITH roots AS (SELECT nid FROM g_node) SELECT COUNT(*) FROM roots",
            "BEGIN NULL; END;",
        )

        assertEquals(listOf("from the store"), fixtures.query(statement(sql[0]).transform(String::class.java)))
        sql.drop(1).forEach { fixtures.execute(statement(it)) }

        assertEquals(sql, handedOn)
    }

    @Test
    fun `the statement that empties a graph on the graph engines empties the two tables, the relationships first`() {
        fixtures.execute(statement("CREATE (:Human {id: 'ada'})-[:KNOWS]->(:Human {id: 'bob'})"))

        fixtures.execute(statement("  MATCH (n) DETACH DELETE n  "))

        assertEquals(listOf("DELETE FROM g_edge", "DELETE FROM g_node"), handedOn)
        assertTrue(fake.nodes.isEmpty() && fake.edges.isEmpty())
    }

    @Test
    fun `a clear needs a graph to name the tables by`() {
        val nameless = object : PersistenceManager by fake {
            override val graphName: String? = null
        }

        assertEquals("The manager names no graph.", assertFailsWith<IllegalArgumentException> { OracleCypherFixtures.clear(nameless) }.message)
    }

    @Test
    fun `a fixture run on its own gives the rows it returns, whatever space it is written in`() {
        OracleCypherFixtures.run(fake, "\n  CREATE (:Human {id: \$id})\n", mapOf("id" to "ada"))

        assertEquals(listOf<Any?>("ada"), OracleCypherFixtures.run(fake, "  MATCH (h:Human) RETURN h.id  "))
        assertEquals(emptyList(), OracleCypherFixtures.run(fake, "MATCH (h:Human {id: 'nobody'}) RETURN h.id"))
    }

    @Test
    fun `what a fixture does not read is refused, and not handed to the store to fail there`() {
        assertFailsWith<IllegalArgumentException> { fixtures.query(statement("MATCH (h:Human) RETURN h.id LIMIT 1").transform(String::class.java)) }
        assertTrue(handedOn.isEmpty())
    }
}
