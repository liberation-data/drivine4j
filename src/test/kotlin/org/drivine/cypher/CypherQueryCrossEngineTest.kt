package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
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
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Cypher statement written for a graph engine runs on a SQL store, compiled to its SQL, and gives the
 * same rows: the same statements are run on Neo4j and on Postgres over one graph, and give what each
 * test says.
 */
abstract class CypherQueryContract {

    abstract val pm: PersistenceManager

    /** Writes the graph a Cypher `CREATE` describes. */
    abstract fun seed(cypher: String)

    /** Removes the graph the tests wrote. */
    abstract fun clear()

    /** Whether the statement under test is compiled to SQL: what only compiling refuses is asked of it alone. */
    open val compiles: Boolean = false

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    @BeforeEach
    fun graph() {
        clear()
        seed(
            """
            CREATE (keanu:Person:CyTest {name: 'Keanu', born: 1964}),
                   (carrie:Person:CyTest {name: 'Carrie', born: 1967}),
                   (laurence:Person:CyTest {name: 'Laurence', born: 1961}),
                   (hugo:Person:CyTest {name: 'Hugo'}),
                   (matrix:Movie:CyTest {title: 'The Matrix', released: 1999, tagline: 'Welcome to the Real World'}),
                   (reloaded:Movie:CyTest {title: 'The Matrix Reloaded', released: 2003}),
                   (johnny:Movie:CyTest {title: 'Johnny Mnemonic', released: 1995}),
                   (text:Code:CyTest {code: '7'}),
                   (number:Code:CyTest {code: 7}),
                   (keanu)-[:ACTED_IN {role: 'Neo'}]->(matrix),
                   (keanu)-[:ACTED_IN {role: 'Neo'}]->(reloaded),
                   (keanu)-[:ACTED_IN {role: 'Johnny'}]->(johnny),
                   (carrie)-[:ACTED_IN {role: 'Trinity'}]->(matrix),
                   (carrie)-[:ACTED_IN {role: 'Trinity'}]->(reloaded),
                   (laurence)-[:ACTED_IN {role: 'Morpheus'}]->(matrix),
                   (keanu)-[:KNOWS]->(carrie),
                   (laurence)-[:KNOWS]->(keanu)
            """
        )
    }

    @Test
    fun `a node is returned as its properties, and a property as its value`() {
        assertEquals(
            listOf(mapOf("name" to "Keanu", "born" to 1964L)),
            query("MATCH (p:Person {name: \$name}) RETURN properties(p)", mapOf("name" to "Keanu")),
        )
        assertEquals(
            listOf(mapOf("name" to "Keanu", "born" to 1964L)),
            query("MATCH (p:Person) WHERE p.name = 'Keanu' RETURN p"),
        )
        assertEquals(listOf("Carrie", "Hugo", "Keanu", "Laurence"), query("MATCH (p:Person) RETURN p.name ORDER BY p.name"))
    }

    @Test
    fun `a value compares as JSON compares it, so a text of digits is not the number`() {
        assertEquals(listOf("7"), query("MATCH (c:Code) WHERE c.code = '7' RETURN c.code"))
        assertEquals(listOf(7L), query("MATCH (c:Code) WHERE c.code = 7 RETURN c.code"))
        assertEquals(listOf("Carrie", "Keanu"), query("MATCH (p:Person) WHERE p.born >= \$year RETURN p.name ORDER BY p.name", mapOf("year" to 1964)))
        assertEquals(listOf("Hugo"), query("MATCH (p:Person) WHERE p.born IS NULL RETURN p.name"))
        assertEquals(listOf("Keanu", "Laurence"), query("MATCH (p:Person) WHERE p.name STARTS WITH 'K' OR p.name ENDS WITH 'ence' RETURN p.name ORDER BY p.name"))
        assertEquals(listOf("Carrie", "Hugo"), query("MATCH (p:Person) WHERE p.name IN ['Carrie', 'Hugo'] RETURN p.name ORDER BY p.name"))
    }

