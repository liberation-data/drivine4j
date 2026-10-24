package org.drivine.cypher

import org.drivine.connection.DatabaseType
import org.drivine.cypher.spec.CypherSpec
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
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
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertTrue

/**
 * A Cypher statement that writes runs on a SQL store, compiled to a procedure of its SQL, and leaves the
 * graph a graph engine leaves: the statements of the spec's `cypher/writes.yaml` are run on Neo4j, Postgres
 * and Oracle, and read back.
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

    @TestFactory
    fun spec() = CypherSpec.load("writes.yaml").tests(CypherSpec.storeOf(pm.type), before = ::clear) { step -> query(step.cypher, step.parameters) }

    @Test
    fun `CALLs that write, nested, give each row its own variables with the nodes they return`() {
        // Not in the spec yet: a caller row at each depth, merged with a node returned from below it.
        assertEquals(
            listOf(listOf(1L, 1L, 10L), listOf(2L, 2L, 20L)),
            query(
                "UNWIND [1, 2] AS x CALL { WITH x CREATE (a:CyWrite {x: x}) WITH a, x " +
                    "CALL { WITH a, x CREATE (b:CyWrite {y: x * 10}) RETURN b } RETURN a, b } RETURN [x, a.x, b.y]"
            ),
        )
        assertEquals(
            listOf(listOf(1L, 1L, 10L), listOf(2L, 2L, 20L)),
            query("UNWIND [1, 2] AS i CALL { WITH i CREATE (n:CyWrite {i: i}) RETURN n } SET n.j = i * 10 RETURN [i, n.i, n.j] ORDER BY i"),
        )
        assertEquals(
            listOf(listOf(2020L, 3L)),
            query("WITH date('2020-01-02') AS d CALL { CREATE (n:CyWrite) RETURN date('2021-03-04') AS e } RETURN [d.year, e.month]"),
        )
    }

    @Test
    fun `a statement of hundreds of creates, each naming nodes made before it, makes them all`() {
        val clauses = (0 until 300).flatMap { i ->
            listOf("CREATE (n$i:CyWrite {i: $i})") + if (i > 0) listOf("CREATE (n${i - 1})-[:NEXT]->(n$i)") else emptyList()
        }
        query(clauses.joinToString("\n"))
        assertEquals(listOf(listOf(300L, 299L)), query("MATCH (n:CyWrite) OPTIONAL MATCH (n)-[r:NEXT]->() RETURN [count(DISTINCT n), count(r)]"))
    }

    @Test
    fun `drivine's stamp is written as a node's property is, and read back as one`() {
        val stamp = Stamps.QUOTED
        query("CREATE (:CyWrite {id: 1, $stamp: 'aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb'})")
        assertEquals(listOf("aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
        query("MATCH (n:CyWrite {id: 1}) SET ${Stamps.setClause("n")}")
        val (token, links) = (query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp").single() as String).split(':')
        assertTrue(token.length == 16 && token != "aaaaaaaaaaaaaaaa" && links == "bbbbbbbbbbbbbbbb", "$token:$links")
        assertEquals(listOf(mapOf("id" to 1L, Stamps.PROPERTY to "$token:$links")), query("MATCH (n:CyWrite {id: 1}) RETURN properties(n)"))
        query("MATCH (n:CyWrite {id: 1}) REMOVE n.$stamp")
        assertEquals(listOf(null), query("MATCH (n:CyWrite {id: 1}) RETURN n.$stamp"))
    }

    @Test
    fun `datetime's epoch milliseconds and timestamp are the moment the statement runs`() {
        val before = System.currentTimeMillis() - 60_000
        val (millis, stamp) = query("CREATE (n:CyWrite) SET n.at = datetime().epochMillis RETURN [n.at, timestamp()]").single() as List<*>
        val after = System.currentTimeMillis() + 60_000
        assertTrue((millis as Long) in before..after, "$millis")
        assertTrue((stamp as Long) in before..after, "$stamp")
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

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

@PostgresTests
class CypherWritePostgresTest : CypherWriteContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = PostgresTestDatabase.clear(manager)

    @Test
    fun `a write in a transaction rolled back leaves nothing, and runs again after`() {
        val connection = PostgresTestDatabase.connections(manager).connect()
        fun query(cypher: String) = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        connection.startTransaction()
        try {
            query("CREATE (:CyWrite {name: 'Ada'})")
            assertEquals(listOf("Ada"), query("MATCH (n:CyWrite) RETURN n.name"))
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
        assertEquals(listOf(0L), pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN count(n)"))))
        // The function the write was compiled to went with the transaction, and is made again.
        pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (:CyWrite {name: 'Ada'})")))
        assertEquals(listOf(1L), pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:CyWrite) RETURN count(n)"))))
    }
}

@OracleTests
class CypherWriteOracleTest : CypherWriteContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun clear() = OracleTestDatabase.clear(manager)

    @Test
    fun `nodes of two labels a transaction has made and not yet committed are found by both`() {
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String) = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        connection.startTransaction()
        try {
            query("CREATE (:CyWrite:Dog {name: 'Buddy'}), (:CyWrite:Cat {name: 'Tom'}), (:CyWrite:Dog {name: 'Max'})")
            assertEquals(listOf("Buddy", "Max"), query("MATCH (d:CyWrite:Dog) RETURN d.name ORDER BY d.name"))
            assertEquals(listOf("Tom"), query("MATCH (c) WHERE c:Cat:CyWrite RETURN c.name"))
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
    }

    @Test
    fun `a walk either way around a cycle finds its rows in a second session too`() {
        // Oracle adapted the plan of the walk's step as the first session ran it, and the second then found no rows (#182).
        pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (a:CyWrite {id: 'a'})-[:R]->(:CyWrite {id: 'b'})-[:R]->(:CyWrite {id: 'c'})-[:R]->(a)")))
        val walk = QuerySpecification.withStatement(CypherStatement("MATCH (:CyWrite {id: 'a'})-[rs:R*]-(n) RETURN [n.id, size(rs)] ORDER BY size(rs), n.id"))
        val rows = listOf(listOf("b", 1L), listOf("c", 1L), listOf("b", 2L), listOf("c", 2L), listOf("a", 3L), listOf("a", 3L))
        val connections = OracleTestDatabase.connections(manager).let { provider -> listOf(provider.connect(), provider.connect()) }
        try {
            val sessions = connections.map { connection ->
                assertEquals<List<Any?>>(rows, connection.query(walk))
                connection.query(QuerySpecification.withStatement("SELECT SYS_CONTEXT('USERENV', 'SID') FROM DUAL")).single()
            }
            assertTrue(sessions[0] != sessions[1], "$sessions")
        } finally {
            connections.forEach { it.release() }
        }
    }
}
