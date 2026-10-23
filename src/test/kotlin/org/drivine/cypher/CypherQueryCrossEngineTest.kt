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
    fun `a whole number is equal to the same number written with a fraction, in a pattern or a condition`() {
        assertEquals(listOf("Keanu"), query("MATCH (p:Person {born: 1964.0}) RETURN p.name"))
        assertEquals(listOf("Keanu"), query("MATCH (p:Person) WHERE p.born = \$born RETURN p.name", mapOf("born" to 1964.0)))
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
        // One that binds nothing new keeps the row too.
        assertEquals(listOf("Hugo"), query("MATCH (p:Person {name: 'Hugo'}) OPTIONAL MATCH (p)-[:KNOWS]->() RETURN p.name"))
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
        assertEquals(listOf("Laurence", "Keanu"), query("MATCH (p:Person) WITH p ORDER BY p.name DESC LIMIT 2 RETURN p.name"))
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
                    "coalesced" to "x", "case" to "big", "keyed" to 2L, "notInteger" to null, "notFloat" to null,
                )
            ),
            query(
                """
                WITH [1, 2, 3] AS xs
                RETURN {
                    whole: 2.0, last: xs[-1], byParam: xs[${'$'}i], head: head(xs), joined: xs + [4], appended: [1, 2] + 3,
                    float: toFloat('2.5'), integer: toInteger('7.9'), string: toString(7), in: 2 IN xs,
                    starts: 'Keanu' STARTS WITH 'Ke', ends: 'Keanu' ENDS WITH 'nu', holds: 'Keanu' CONTAINS 'an',
                    coalesced: coalesce(null, 'x'), case: CASE WHEN size(xs) > 2 THEN 'big' ELSE 'small' END,
                    keyed: {a: 2}['a'], notInteger: toInteger('seven'), notFloat: toFloat('2.5.1')
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
    fun `quantifiers, xor, a case of a subject, powers, negation and text functions give a graph engine's values`() {
        assertEquals(
            listOf(
                mapOf(
                    "all" to true, "none" to false, "single" to true, "xor" to true, "case" to "two", "power" to 8.0,
                    "negated" to -3L, "abs" to 2.5, "trimmed" to "a", "lower" to "ab", "unordered" to null,
                )
            ),
            query(
                """
                WITH [1, 2, 3] AS xs
                RETURN {
                    all: all(x IN xs WHERE x > 0), none: none(x IN xs WHERE x > 2), single: single(x IN xs WHERE x > 2),
                    xor: true XOR false, case: CASE size(xs) - 1 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'many' END,
                    power: 2 ^ 3, negated: -(1 + 2), abs: abs(-2.5), trimmed: trim('  a '), lower: toLower('AB'), unordered: 1 < 'a'
                }
                """
            ),
        )
        // A key or an index an expression gives: Oracle reads no key so.
        if (pm.type == DatabaseType.ORACLE) return
        assertEquals(listOf(listOf(2L, 3L)), query("WITH {a: 2} AS m, [1, 3] AS xs, 'a' AS k, 1 AS i RETURN [m[k], xs[i]]"))
    }

    @Test
    fun `not, xor and is null take the whole condition they are given, and an index the whole list`() {
        assertEquals(listOf("Carrie", "Hugo", "Laurence"), query("MATCH (p:Person) WHERE NOT p.name = 'Keanu' RETURN p.name ORDER BY p.name"))
        assertEquals(listOf(9L), query("MATCH (n:CyTest) WHERE NOT n:Person:Movie RETURN count(*)"))
        assertEquals(listOf("Carrie"), query("MATCH (p:Person) WHERE p.name = 'Keanu' XOR p.born > 1962 RETURN p.name"))
        assertEquals(listOf(true), query("MATCH (p:Person {name: 'Keanu'}) RETURN (p.nick = 'x') IS NULL"))
        assertEquals(listOf(listOf(1L, 2L, 3L)), query("RETURN [1] + [[2, 3]][0]"))
    }

    @Test
    fun `collect of distinct maps and lists keeps each once`() {
        val (maps, lists) = query(
            "MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN]->() RETURN [collect(DISTINCT {role: r.role}), collect(DISTINCT [r.role])]"
        ).single() as List<*>
        assertEquals(setOf(mapOf("role" to "Neo"), mapOf("role" to "Johnny")), (maps as List<*>).toSet())
        assertEquals(2, maps.size)
        assertEquals(setOf(listOf("Neo"), listOf("Johnny")), (lists as List<*>).toSet())
        assertEquals(2, lists.size)
    }

    @Test
    fun `collect leaves out a null, a list's item or a map's entry among them`() {
        assertEquals(listOf(listOf(listOf(1L, 2L), 2L)), query("UNWIND [1, null, 2] AS x RETURN [collect(x), count(x)]"))
        assertEquals(listOf(listOf(1L)), query("UNWIND [1, null, 1] AS x RETURN collect(DISTINCT x)"))
        assertEquals(listOf(listOf(1L, 2L)), query("UNWIND [{a: 1}, {a: null}, {b: 1}, {a: 2}] AS m RETURN collect(m.a)"))
        // Nodes gathered, and read in the projection that gathers them; a node that is null left out.
        assertEquals(listOf(listOf(4L, 0L)), query("MATCH (p:Person) OPTIONAL MATCH (p)-[:KNOWS]->(f:Person {name: 'Nobody'}) RETURN [size(collect(p)), size(collect(f))]"))
    }

    @Test
    fun `collect gathers the rows in the order a with gave them, within each group too`() {
        assertEquals(listOf(listOf(1L, 2L, 3L)), query("UNWIND [3, 1, 2] AS x WITH x ORDER BY x RETURN collect(x)"))
        assertEquals(listOf(listOf(3L, 2L, 1L)), query("UNWIND [3, 1, 2] AS x WITH x ORDER BY x DESC RETURN collect(x)"))
        assertEquals(
            listOf(listOf("The Matrix Reloaded", "The Matrix", "Johnny Mnemonic")),
            query(
                """
                MATCH (p:Person)-[:ACTED_IN]->(m:Movie) WITH p, m ORDER BY m.title DESC
                WITH p.name AS name, collect(m.title) AS titles WHERE name = 'Keanu'
                RETURN titles
                """
            ),
        )
    }

    @Test
    fun `a projection groups by a map, a list or a pattern comprehension it returns, and orders by an aggregate or an item's expression it does not return`() {
        assertEquals(
            listOf(listOf(mapOf("name" to "Keanu"), 3L), listOf(mapOf("name" to "Carrie"), 2L), listOf(mapOf("name" to "Laurence"), 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN {name: p.name} AS who, count(*) AS c ORDER BY c DESC"),
        )
        assertEquals(
            listOf(listOf(mapOf("name" to "Keanu"), 3L), listOf(mapOf("name" to "Carrie"), 2L), listOf(mapOf("name" to "Laurence"), 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p {.name} AS who, count(m) AS c ORDER BY c DESC"),
        )
        assertEquals(
            listOf(listOf(listOf("Keanu"), 1L), listOf(listOf<Any?>(), 2L), listOf(listOf("Carrie"), 3L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN [(p)-[:KNOWS]->(f) | f.name] AS knows, count(*) AS c ORDER BY c"),
        )
        // Gathered in the order the rows came in, though the key is worked out before it is grouped by.
        assertEquals(
            listOf(listOf(0L, listOf("Hugo", "Carrie")), listOf(1L, listOf("Laurence", "Keanu"))),
            query("MATCH (p:Person) WITH p ORDER BY p.name DESC RETURN size([(p)-[:KNOWS]->(f) | f]) AS k, collect(p.name) AS names ORDER BY k"),
        )
        assertEquals(
            listOf(listOf("CARRIE", 1L), listOf("HUGO", 1L), listOf("KEANU", 1L), listOf("LAURENCE", 1L)),
            query("MATCH (p:Person) RETURN toUpper(toLower(trim(toUpper(toLower(trim(toUpper(p.name))))))) AS k, count(*) AS c ORDER BY k"),
        )
        assertEquals(
            listOf(listOf("Keanu", 3L), listOf("Carrie", 2L), listOf("Laurence", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name AS n, count(*) AS c ORDER BY min(m.released), n"),
        )
        // An order of an expression of an item, which is no item.
        assertEquals(
            listOf(listOf(mapOf("name" to "Carrie"), 2L), listOf(mapOf("name" to "Keanu"), 3L), listOf(mapOf("name" to "Laurence"), 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN {name: p.name} AS who, count(*) AS c ORDER BY who.name"),
        )
        assertEquals(
            listOf(listOf(null, 1L), listOf(1967L, 1L), listOf(1964L, 1L), listOf(1961L, 1L)),
            query("MATCH (p:Person) RETURN p.born AS b, count(*) AS c ORDER BY b + 1 DESC"),
        )
    }

    @Test
    fun `a projection groups by a key of a parameter or a subquery, kept distinct, paged, filtered after, or over an optional match`() {
        assertEquals(
            listOf(listOf("Carrie!", 2L), listOf("Keanu!", 3L), listOf("Laurence!", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name + \$x AS k, count(*) AS c ORDER BY k", mapOf("x" to "!")),
        )
        assertEquals(
            listOf(listOf(listOf("Keanu"), 3L), listOf(listOf<Any?>(), 3L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN [x IN \$names WHERE x = p.name] AS k, count(*) AS c ORDER BY size(k) DESC",
                mapOf("names" to listOf("Keanu", "Hugo")),
            ),
        )
        assertEquals(
            listOf(listOf(listOf<Any?>(), 2L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN DISTINCT [(p)-[:KNOWS]->(f) | f.name] AS knows, count(*) AS c ORDER BY c DESC SKIP \$s LIMIT \$l",
                mapOf("s" to 1, "l" to 1),
            ),
        )
        assertEquals(
            listOf(mapOf("k" to listOf("Carrie"), "c" to 3L), mapOf("k" to listOf<Any?>(), "c" to 2L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH [(p)-[:KNOWS]->(f) | f.name] AS knows, count(*) AS c WHERE c > 1 RETURN {k: knows, c: c} ORDER BY c DESC"),
        )
        assertEquals(
            listOf(listOf(listOf("Keanu"), 1L), listOf(listOf<Any?>(), 2L), listOf(listOf("Carrie"), 3L)),
            query("MATCH (p:Person) OPTIONAL MATCH (p)-[:ACTED_IN]->(m) RETURN [(p)-[:KNOWS]->(f) | f.name] AS knows, count(m) AS c ORDER BY c, size(knows)"),
        )
    }

    @Test
    fun `min and max of values of several kinds order them as Cypher does`() {
        assertEquals(listOf(listOf("a", 2.5)), query("UNWIND [1, 'a', 2.5] AS x RETURN [min(x), max(x)]"))
        assertEquals(listOf(listOf(true, 1L)), query("UNWIND [true, 1] AS x RETURN [min(x), max(x)]"))
        assertEquals(listOf(listOf(listOf(1L), 1L)), query("UNWIND [[1], 'a', 1] AS x RETURN [min(x), max(x)]"))
        assertEquals(listOf(listOf("a", "b")), query("UNWIND [null, 'b', 'a'] AS x RETURN [min(x), max(x)]"))
    }

    @Test
    fun `an order puts null last, a map's null entry among them, and first where it descends`() {
        assertEquals(listOf(1L, 2L, null), query("UNWIND [{a: 2}, {a: null}, {a: 1}] AS m RETURN m.a ORDER BY m.a"))
        assertEquals(listOf(null, 2L, 1L), query("UNWIND [{a: 2}, {a: null}, {a: 1}] AS m RETURN m.a ORDER BY m.a DESC"))
        assertEquals(
            listOf(mapOf("born" to 1961L), mapOf("born" to 1964L), mapOf("born" to 1967L), mapOf("born" to null)),
            query("MATCH (p:Person) RETURN DISTINCT p {.born} AS b ORDER BY b.born"),
        )
    }

    @Test
    fun `a negated or absolute floating-point number stays one`() {
        assertEquals(listOf(listOf(-2.0, 2.0, 2.5, -2L, 2L)), query("RETURN [-2.0, abs(-2.0), abs(-2.5), -(2), abs(-2)]"))
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
    fun `a regular expression matches the whole text, and more functions give a graph engine's values`() {
        assertEquals(listOf("Carrie", "Keanu"), query("MATCH (p:Person) WHERE p.name =~ '[CK].*' RETURN p.name ORDER BY p.name"))
        assertEquals(listOf(listOf(false, false, null)), query("RETURN ['Keanu' =~ 'Kea', 'Keanu' =~ 'keanu', null =~ 'x']"))
        // A pattern that opens with (?i) matches in any case, as Java's does.
        assertEquals(listOf(listOf(true, false)), query("RETURN ['Keanu' =~ '(?i)keanu', 'Keanu' =~ '(?i)kea']"))
        // A dot matches no line break, as Java's does, unless the pattern opens with (?s).
        assertEquals(listOf(listOf(false, true)), query("RETURN ['a\\nb' =~ 'a.b', 'a\\nb' =~ '(?s)a.b']"))
        assertEquals(listOf("Carrie", "Keanu"), query("MATCH (p:Person) WHERE p.name =~ \$pattern RETURN p.name ORDER BY p.name", mapOf("pattern" to "(?i)[ck].*")))
        assertEquals(
            listOf(
                mapOf(
                    "split" to listOf("a", "b", "c"), "reversed" to "unaeK", "reversedList" to listOf(3L, 2L, 1L), "tail" to listOf(2L, 3L),
                    "yes" to true, "no" to false, "notBoolean" to null, "fromNumbers" to listOf(true, false), "spaced" to true,
                    "emptyList" to true, "emptyMap" to true, "fullText" to false,
                    "round" to 3.0, "roundTo" to 2.57, "floor" to 2.0, "ceil" to 3.0, "sqrt" to 3.0, "sign" to -1L, "trimmed" to listOf("a ", " b"),
                )
            ),
            query(
                """
                RETURN {split: split('a,b,c', ','), reversed: reverse('Keanu'), reversedList: reverse([1, 2, 3]), tail: tail([1, 2, 3]),
                        yes: toBoolean('TRUE'), no: toBoolean(false), notBoolean: toBoolean('maybe'), fromNumbers: [toBoolean(1), toBoolean(0)], spaced: toBoolean(' true '),
                        emptyList: isEmpty([]), emptyMap: isEmpty({}), fullText: isEmpty('x'),
                        round: round(2.5), roundTo: round(2.567, 2), floor: floor(2.7), ceil: ceil(2.1), sqrt: sqrt(9), sign: sign(-4),
                        trimmed: [ltrim('  a '), rtrim(' b  ')]}
                """
            ),
        )
        assertEquals(listOf(null), query("RETURN split('a', null)"))
        // An empty separator cuts at each character. Oracle's empty text is null.
        if (pm.type != DatabaseType.ORACLE) assertEquals(listOf(listOf(listOf("a", "b", "c"), listOf(""))), query("RETURN [split('abc', ''), split('', ',')]"))
    }

    @Test
    fun `exists and count take a pattern, reading clauses or a whole query, read for the row they are in`() {
        assertEquals(
            listOf("Carrie", "Keanu"),
            query("MATCH (p:Person) WHERE EXISTS { MATCH (p)-[:ACTED_IN]->(m:Movie) WHERE m.released > 2000 } RETURN p.name ORDER BY p.name"),
        )
        assertEquals(listOf("Keanu", "Laurence"), query("MATCH (p:Person) WHERE EXISTS { MATCH (p)-[:KNOWS]->(f) RETURN f } RETURN p.name ORDER BY p.name"))
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Hugo", 0L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person) RETURN p.name, COUNT { (p)-[:ACTED_IN]->() } ORDER BY p.name"),
        )
        assertEquals(
            listOf(2L),
            query("MATCH (p:Person {name: 'Keanu'}) RETURN COUNT { MATCH (p)-[:ACTED_IN]->(m) WITH m WHERE m.released < 2000 RETURN m.title }"),
        )
    }

    @Test
    fun `a call subquery gives rows for each row, and an optional one keeps a row it gives none for`() {
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Hugo", 0L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person) CALL { WITH p MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN count(m) AS movies } RETURN p.name, movies ORDER BY p.name"),
        )
        assertEquals(
            listOf("Keanu>Carrie", "Laurence>Keanu"),
            query("MATCH (p:Person) CALL (p) { MATCH (p)-[:KNOWS]->(f) RETURN f.name AS friend } RETURN p.name + '>' + friend ORDER BY p.name"),
        )
        assertEquals(
            listOf(listOf("Carrie", null), listOf("Hugo", null), listOf("Keanu", "Carrie"), listOf("Laurence", "Keanu")),
            query("MATCH (p:Person) OPTIONAL CALL (p) { MATCH (p)-[:KNOWS]->(f) RETURN f.name AS friend } RETURN [p.name, friend] ORDER BY p.name"),
        )
        assertEquals(listOf("The Matrix Reloaded"), query("CALL { MATCH (m:Movie) RETURN m ORDER BY m.released DESC LIMIT 1 } RETURN m.title"))
    }

    @Test
    fun `a union is taken in a call, a count and an exists, each query read for the row`() {
        assertEquals(
            listOf("Carrie", "Laurence"),
            query(
                """
                MATCH (p:Person {name: 'Keanu'})
                CALL (p) { MATCH (p)-[:KNOWS]->(f) RETURN f.name AS other UNION MATCH (p)<-[:KNOWS]-(f) RETURN f.name AS other }
                RETURN other ORDER BY other
                """
            ),
        )
        assertEquals(
            listOf(5L),
            query("MATCH (p:Person {name: 'Keanu'}) RETURN COUNT { MATCH (p)-[:KNOWS]-(f) RETURN f.name AS n UNION ALL MATCH (p)-[:ACTED_IN]->(m) RETURN m.title AS n }"),
        )
        assertEquals(
            listOf("Keanu", "Laurence"),
            query("MATCH (p:Person) WHERE EXISTS { MATCH (p)-[:KNOWS]->(f) RETURN f UNION MATCH (p)-[:ACTED_IN]->(f) WHERE f.released < 1996 RETURN f } RETURN p.name ORDER BY p.name"),
        )
        if (!compiles) return
        // A graph engine's driver gives a day and a moment as values of its own; here they are their ISO text.
        val (day, moment) = query("RETURN [date(), localdatetime()]").single() as List<*>
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(day as String), day)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6})?").matches(moment as String), moment)
    }

    @Test
    fun `a quantified relationship is a variable-length one`() {
        assertEquals(
            listOf("Carrie", "Keanu"),
            query("MATCH (:Person {name: 'Laurence'})-[:KNOWS]->{1,2}(p) RETURN p.name ORDER BY p.name"),
        )
        assertEquals(listOf("Carrie"), query("MATCH (:Person {name: 'Laurence'})-[:KNOWS]->{2}(p) RETURN p.name"))
        assertEquals(listOf("Carrie", "Keanu"), query("MATCH (:Person {name: 'Laurence'})-[:KNOWS]->+(p) RETURN p.name ORDER BY p.name"))
        assertEquals(listOf(listOf("Laurence", 0L), listOf("Keanu", 1L), listOf("Carrie", 2L)), query("MATCH (:Person {name: 'Laurence'})-[r:KNOWS]->*(p) RETURN [p.name, size(r)] ORDER BY size(r)"))
    }

    @Test
    fun `a shortest path is the least of the pattern's, and all shortest paths every one of that length`() {
        assertEquals(
            listOf(listOf(2L, listOf("Laurence", "Keanu", "Carrie"))),
            query("MATCH (a:Person {name: 'Laurence'}), (b:Person {name: 'Carrie'}) MATCH p = shortestPath((a)-[:KNOWS*]->(b)) RETURN [length(p), [n IN nodes(p) | n.name]]"),
        )
        assertEquals(
            setOf(listOf("Laurence", "Keanu", "Carrie"), listOf("Laurence", "The Matrix", "Carrie")),
            query("MATCH (a:Person {name: 'Laurence'}), (b:Person {name: 'Carrie'}) MATCH p = allShortestPaths((a)-[*]-(b)) RETURN [n IN nodes(p) | coalesce(n.name, n.title)]").toSet(),
        )
        assertEquals(emptyList(), query("MATCH (a:Person {name: 'Hugo'}), (b:Person {name: 'Carrie'}) MATCH p = shortestPath((a)-[*]-(b)) RETURN length(p)"))
        // The WHERE of its MATCH is met by the paths it finds from: the shortest of those that meet it.
        assertEquals(
            listOf(2L),
            query("MATCH (a:Person {name: 'Laurence'}), (b:Person {name: 'Keanu'}) MATCH p = shortestPath((a)-[*]-(b)) WHERE length(p) > 1 RETURN length(p)"),
        )
        // An end not bound before it: the shortest to each node it reaches.
        assertEquals(
            listOf(listOf("Johnny Mnemonic", 2L), listOf("The Matrix", 1L), listOf("The Matrix Reloaded", 2L)),
            query("MATCH (a:Person {name: 'Laurence'}) MATCH p = shortestPath((a)-[*]-(m:Movie)) RETURN [m.title, length(p)] ORDER BY m.title"),
        )
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH p = allShortestPaths((a:Person)-[:ACTED_IN*]->(:Movie)) WITH a, count(p) AS paths RETURN [a.name, paths] ORDER BY a.name"),
        )
    }

    @Test
    fun `a union gives each query's rows in turn, and without all each row once where it is first given`() {
        assertEquals(listOf(3L, 1L, 2L, 3L), query("UNWIND [3, 1] AS x RETURN x AS v UNION ALL UNWIND [2, 3] AS x RETURN x AS v"))
        assertEquals(listOf(3L, 1L, 2L), query("UNWIND [3, 1] AS x RETURN x AS v UNION UNWIND [2, 3] AS x RETURN x AS v"))
        assertEquals(
            listOf(mapOf("name" to "Keanu", "kind" to "person"), mapOf("name" to "The Matrix", "kind" to "movie")),
            query(
                """
                MATCH (p:Person {name: ${'$'}name}) RETURN {name: p.name, kind: 'person'} AS row
                UNION
                MATCH (m:Movie {title: 'The Matrix'}) RETURN {name: m.title, kind: 'movie'} AS row
                UNION
                MATCH (p:Person {name: ${'$'}name}) RETURN {name: p.name, kind: 'person'} AS row
                """,
                mapOf("name" to "Keanu"),
            ),
        )
        if (!compiles) return
        assertEquals(
            "UNION and UNION ALL are not mixed in one statement, as in Cypher.",
            assertThrows<DrivineException> { query("RETURN 1 AS v UNION RETURN 2 AS v UNION ALL RETURN 3 AS v") }.message,
        )
        assertEquals(
            "Each query of a UNION returns the same names, as in Cypher: (v) and (w).",
            assertThrows<DrivineException> { query("RETURN 1 AS v UNION RETURN 2 AS w") }.message,
        )
    }

    @Test
    fun `range gives whole numbers, and reduce folds a list from its first item`() {
        assertEquals(listOf(listOf(listOf(0L, 1L, 2L, 3L), listOf(10L, 7L, 4L), emptyList<Long>())), query("RETURN [range(0, 3), range(10, 4, -3), range(1, 0)]"))
        assertEquals(listOf(6L), query("RETURN reduce(total = 0, x IN [1, 2, 3] | total + x)"))
        assertEquals(listOf(null), query("RETURN reduce(total = 0, x IN null | total + x)"))
        assertEquals(listOf(14L), query("RETURN reduce(dot = 0, i IN range(0, size(\$v) - 1) | dot + \$v[i] * \$v[i])", mapOf("v" to listOf(1, 2, 3))))
        // A reduce in the list of a reduce.
        assertEquals(listOf(3L), query("RETURN reduce(s = 0, x IN reduce(t = [], y IN [1, 2] | t + [y]) | s + x)"))
        // A text built up from the empty text: Oracle's empty text is null. Oracle's reduce in the step of one, and a path read in a step: see CypherQueryOracleTest.
        if (pm.type == DatabaseType.ORACLE) return
        assertEquals(listOf(6L), query("RETURN reduce(s = 0, x IN [[1, 2], [3]] | s + reduce(t = 0, y IN x | t + y))"))
        assertEquals(listOf(1L), query("MATCH p = (:Person {name: 'Laurence'})-[:KNOWS]->() RETURN reduce(s = 0, x IN [1] | s + length(p))"))
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
    }

    @Test
    fun `unwind of a value that is no list gives it as one row, and of null no rows`() {
        assertEquals(listOf(5L), query("UNWIND 5 AS x RETURN x"))
        assertEquals(listOf("a"), query("UNWIND \$v AS x RETURN x", mapOf("v" to "a")))
        assertEquals(emptyList(), query("UNWIND null AS x RETURN x"))
        assertEquals(emptyList(), query("UNWIND \$v AS x RETURN x", mapOf("v" to null)))
        assertEquals(listOf(1L, null), query("UNWIND [1, null] AS x RETURN x"))
    }

    @Test
    fun `null is equal to nothing, itself included`() {
        assertEquals(listOf(listOf(null, null, null, null)), query("RETURN [null = null, null <> null, 1 = null, 'a' <> null]"))
        assertEquals(listOf(null, null), query("UNWIND [1, null] AS x RETURN x = null"))
        assertEquals(emptyList(), query("MATCH (p:Person {name: 'Keanu'}) WHERE NOT (p.name = null) RETURN p.name"))
        assertEquals(listOf("other"), query("RETURN CASE null WHEN null THEN 'null' ELSE 'other' END"))
    }

    @Test
    fun `a map projection gives what it selects over every property, and a relationship's type`() {
        assertEquals(
            listOf(listOf(mapOf("name" to "Laurence", "born" to 1L, "first" to "Keanu"), mapOf("name" to "Laurence", "first" to "Keanu"), "KNOWS")),
            query("MATCH (p:Person {name: 'Laurence'})-[r]->(k:Person) WITH p, r, k.name AS first RETURN [p {.*, born: 1, first}, p {.name, first}, type(r)]"),
        )
    }

    @Test
    fun `a map projection, the keys and a list comprehension of null are null`() {
        assertEquals(
            listOf(listOf(null, null, null, null)),
            query("MATCH (p:Person {name: 'Hugo'}) OPTIONAL MATCH (p)-[:KNOWS]->(f) RETURN [f {.name}, f {.*, x: 1}, keys(f), [t IN p.tags | t]]"),
        )
        assertEquals(listOf(null), query("RETURN [x IN \$v | x]", mapOf("v" to null)))
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
    fun `an index an expression gives reads a list's item, and a map's key as null, for Oracle's paths read no key so`() {
        assertEquals(listOf(listOf(null, 6L)), pm.query(QuerySpecification.withStatement(CypherStatement("WITH {a: 1} AS m, 'a' AS k, [5, 6] AS xs, 1 AS i RETURN [m[k], xs[i]]"))))
    }

    @Test
    fun `reduce reads the row it is in and the parameters, and is refused in a statement that writes, in a step, or reading a path`() {
        assertEquals(
            listOf(listOf("Keanu", 1964L + 7L)),
            pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (p:Person {name: 'Keanu'}) RETURN [p.name, reduce(t = p.born, x IN [3, 4] | t + x * \$k)]")).bind(mapOf("k" to 1))),
        )
        assertEquals(
            "reduce() does not run on Oracle in a statement that writes: its block calls no function of its own from SQL.",
            assertThrows<DrivineException> { pm.query(QuerySpecification.withStatement(CypherStatement("CREATE (n:CyTest) RETURN reduce(t = 0, x IN [1] | t + x)"))) }.message,
        )
        assertEquals(
            "reduce() does not run on Oracle in the step of a reduce(): its function calls no function of the query's from SQL.",
            assertThrows<DrivineException> {
                pm.query(QuerySpecification.withStatement(CypherStatement("RETURN reduce(s = 0, x IN [[1, 2], [3]] | s + reduce(t = 0, y IN x | t + y))")))
            }.message,
        )
        assertEquals(
            "A path read in the step of reduce() does not run on Oracle: its function is given the row as JSON.",
            assertThrows<DrivineException> {
                pm.query(QuerySpecification.withStatement(CypherStatement("MATCH p = (:Person {name: 'Laurence'})-[:KNOWS]->() RETURN reduce(s = 0, x IN [1] | s + length(p))")))
            }.message,
        )
    }

}