    @Test
    fun `a relationship is read in its direction, or either way, and of any of its types`() {
        assertEquals(listOf("Carrie"), query("MATCH (:Person {name: 'Keanu'})-[:KNOWS]->(p) RETURN p.name"))
        assertEquals(listOf("Laurence"), query("MATCH (:Person {name: 'Keanu'})<-[:KNOWS]-(p) RETURN p.name"))
        assertEquals(listOf("Carrie", "Laurence"), query("MATCH (:Person {name: 'Keanu'})-[:KNOWS]-(p) RETURN p.name ORDER BY p.name"))
        assertEquals(
            listOf("Morpheus", "Neo", "Trinity"),
            query("MATCH (p:Person)-[r:ACTED_IN]->(m:Movie {title: 'The Matrix'}) RETURN r.role ORDER BY r.role"),
        )
        assertEquals(listOf("Keanu"), query("MATCH (p:Person)-[r:ACTED_IN {role: \$role}]->(:Movie {title: 'The Matrix'}) RETURN p.name", mapOf("role" to "Neo")))
        assertEquals(listOf("Keanu", "Laurence"), query("MATCH (p:Person)-[:KNOWS|ACTED_IN]->(:Person) RETURN DISTINCT p.name ORDER BY p.name"))
    }

    @Test
    fun `two relationships of one match are two relationships`() {
        // Keanu's co-actors: the same ACTED_IN is not read for both sides.
        assertEquals(
            listOf("Carrie", "Carrie", "Laurence"),
            query("MATCH (a:Person {name: 'Keanu'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(other) RETURN other.name ORDER BY other.name"),
        )
    }

    @Test
    fun `an optional match keeps a row with null where it finds nothing`() {
        assertEquals(
            listOf(listOf("Carrie", null), listOf("Hugo", null), listOf("Keanu", "Carrie"), listOf("Laurence", "Keanu")),
            query("MATCH (p:Person) OPTIONAL MATCH (p)-[:KNOWS]->(f) RETURN p.name, f.name ORDER BY p.name"),
        )
    }

    @Test
    fun `a with aggregates, filters, orders and pages`() {
        assertEquals(
            listOf(mapOf("name" to "Keanu", "movies" to 3L), mapOf("name" to "Carrie", "movies" to 2L)),
            query(
                """
                MATCH (p:Person)-[:ACTED_IN]->(m:Movie)
                WITH p, count(m) AS movies
                WHERE movies > 1
                RETURN { name: p.name, movies: movies }
                ORDER BY movies DESC
                """
            ),
        )
        assertEquals(listOf("Johnny Mnemonic", "The Matrix"), query("MATCH (m:Movie) RETURN m.title ORDER BY m.title LIMIT 2"))
        assertEquals(listOf("The Matrix Reloaded"), query("MATCH (m:Movie) RETURN m.title ORDER BY m.title SKIP \$offset LIMIT 5", mapOf("offset" to 2)))
        assertEquals(listOf("Neo", "Johnny", "Trinity", "Morpheus").sorted(), query("MATCH ()-[r:ACTED_IN]->() RETURN DISTINCT r.role ORDER BY r.role"))
    }

    @Test
    fun `a with that aggregates groups by the variables it keeps`() {
        val rows = query(
            """
            MATCH (p:Person)-[:ACTED_IN]->(m:Movie)
            WITH p, collect(m.title) AS titles, min(m.released) AS first, sum(m.released) AS years
            RETURN { person: p.name, titles: titles, first: first, years: years }
            ORDER BY p.name
            """
        )
        @Suppress("UNCHECKED_CAST")
        val byPerson = rows.associate { row -> (row as Map<String, Any?>)["person"] to row }
        assertEquals(listOf("Carrie", "Keanu", "Laurence"), byPerson.keys.toList())
        assertEquals(setOf("The Matrix", "The Matrix Reloaded", "Johnny Mnemonic"), ((byPerson["Keanu"] as Map<*, *>)["titles"] as List<*>).toSet())
        assertEquals(1995L, (byPerson["Keanu"] as Map<*, *>)["first"])
        assertEquals(1999L + 2003L + 1995L, (byPerson["Keanu"] as Map<*, *>)["years"])
    }

    @Test
    fun `a returned map keeps its keys in the order written`() {
        val row = query("MATCH (p:Person {name: 'Keanu'}) RETURN { zebra: p.name, apple: p.born, mango: 'm' }").single() as Map<*, *>
        assertEquals(listOf("zebra", "apple", "mango"), row.keys.toList())
    }

