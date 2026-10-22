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
    fun `lists, conversions, text tests and aggregates give a graph engine's values`() {
        assertEquals(
            listOf(
                mapOf(
                    "whole" to 2.0, "last" to 3L, "byParam" to 2L, "head" to 1L, "joined" to listOf(1L, 2L, 3L, 4L), "appended" to listOf(1L, 2L, 3L),
                    "float" to 2.5, "integer" to 7L, "string" to "7", "in" to true, "starts" to true, "ends" to true, "holds" to true,
                    "coalesced" to "x", "case" to "big",
                )
            ),
            query(
                """
                WITH [1, 2, 3] AS xs
                RETURN {
                    whole: 2.0, last: xs[-1], byParam: xs[${'$'}i], head: head(xs), joined: xs + [4], appended: [1, 2] + 3,
                    float: toFloat('2.5'), integer: toInteger('7.9'), string: toString(7), in: 2 IN xs,
                    starts: 'Keanu' STARTS WITH 'Ke', ends: 'Keanu' ENDS WITH 'nu', holds: 'Keanu' CONTAINS 'an',
                    coalesced: coalesce(null, 'x'), case: CASE WHEN size(xs) > 2 THEN 'big' ELSE 'small' END
                }
                """,
                mapOf("i" to 1),
            ),
        )
        assertEquals<List<Any?>>(
            listOf(mapOf("avg" to 1997.0, "latest" to 2003L, "distinct" to listOf("Neo", "Trinity"))),
            query(
                """
                MATCH (:Person)-[r:ACTED_IN]->(m:Movie) WHERE m.title STARTS WITH 'The Matrix' AND r.role <> 'Morpheus'
                RETURN { avg: avg(m.released) - 4, latest: max(m.released), distinct: collect(DISTINCT r.role) }
                """
            ).map { row -> (row as Map<*, *>).mapValues { (key, value) -> if (key == "distinct") (value as List<*>).map { it as String }.sorted() else value } },
        )
    }

    @Test
    fun `substring, left, right and replace work on text, counted from 0, and randomUUID gives a new UUID each time`() {
        assertEquals(
            listOf(listOf("trix", "tr", "Ma", "rix", "The Matrix", "The M_trix")),
            query("MATCH (m:Movie {title: 'The Matrix'}) RETURN [substring(m.title, 6), substring(m.title, 6, 2), left('Matrix', 2), right('Matrix', 3), right(m.title, 50), replace(m.title, 'a', '_')]"),
        )
        val (a, b) = query("UNWIND [1, 2] AS x RETURN randomUUID()").map { it as String }
        assertTrue(a != b && Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a), "$a $b")
    }

    @Test
    fun `keys gives a map's keys, and a node's, whatever they are called`() {
        assertEquals(setOf("b", "a", "odd key", "q\"t"), (query("RETURN keys({b: 1, a: {x: 2}, `odd key`: [1], `q\"t`: null})").single() as List<*>).toSet())
        assertEquals(setOf("name", "born"), query("MATCH (p:Person {name: 'Keanu'}) UNWIND keys(p) AS k RETURN k").toSet())
        assertEquals(listOf(emptyList<String>()), query("RETURN keys({})"))
    }

    @Test
    fun `range gives whole numbers, and reduce folds a list from its first item`() {
        assertEquals(listOf(listOf(listOf(0L, 1L, 2L, 3L), listOf(10L, 7L, 4L), emptyList<Long>())), query("RETURN [range(0, 3), range(10, 4, -3), range(1, 0)]"))
        if (pm.type == DatabaseType.ORACLE) return
        assertEquals(listOf(6L), query("RETURN reduce(total = 0, x IN [1, 2, 3] | total + x)"))
        assertEquals(listOf(null), query("RETURN reduce(total = 0, x IN null | total + x)"))
        assertEquals(
            listOf("Carrie,Hugo,Keanu,Laurence"),
            query(
                """
                MATCH (p:Person) WITH p ORDER BY p.name
                WITH collect(p.name) AS names
                RETURN reduce(s = '', n IN names | s + CASE WHEN s = '' THEN '' ELSE ',' END + n)
                """
            ),
        )
        assertEquals(listOf(14L), query("RETURN reduce(dot = 0, i IN range(0, size(\$v) - 1) | dot + \$v[i] * \$v[i])", mapOf("v" to listOf(1, 2, 3))))
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
    fun `a variable-length relationship reads each length of its range`() {
        val from = "MATCH (:Person {name: 'Laurence'})"
        assertEquals(listOf("Carrie", "Keanu"), query("$from-[:KNOWS*]->(b) RETURN b.name ORDER BY b.name"))
        assertEquals(listOf("Carrie"), query("$from-[:KNOWS*2]->(b) RETURN b.name"))
        assertEquals(listOf("Keanu"), query("$from-[:KNOWS*..1]->(b) RETURN b.name"))
        assertEquals(listOf("Carrie"), query("$from-[:KNOWS*2..]->(b) RETURN b.name"))
        assertEquals(listOf("Keanu", "Laurence"), query("$from-[:KNOWS*0..1]->(b) RETURN b.name ORDER BY b.name"))
        assertEquals(listOf<Any?>(), query("$from-[:KNOWS*3..]->(b) RETURN b.name"))
    }

    @Test
    fun `a variable-length relationship reads its direction, types and properties, and conditions on its end`() {
        // Pinned at its far end: walked from there.
        assertEquals(listOf("Keanu", "Laurence"), query("MATCH (a)-[:KNOWS*]->(:Person {name: 'Carrie'}) RETURN a.name ORDER BY a.name"))
        // Either way, and not back along the relationship it came by.
        assertEquals(listOf("Laurence"), query("MATCH (:Person {name: 'Carrie'})-[:KNOWS*2]-(b) RETURN b.name"))
        assertEquals(
            listOf("Johnny Mnemonic", "The Matrix", "The Matrix Reloaded"),
            query("MATCH (:Person {name: 'Laurence'})-[:KNOWS|ACTED_IN*2]->(m:Movie) RETURN DISTINCT m.title ORDER BY m.title"),
        )
        assertEquals(
            listOf("The Matrix", "The Matrix Reloaded"),
            query("MATCH (:Person {name: 'Keanu'})-[:ACTED_IN*1 {role: 'Neo'}]->(m) RETURN m.title ORDER BY m.title"),
        )
        assertEquals(listOf("Carrie"), query("MATCH (:Person {name: 'Laurence'})-[:KNOWS*]->(b) WHERE b.born > 1965 RETURN b.name"))
        assertEquals(listOf("Laurence"), query("MATCH (p:Person) WHERE (p)-[:KNOWS*2]->() RETURN p.name"))
    }

    @Test
    fun `a variable-length relationship does not take a relationship its match took already`() {
        assertEquals(
            query("MATCH (:Person {name: 'Keanu'})-[:ACTED_IN]->(m)<-[:ACTED_IN]-(other) RETURN other.name ORDER BY other.name"),
            query("MATCH (:Person {name: 'Keanu'})-[:ACTED_IN]->(m)<-[:ACTED_IN*1]-(other) RETURN other.name ORDER BY other.name"),
        )
        // Two walks of one match share no relationship: Laurence to Keanu is taken by one of them only.
        assertEquals(
            listOf(listOf("Laurence", "Carrie")),
            query("MATCH (a)-[:KNOWS*1]->(:Person {name: 'Keanu'})-[:KNOWS*1]->(c) RETURN [a.name, c.name]"),
        )
        assertEquals(listOf<Any?>(), query("MATCH (:Person {name: 'Laurence'})-[:KNOWS*1]->(k)<-[:KNOWS*1]-(:Person {name: 'Laurence'}) RETURN k.name"))
    }

    @Test
    fun `a path gives its nodes and relationships in order, and its length`() {
        assertEquals(
            listOf(listOf(listOf("Laurence", "Keanu", "Carrie"), 2L, 2L)),
            query("MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->(:Person) RETURN [[n IN nodes(p) | n.name], size(relationships(p)), length(p)]"),
        )
        // A path of fixed and variable-length parts, walked from its pinned far end, in the pattern's order.
        assertEquals(
            listOf(listOf("Laurence", "Keanu", "Carrie", "The Matrix")),
            query("MATCH p = (a)-[:KNOWS*]->(:Person {name: 'Carrie'})-[:ACTED_IN]->(m {title: 'The Matrix'}) WHERE a.name = 'Laurence' RETURN [n IN nodes(p) | coalesce(n.name, n.title)]"),
        )
        assertEquals(
            listOf(listOf("Neo", "Trinity")),
            query("MATCH p = (:Person {name: 'Keanu'})-[:ACTED_IN]->(:Movie {title: 'The Matrix'})<-[:ACTED_IN]-(:Person {name: 'Carrie'}) RETURN [r IN relationships(p) | r.role]"),
        )
        assertEquals(listOf(0L), query("MATCH p = (:Person {name: 'Hugo'}) RETURN length(p)"))
        // A path is kept by a WITH, and is null where an optional match finds none.
        assertEquals(
            listOf(listOf("Hugo", null), listOf("Keanu", 1L)),
            query(
                """
                MATCH (a:Person) WHERE a.name IN ['Hugo', 'Keanu']
                OPTIONAL MATCH p = (a)-[:KNOWS]->(:Person)
                WITH a, p
                RETURN [a.name, length(p)] ORDER BY a.name
                """
            ),
        )
    }

    @Test
    fun `a variable on a variable-length relationship is the list of its relationships`() {
        assertEquals(
            listOf(listOf("Neo", "Trinity")),
            query("MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN*2]-(:Person {name: 'Carrie'}) WHERE r[0].role = 'Neo' AND r[1].role = 'Trinity' RETURN [x IN r | x.role]").distinct(),
        )
    }

    @Test
    fun `cypher that is not compiled yet is refused by name`() {
        if (!compiles) return
        assertEquals(
            "A path is not a value drivine gives the same on each store: use nodes(p), relationships(p) and length(p).",
            assertThrows<DrivineException> { query("MATCH p = (a:Person)-[:KNOWS*1..2]->(b) RETURN p") }.message,
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

@OracleTests
class CypherQueryOracleTest : CypherQueryContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun seed(cypher: String) = OracleTestDatabase.seed(manager, cypher)

    override fun clear() = OracleTestDatabase.clear(manager)

    @Test
    fun `reduce is refused, for Oracle's recursive queries read nothing of the row they are in`() {
        assertEquals(
            "reduce() does not run on Oracle: its recursive queries read nothing of the row they are in.",
            assertThrows<DrivineException> { pm.query(QuerySpecification.withStatement(CypherStatement("RETURN reduce(t = 0, x IN [1] | t + x)"))) }.message,
        )
    }

}
