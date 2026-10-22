package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertTrue

/**
 * A Cypher statement that writes runs on a SQL store, compiled to a procedure of its SQL, and leaves the
 * graph a graph engine leaves: the same statements are run on Neo4j, Postgres and Oracle, and read back.
 */
abstract class CypherWriteContract {

    abstract val pm: PersistenceManager

    /** Removes the graph the tests wrote. */
    abstract fun clear()

    /** Whether the statement under test is compiled to SQL: what only compiling refuses is asked of it alone. */
    open val compiles: Boolean = false

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    @BeforeEach
    fun graph() = clear()

    @Test
    fun `a create makes its nodes and relationships, and returns what it made`() {
        assertEquals(
            listOf(listOf("Ada", "Bob")),
            query("CREATE (a:CyWrite:Person {name: 'Ada', tags: ['x', 'y'], none: null})-[:KNOWS {since: 2020}]->(b:CyWrite {name: 'Bob'}) RETURN a.name, b.name"),
        )
        assertEquals(
            listOf(mapOf("from" to "Ada", "since" to 2020L, "to" to "Bob", "tags" to listOf("x", "y"), "person" to true, "none" to true)),
            query(
                """
                MATCH (a:CyWrite)-[r:KNOWS]->(b:CyWrite)
                RETURN {from: a.name, since: r.since, to: b.name, tags: a.tags, person: a:Person, none: a.none IS NULL}
                """
            ),
        )
        // A pattern of nodes bound before it makes only its relationship.
        query("MATCH (a:CyWrite {name: 'Ada'}), (b:CyWrite {name: 'Bob'}) CREATE (b)-[:KNOWS]->(a)")
        assertEquals(listOf("Ada", "Bob"), query("MATCH (n:CyWrite)-[:KNOWS]->(:CyWrite) RETURN n.name ORDER BY n.name"))
        assertEquals(listOf(2L), query("MATCH (n:CyWrite) RETURN count(n)"))
    }

    @Test
    fun `a create of a node from a map leaves out its nulls, and a write with no return returns no rows`() {
        assertEquals(
            emptyList(),
            query("CREATE (n:CyWrite \$props)", mapOf("props" to mapOf("name" to "Ada", "age" to 36, "gone" to null))),
        )
        assertEquals(listOf(mapOf("name" to "Ada", "age" to 36L)), query("MATCH (n:CyWrite) RETURN properties(n)"))
    }

    @Test
    fun `a merge finds what is there, or what an earlier row made, and makes it once`() {
        assertEquals(
            listOf("x", "y", "x"),
            query(
                """
                UNWIND ['x', 'y', 'x'] AS k
                MERGE (n:CyWrite {key: k})
                ON CREATE SET n.made = true
                ON MATCH SET n.seen = coalesce(n.seen, 0) + 1
                RETURN n.key
                """
            ),
        )
        assertEquals(
            listOf(mapOf("key" to "x", "made" to true, "seen" to 1L), mapOf("key" to "y", "made" to true)),
            query("MATCH (n:CyWrite) RETURN properties(n) ORDER BY n.key"),
        )
        // A relationship merged between two nodes is made once.
        repeat(2) { query("MATCH (x:CyWrite {key: 'x'}), (y:CyWrite {key: 'y'}) MERGE (x)-[r:NEXT]->(y) ON CREATE SET r.made = true") }
        assertEquals(listOf(true), query("MATCH (:CyWrite {key: 'x'})-[r:NEXT]->(:CyWrite {key: 'y'}) RETURN r.made"))
    }

    @Test
    fun `a merge of a node keyed by a parameter's property, then set from the parameter, as drivine's repositories write`() {
        val task = mapOf("id" to "t1", "title" to "Write", "tags" to listOf("a"), "done" to false)
        assertEquals(
            listOf(task),
            query("MERGE (t:CyWrite {id: \$task.id}) SET t += \$task RETURN properties(t)", mapOf("task" to task)),
        )
        assertEquals(
            listOf(mapOf("id" to "t1", "title" to "Written", "tags" to listOf("a"), "done" to true)),
            query("MERGE (t:CyWrite {id: \$task.id}) SET t += \$task RETURN properties(t)", mapOf("task" to mapOf("id" to "t1", "title" to "Written", "done" to true))),
        )
        assertEquals(listOf(1L), query("MATCH (t:CyWrite) RETURN count(t)"))
    }