    @Test
    fun `a pattern comprehension and a map projection read a view in one statement`() {
        val row = query(
            """
            MATCH (actor:Person {name: ${'$'}name})
            WITH actor, [(actor)-[:ACTED_IN]->(movie:Movie) | movie {.title, .released}] AS movies
            RETURN { actor: actor.name, movies: movies }
            """,
            mapOf("name" to "Carrie"),
        ).single() as Map<*, *>
        assertEquals("Carrie", row["actor"])
        assertEquals(
            setOf(mapOf("title" to "The Matrix", "released" to 1999L), mapOf("title" to "The Matrix Reloaded", "released" to 2003L)),
            (row["movies"] as List<*>).toSet(),
        )
        assertEquals(listOf(listOf<Any?>()), query("MATCH (p:Person {name: 'Hugo'}) RETURN [(p)-[:ACTED_IN]->(m) | m.title]"))
    }

    @Test
    fun `a pattern, an exists, a label and a list comprehension are conditions and values`() {
        assertEquals(listOf("Carrie", "Keanu", "Laurence"), query("MATCH (p:Person) WHERE (p)-[:ACTED_IN]->(:Movie) RETURN p.name ORDER BY p.name"))
        assertEquals(listOf("Hugo"), query("MATCH (p:Person) WHERE NOT EXISTS { (p)-[:ACTED_IN]->() } RETURN p.name"))
        assertEquals(listOf("Keanu"), query("MATCH (p:Person) WHERE p:CyTest AND p.name = 'Keanu' RETURN p.name"))
        assertEquals(listOf(listOf(2L, 4L)), query("RETURN [x IN [1, 2, 3, 4] WHERE x % 2 = 0 | x]"))
        assertEquals(listOf(true), query("RETURN any(x IN [1, 2, 3] WHERE x > 2)"))
        assertEquals(listOf(listOf("Person", "CyTest").sorted()), query("MATCH (p:Person {name: 'Keanu'}) RETURN labels(p)").map { (it as List<*>).map(Any?::toString).sorted() })
    }

    @Test
    fun `arithmetic and functions work on values, a whole number divided by one a whole number`() {
        assertEquals(listOf(mapOf("sum" to 7L, "div" to 3L, "real" to 3.5, "text" to "ab", "upper" to "KEANU", "size" to 5L)), query(
            "MATCH (p:Person {name: 'Keanu'}) RETURN { sum: 3 + 4, div: 7 / 2, real: 7.0 / 2, text: 'a' + 'b', upper: toUpper(p.name), size: size(p.name) }"
        ))
        assertEquals(listOf("Laurence"), query("MATCH (p:Person) WHERE p.born IS NOT NULL RETURN p.name ORDER BY p.born LIMIT 1"))
        assertEquals(listOf(4L), query("MATCH (p:Person) RETURN count(*)"))
        assertEquals(listOf(3L), query("MATCH (p:Person) RETURN count(p.born)"))
    }

    @Test
    fun `an item that aggregates and names another variable outside its aggregates is refused, as Cypher refuses it`() {
        val failure = assertThrows<DrivineException> {
            query("MATCH (p:Person)-[:ACTED_IN]->(m:Movie) RETURN { person: p.name, titles: collect(m.title) }")
        }
        // Neo4j says "implicit grouping"; the compiler says the variable is outside its aggregates.
        val message = failure.message.orEmpty()
        assertTrue("implicit grouping" in message || "outside its aggregates" in message, message)
        // With the variable an item of its own, it is grouped by.
        assertEquals(
            listOf(listOf(mapOf("name" to "Laurence", "born" to 1961L), listOf("The Matrix"))),
            query("MATCH (p:Person {name: 'Laurence'})-[:ACTED_IN]->(m:Movie) RETURN p, collect(m.title)"),
        )
    }

    @Test
    fun `cypher that is not compiled yet is refused by name`() {
        if (!compiles) return
        assertEquals(
            "A variable-length relationship (*) is Cypher, and does not run on Oracle or Postgres yet.",
            assertThrows<DrivineException> { query("MATCH (a:Person)-[:KNOWS*1..2]->(b) RETURN b.name") }.message,
        )
    }
}

class CypherQueryNeo4jTest : CypherQueryContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-cypher", type = DatabaseType.NEO4J,
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

    override fun seed(cypher: String) = manager.execute(QuerySpecification.withStatement(cypher.trimIndent()))

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n:CyTest) DETACH DELETE n"))
}

@PostgresTests
class CypherQueryPostgresTest : CypherQueryContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun seed(cypher: String) = PostgresTestDatabase.seed(manager, cypher)

    override fun clear() = PostgresTestDatabase.clear(manager)
}