    @Test
    fun `set writes a property, replaces or adds a map, a null removing its key, and a label, and remove takes them away`() {
        query("CREATE (:CyWrite {id: 1, a: 'a', b: 'b'})")
        assertEquals(
            listOf(mapOf("id" to 1L, "a" to "A", "c" to 3L)),
            query("MATCH (n:CyWrite {id: 1}) SET n.a = toUpper(n.a), n.c = 3, n.b = null RETURN properties(n)"),
        )
        assertEquals(
            listOf(mapOf("id" to 1L, "a" to "A", "d" to "d")),
            query("MATCH (n:CyWrite {id: 1}) SET n += \$more RETURN properties(n)", mapOf("more" to mapOf("c" to null, "d" to "d"))),
        )
        assertEquals(
            listOf(mapOf("id" to 2L)),
            query("MATCH (n:CyWrite {id: 1}) SET n = \$whole RETURN properties(n)", mapOf("whole" to mapOf("id" to 2, "gone" to null))),
        )
        assertEquals(listOf(listOf(true, true)), query("MATCH (n:CyWrite {id: 2}) SET n:Done:Seen RETURN n:Done, n:Seen"))
        assertEquals(listOf(true), query("MATCH (n:CyWrite {id: 2}) SET n:Done RETURN n:Done"))
        assertEquals(
            listOf(listOf(false, true, null)),
            query("MATCH (n:CyWrite {id: 2}) REMOVE n:Done, n.id SET n.x = 1 REMOVE n.x RETURN n:Done, n:Seen, n.id"),
        )
    }

    @Test
    fun `delete takes a relationship, and a node with no relationships, and detach delete a node with its relationships`() {
        query("CREATE (:CyWrite {name: 'a'})-[:R]->(:CyWrite {name: 'b'})-[:R]->(:CyWrite {name: 'c'})")
        // A node with relationships is not deleted, and the statement leaves nothing it did behind.
        assertThrows<Exception> { query("CREATE (:CyWrite {name: 'temp'}) WITH 1 AS one MATCH (n:CyWrite {name: 'b'}) DELETE n") }
        assertEquals(listOf("a", "b", "c"), query("MATCH (n:CyWrite) RETURN n.name ORDER BY n.name"))
        // The relationships a clause deletes are gone before its nodes are.
        query("MATCH (a:CyWrite {name: 'a'})-[r:R]->(b) DELETE r, a")
        assertEquals(listOf("b", "c"), query("MATCH (n:CyWrite) RETURN n.name ORDER BY n.name"))
        query("MATCH (n:CyWrite {name: 'b'}) DETACH DELETE n")
        assertEquals(listOf("c"), query("MATCH (n:CyWrite) RETURN n.name"))
        assertEquals(emptyList(), query("MATCH (:CyWrite)-[r]-() RETURN r"))
    }

    @Test
    fun `a read after a write sees what it wrote`() {
        query("CREATE (:CyWrite {name: 'Ada'})")
        assertEquals(
            listOf(listOf("Notes", 1L)),
            query(
                """
                MATCH (a:CyWrite {name: 'Ada'})
                CREATE (a)-[:WROTE]->(:CyWrite:Paper {title: ${'$'}title})
                WITH a
                MATCH (a)-[:WROTE]->(p:Paper)
                RETURN p.title, count(*)
                """,
                mapOf("title" to "Notes"),
            ),
        )
    }

    @Test
    fun `datetime's epoch milliseconds and timestamp are the moment the statement runs`() {
        val before = System.currentTimeMillis() - 60_000
        val (millis, stamp) = query("CREATE (n:CyWrite) SET n.at = datetime().epochMillis RETURN [n.at, timestamp()]").single() as List<*>
        val after = System.currentTimeMillis() + 60_000
        assertTrue((millis as Long) in before..after, "$millis")
        assertTrue((stamp as Long) in before..after, "$stamp")
    }

    @Test
    fun `a write cypher does not compile yet is refused by name`() {
        if (!compiles) return
        assertEquals(
            "A variable-length relationship in CREATE or MERGE is Cypher, and does not run on Oracle or Postgres yet.",
            assertThrows<DrivineException> { query("MATCH (a:CyWrite), (b:CyWrite) MERGE (a)-[:R*1..2]->(b)") }.message,
        )
        assertEquals(
            "A relationship that CREATE makes has a direction.",
            assertThrows<DrivineException> { query("CREATE (:CyWrite)-[:R]-(:CyWrite)") }.message,
        )
    }
}

class CypherWriteNeo4jTest : CypherWriteContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-cypher-write", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n:CyWrite) DETACH DELETE n"))
}

@PostgresTests
class CypherWritePostgresTest : CypherWriteContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = PostgresTestDatabase.clear(manager)
}

@OracleTests
class CypherWriteOracleTest : CypherWriteContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = OracleTestDatabase.clear(manager)
}
