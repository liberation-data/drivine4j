package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.cypher.spec.CypherSpec
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
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Cypher statement written for a graph engine runs on a SQL store, compiled to its SQL, and gives the
 * same rows: the statements of the spec's `cypher/queries.yaml` are run on Neo4j, Postgres, Oracle and SQLite over
 * its graph, and give the rows it says.
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

    @TestFactory
    fun spec() = CypherSpec.load("queries.yaml").let { spec ->
        spec.tests(CypherSpec.storeOf(pm.type), before = { clear(); seed(requireNotNull(spec.graph)) }) { step -> query(step.cypher, step.parameters) }
    }

    @Test
    fun `collect(DISTINCT) beside grouping keys, other aggregates, an order, a page and a variable outside its aggregates`() {
        // Not in the spec yet: aggregatesFirst's grouping WITH, and the projection over it.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 4L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, count(*) + size(collect(DISTINCT m.title)) ORDER BY count(*) DESC, p.name SKIP 1 LIMIT 1"),
        )
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, collect(DISTINCT m) AS ms WHERE size(ms) > 1 RETURN p.name, size(ms) ORDER BY p.name"),
        )
        assertEquals(
            listOf(listOf("Carrie", 1969L), listOf("Keanu", 1967L), listOf("Laurence", 1962L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, size(collect(DISTINCT m.title)) + p.born AS x RETURN p.name, x ORDER BY p.name"),
        )
    }

    @Test
    fun `collect(DISTINCT) in a DISTINCT projection ordered by a key's property or an aggregate no item is`() {
        // Not in the spec yet: the projection over aggregatesFirst's grouping WITH orders by what that WITH holds.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH DISTINCT p, size(collect(DISTINCT m.title)) AS n ORDER BY p.born DESC RETURN p.name, n"),
        )
        assertEquals(
            listOf(listOf("Laurence", 1L), listOf("Carrie", 2L), listOf("Keanu", 3L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN DISTINCT p.name, size(collect(DISTINCT m.title)) ORDER BY count(*)"),
        )
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Laurence", 1L), listOf("Keanu", 3L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p.name AS name, m.title AS t " +
                    "RETURN name AS nm, size(collect(DISTINCT t)) ORDER BY any(x IN collect(DISTINCT t) WHERE x STARTS WITH 'J' AND name = 'Keanu'), nm"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) beside an item named after a variable another item is, ordered by that name`() {
        // Not in the spec yet: in an order term, an item's name is that item, not the variable it shadows.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS person, p.name AS p, size(collect(DISTINCT m.title)) ORDER BY toLower(p)")
                .map { (it as List<*>).drop(1) },
        )
    }

    @Test
    fun `an item of the relationships collect gathers is read in the projection that gathers them`() {
        // Not in the spec yet: Oracle reads no aggregate from a subquery, so the item is read from what collect gathered.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", "Trinity"), listOf("Laurence", "Morpheus")),
            query("MATCH (p:Person)-[r:ACTED_IN]->() WHERE p.name <> 'Keanu' RETURN p.name, collect(r)[0].role ORDER BY p.name"),
        )
        assertEquals(
            listOf(listOf(4L, "Morpheus", "Trinity")),
            query(
                "MATCH (:Person {name: 'Laurence'})-[r:ACTED_IN]->() MATCH (:Person {name: 'Carrie'})-[s:ACTED_IN]->() " +
                    "RETURN size(collect(r) + collect(s)), (collect(r) + collect(s))[0].role, (collect(r) + collect(s))[-1].role"
            ),
        )
        assertEquals(
            listOf(listOf(true, true)),
            query("MATCH (:Person {name: 'Laurence'})-[r:ACTED_IN]->() WITH collect(r) AS rs RETURN rs[9] IS NULL, rs[-9] IS NULL"),
        )
    }

    @Test
    fun `collect(DISTINCT) under a comprehension, a quantifier or a reduce that names a grouping key in its body`() {
        // Not in the spec yet: aggregatesFirst's grouping WITH keeps each variable such a body names, outside the aggregate.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L, false, 12L), listOf("Keanu", 3L, false, 15L), listOf("Laurence", 1L, false, 8L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p.name AS name, m.title AS t " +
                    "RETURN name, size([x IN collect(DISTINCT t) WHERE x <> name]), any(x IN collect(DISTINCT t) WHERE x = name), " +
                    "reduce(n = 0, x IN collect(DISTINCT t) | n + size(name)) ORDER BY name"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) beside a pattern or a subquery that names a grouping key`() {
        // Not in the spec yet: aggregatesFirst's grouping WITH keeps each variable a pattern or a subquery names, outside the aggregate.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L, 2L, false), listOf("Keanu", 4L, 4L, true), listOf("Laurence", 2L, 2L, true)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, size(collect(DISTINCT m.title)) + COUNT { (p)-[:KNOWS]->() } AS x, " +
                    "size([(p)-[:KNOWS]->(f) | f.name] + collect(DISTINCT m.title)) AS y, " +
                    "size(collect(DISTINCT m.title)) > 2 OR EXISTS { (p)-[:KNOWS]->(:Person {name: 'Keanu'}) } AS z " +
                    "RETURN p.name, x, y, z ORDER BY p.name"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) of dates keeps each once, as dates`() {
        // Not in the spec yet: what aggregatesFirst keeps of a collection is still of the kind collect gathered.
        val dates = "UNWIND [date('2020-01-01'), date('2020-01-01'), date('2021-05-01')] AS d"
        assertEquals(listOf(listOf(2020L, 2021L)), query("$dates WITH collect(DISTINCT d) AS ds RETURN [x IN ds | x.year]"))
        assertEquals(listOf(5L), query("$dates RETURN collect(DISTINCT d)[1].month"))
    }

    @Test
    fun `a map or a list that holds a relationship is equal to one whose numbers are written otherwise or whose keys come in another order`() {
        // Not in the spec yet: identities compare as JSON values, not as their text, which SQLite's = compares.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(true, true, true, true)),
            query("MATCH (:Person {name: 'Keanu'})-[r1:KNOWS]->() RETURN [{x: r1, y: 1} = {x: r1, y: 1.0}, [r1, 1] = [r1, 1.0], {a: 1, x: r1} = {x: r1, a: 1}, {x: r1, y: 1} IN [{x: r1, y: 1.0}]]"),
        )
    }

    @Test
    fun `a list of a few thousand relationships is equal by identity to a map's or a list's`() {
        // Not in the spec yet: their identity is larger than Oracle's = compares (ORA-40180), so Oracle compares it by JSON_EQUAL.
        clear()
        assertEquals(listOf(2500L), query("CREATE (h:Hub) WITH h UNWIND range(1, 2500) AS i CREATE (h)-[:R]->(:Leaf) RETURN count(*)"))
        assertEquals(
            listOf(listOf(true, true, true, 2500L)),
            query("MATCH (:Hub)-[r:R]->() WITH collect(r) AS rs RETURN [[rs][0] = rs, {x: rs}.x = rs, rs IN [rs], size(rs)]"),
        )
    }

    @Test
    fun `collect(DISTINCT) keeps a few thousand lists or maps holding a relationship once each in about a second, not a minute`() {
        // Not in the spec yet: each was compared with every one before it, which took 20 to 40 seconds on Oracle and SQLite.
        clear()
        assertEquals(listOf(5000L), query("CREATE (h:Hub) WITH h UNWIND range(1, 5000) AS i CREATE (h)-[:R]->(:Leaf) RETURN count(*)"))
        val started = System.nanoTime()
        assertEquals(
            listOf(listOf(5000L, 5000L)),
            query("MATCH p = (:Hub)-[r:R]->() RETURN [size(collect(DISTINCT relationships(p))), size(collect(DISTINCT {x: r}))]"),
        )
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertTrue(seconds < 15, "$seconds seconds")
    }

    @Test
    fun `an item of a pattern comprehension's relationships is compared by its id`() {
        // Not in the spec yet: a pattern comprehension gathers their properties beside their ids, as collect does, but in a subquery.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(false, true, true)),
            query(
                "MATCH (a:Person {name: 'Keanu'}) WITH [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r] AS rs, a " +
                    "RETURN [[(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][0] = [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][1], " +
                    "[(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][1] IN rs, [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][0].role = 'Neo']"
            ),
        )
    }

    @Test
    fun `a list comprehension over nodes or relationships that keeps them gives a list of them, compared by id`() {
        // Not in the spec yet: what entityComprehension keeps, with no projection or one of the item, is still a list of nodes or relationships.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(true, false, 1L, false, listOf("Neo", "Neo"))),
            query(
                "MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN {role: 'Neo'}]->() WITH collect(r) AS rs " +
                    "RETURN [[x IN rs WHERE x <> rs[0]][0] = rs[1], [x IN rs | x][0] = rs[1], size([x IN rs WHERE x = rs[0] | x]), " +
                    "rs[0] IN [x IN rs WHERE x <> rs[0]], [x IN rs | x.role]]"
            ),
        )
        assertEquals(
            listOf(listOf(true, true, listOf("Carrie"))),
            query(
                "MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->(b) " +
                    "RETURN [[n IN nodes(p) WHERE n.name <> 'Laurence'][0] = nodes(p)[1], b IN [n IN nodes(p) | n], [n IN nodes(p) WHERE n = b | n.name]]"
            ),
        )
    }

    @Test
    fun `a path's nodes or relationships added together are a list of them, and of no path, null`() {
        // Not in the spec yet: lists of nodes no collect gathers, added, indexed and iterated by id; over a null list, null.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(6L, true, false, listOf("Laurence", "Keanu", "Carrie", "Laurence", "Keanu", "Carrie"), true, 4L)),
            query(
                "MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->() " +
                    "RETURN [size(nodes(p) + nodes(p)), (nodes(p) + nodes(p))[3] = nodes(p)[0], (nodes(p) + nodes(p))[3] = nodes(p)[1], " +
                    "[n IN nodes(p) + nodes(p) | n.name], relationships(p)[1] IN relationships(p) + relationships(p), size(relationships(p) + relationships(p))]"
            ),
        )
        assertEquals(
            listOf(listOf(null, null, null)),
            query("OPTIONAL MATCH p = (:Nope)-->() RETURN [[n IN nodes(p) | n.name], [n IN nodes(p) WHERE true], nodes(p)[0]]"),
        )
    }

    @Test
    fun `randomUUID gives a new UUID each time`() {
        val (a, b) = query("UNWIND [1, 2] AS x RETURN randomUUID()").map { it as String }
        assertTrue(a != b && Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a), "$a $b")
    }

    @Test
    fun `a walk read through a WITH, then sorted by toString in a second WITH, keeps that order`() {
        // Not in the spec yet: the walk is a query inside the one the second WITH sorts, and its term, carried out, is bound in no subquery (#192).
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf("Carrie", "Johnny Mnemonic", "Keanu", "The Matrix", "The Matrix", "The Matrix", "The Matrix Reloaded", "The Matrix Reloaded"),
            query("MATCH (:Person {name: 'Laurence'})-[*]->(b) WITH b WITH b ORDER BY toString(coalesce(b.name, b.title)) RETURN coalesce(b.name, b.title)"),
        )
    }

    @Test
    fun `a whole float past 2 to the 53 written in the statement or given equals the same float worked out`() {
        // Not in the spec yet: Postgres writes such a float out in full, by its shortest decimal, not the double's exact value.
        assertEquals(listOf(listOf(true, true, true)), query("RETURN [toFloat('1e24') = 1e24, toFloat('1e300') = 1e300, toFloat('1e24') = \$x]", mapOf("x" to 1e24)))
    }

    @Test
    fun `a number or a text the statement gives, added to a text, is written as toString writes it`() {
        // Not in the spec yet: a side the statement gives is written out at compile time, and the other side as it is.
        assertEquals(
            listOf(listOf("a1.0E7", "1.0E7a", "a2.0", "a1.0E-4", "3b", "as", "s1.5E-7", "x10", "0.5y", "4b")),
            query(
                "RETURN ['a' + 1e7, 1e7 + 'a', 'a' + 2.0, 'a' + \$f, \$i + 'b', 'a' + \$s, \$s + 1.5e-7, 'x' + 10, \$m.n + 'y', \$j + 'b']",
                mapOf("f" to 1.0e-4, "i" to 3L, "s" to "s", "m" to mapOf("n" to 0.5), "j" to 4),
            ),
        )
    }

    @Test
    fun `NaN or an infinity the statement gives, added to a text, is written as toString writes it`() {
        assertEquals(
            listOf(listOf("aNaN", "Infinityb", "c-Infinity", "NaN1.5")),
            query(
                "RETURN ['a' + \$n, \$p + 'b', 'c' + \$q, \$n + toString(1.5)]",
                mapOf("n" to Double.NaN, "p" to Double.POSITIVE_INFINITY, "q" to Double.NEGATIVE_INFINITY),
            ),
        )
    }

    @Test
    fun `a quotient of floats is written without the zeros its scale leaves`() {
        // The spec's float arithmetic steps skip Postgres, which works a float out in exact decimal (#188): these it gives as a double does.
        assertEquals(
            listOf(listOf(2.0, 3.5, 2.5, "2.0", "2.5", "3.5")),
            query("RETURN [4.0 / 2, 7 / 2.0, 10.0 / 4, toString(4.0 / 2), toString(10.0 / 4), toString(7 / 2.0)]"),
        )
    }

    @Test
    fun `toString of a double past Oracle's NUMBER range writes its shortest digits`() {
        // Not in the spec yet: Oracle writes such a double from its own text, not from TO_CHAR's 15 digits.
        assertEquals(
            listOf(listOf("1.7976931348623157E308", "1.5E300", "1.0E-310", "-2.5E-320", "-1.5E300")),
            query("RETURN [toString(1.7976931348623157e308), toString(1.5e300), toString(1.0e-310), toString(\$a), toString(\$b)]", mapOf("a" to -2.5e-320, "b" to -1.5e300)),
        )
        assertEquals(listOf("4.9E-324"), query("RETURN toString(5e-324)"))
    }

    @Test
    fun `a UNION in CALL whose queries return a list with an identity beside one without compiles, as it did before identities`() {
        // Not in the spec yet: the queries are compiled again without identities, so their columns match.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(0L, 3L),
            query("CALL { MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN]->() RETURN collect([r]) AS l UNION RETURN [] AS l } RETURN size(l) ORDER BY size(l)"),
        )
        assertEquals(
            listOf(2L),
            query("CALL { MATCH (:Person {name: 'Keanu'})-[r:KNOWS]->() RETURN {x: r} AS m UNION RETURN {x: 1} AS m } RETURN count(*)"),
        )
    }

    @Test
    fun `a CASE that gives a list of relationships or of texts is read, and gathered on every store but Oracle, which refuses it`() {
        // Not in the spec yet: on Oracle the CASE's results are worked out first; gathered, it closes the session, so it is refused.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Johnny Mnemonic"), listOf("The Matrix"), listOf(mapOf("role" to "Neo"))),
            query("MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN]->(m) WITH r, m ORDER BY m.title RETURN CASE WHEN m.released > 2000 THEN [r] ELSE [m.title] END"),
        )
        // A CASE that reads an aggregate is worked out in the query that aggregates, where a subquery would group it again.
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 0L)),
            query("MATCH (a:Person)-[r:ACTED_IN]->(m) RETURN a.name, size(CASE WHEN count(*) > 1 THEN collect(r) ELSE [] END) ORDER BY a.name"),
        )
        // Counted, by count() or the size() of its collect, it is read on Oracle too, as it was before.
        assertEquals(
            listOf(listOf("Carrie", 2L, 2L), listOf("Keanu", 3L, 3L), listOf("Laurence", 1L, 1L)),
            query("MATCH (a:Person)-[r:ACTED_IN]->(m) RETURN a.name, size(collect(CASE WHEN m.released > 2000 THEN [r] ELSE [] END)), count(CASE WHEN m.released > 2000 THEN [r] ELSE [] END) ORDER BY a.name"),
        )
        val gathered = "MATCH (a:Person)-[r:ACTED_IN]->(m) WITH a, r, m ORDER BY m.title RETURN a.name, [x IN collect(CASE WHEN m.released > 2000 THEN [r] ELSE [m.title] END) | x[0]] ORDER BY a.name"
        // With no ELSE, its null is the value of another kind.
        val unmatched = "MATCH (a:Person)-[r:ACTED_IN]->(m) WITH a, r, m ORDER BY m.title RETURN a.name, [x IN collect(CASE WHEN m.released > 2000 THEN [r] END) | x[0].role] ORDER BY a.name"
        if (pm.type == DatabaseType.ORACLE) {
            listOf(gathered, unmatched).forEach { statement ->
                val refused = assertThrows<DrivineException> { query(statement) }.message!!
                assertTrue(refused.contains("On Oracle, an aggregate of a CASE that gives a list of nodes or relationships beside a value of another kind"), refused)
            }
            return
        }
        assertEquals(listOf(listOf("Carrie", listOf("Trinity")), listOf("Keanu", listOf("Neo")), listOf("Laurence", emptyList<String>())), query(unmatched))
        assertEquals(
            listOf(listOf("Carrie", listOf("The Matrix", mapOf("role" to "Trinity"))), listOf("Keanu", listOf("Johnny Mnemonic", "The Matrix", mapOf("role" to "Neo"))), listOf("Laurence", listOf("The Matrix"))),
            query(gathered),
        )
    }

    @Test
    fun `lists and maps that hold a null compare item by item, null where no pair differs`() {
        // Beside the spec's cases since v0.6.6 (#225): Cypher compares a list or a map item by item, so a null it holds makes the comparison null.
        assertEquals(listOf(listOf(null, null, null)), query("RETURN [[null] = [null], {x: null} = {x: null}, [null, 1] = [null, 1]]"))
        assertEquals(listOf(listOf(false, false, false, true)), query("RETURN [[1, null] = [2, null], [1, 2] = [1], [null] = [null, null], [] = []]"))
        assertEquals(listOf(listOf(null, true, null)), query("RETURN [[null] <> [null], [1, null] <> [2, null], [null, 1] <> [null, 1]]"))
        assertEquals(
            listOf(listOf(false, false, null, false, false)),
            query("RETURN [{x: null} = {y: null}, {x: 1, y: null} = {x: 2, y: null}, {x: null} = {x: 1}, {x: null} = {}, {a: 1} = {a: 1, b: null}]"),
        )
        assertEquals(
            listOf(listOf(null, false, null, null, false, false)),
            query("RETURN [[[null]] = [[null]], [[1, null]] = [[2, null]], [{x: null}] = [{x: null}], {a: [null]} = {a: [null]}, [1, [null]] = [2, [null]], [[null], 1] = [[null], 2]]"),
        )
        assertEquals(
            listOf(listOf(false, null, false, false, true, null, null, null)),
            query("RETURN [[1] = ['1'], [null] = [1], [[1]] = [1], [null, 'a'] = [1, 'b'], [1] = [1.0], [null] = [[1]], [null] = [{x: 1}], [null, 1] = [1, null]]"),
        )
        assertEquals("n", query("RETURN CASE [null] WHEN [null] THEN 'y' ELSE 'n' END").single())
        // Lists that hold no null compare as before.
        assertEquals(listOf(listOf(true, true, true, true, false)), query("RETURN [[1, 2] = [1, 2], [1, 2] <> [1, 3], {x: 1} = {x: 1}, 'a' IN ['a', 'b'], 'c' IN ['a', 'b']]"))
    }

    @Test
    fun `IN compares a value with each item as equality does`() {
        // Beside the spec's cases since v0.6.6 (#225): true where an item is equal, else null where a comparison is null, else false.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        // A node with no name is in no list of names, nor out of one: NOT keeps it no more than the IN does.
        assertEquals(listOf(3L, 1L), query("MATCH (n:CyTest) WHERE NOT (n.name IN ['Keanu']) RETURN count(*) UNION ALL MATCH (n:CyTest) WHERE n.name IN ['Keanu'] AND n.born > 0 OR n.name IN \$x RETURN count(*)", mapOf("x" to emptyList<String>())))
        // A quantifier keeps an item where its condition is true, so one with no name is in all of no list of names.
        assertEquals(listOf(1L), query("MATCH (n:CyTest) WHERE all(x IN [1] WHERE n.name IN ['Keanu']) RETURN count(*)"))
        assertEquals(
            listOf(listOf(null, null, null, true, null, false, false, null, false, true, null, null, null, null)),
            query(
                "RETURN [null IN [null], [null] IN [[null]], 1 IN [null], 1 IN [1, null], 2 IN [1, null], null IN [], [null] IN [[1, 2]], " +
                    "[null] IN [[1]], [1, null] IN [[2, null]], [1] IN [[1], null], {x: null} IN [{x: null}], [1] IN [[2], null], [1] IN [[null]], null IN [1]]"
            ),
        )
        assertEquals(
            listOf(listOf(null, true, null, false, null)),
            query(
                "RETURN [\$x IN \$l, 2 IN \$l, \$a IN \$ls, \$a IN [[2, null]], 1 IN \$n]",
                mapOf("x" to null, "l" to listOf(1, 2), "a" to listOf(1, null), "ls" to listOf(listOf(1, null), listOf(3)), "n" to null),
            ),
        )
        // Read by a WHERE, a null is in no list that holds one: JSON's null is equal to such an item as JSON.
        val nulls = mapOf("l" to listOf(null, 2), "m" to mapOf("x" to null))
        assertEquals(emptyList<Any?>(), query("UNWIND [1] AS i WITH i WHERE null IN \$l OR \$m.x IN \$l RETURN i", nulls))
        assertEquals(listOf(emptyList<Any?>()), query("RETURN [x IN [1] WHERE null IN \$l OR \$m.x IN \$l]", nulls))
    }

    @Test
    fun `values that hold a null compare item by item where neither side is written out`() {
        // Beside the spec's cases since v0.6.6 (#225): parameters, values carried by a WITH and collected lists are compared as they are at run time.
        assertEquals(
            listOf(listOf(null, false, null, null, null, true)),
            query(
                "RETURN [\$a = \$b, \$a = \$c, \$a IN [\$b], \$a IN \$l, \$m = \$n, \$p = \$p]",
                mapOf("a" to listOf(1, null), "b" to listOf(1, null), "c" to listOf(2, null), "l" to listOf(listOf(1, null), listOf(3)), "m" to mapOf("x" to null), "n" to mapOf("x" to 1), "p" to listOf(1, 2)),
            ),
        )
        assertEquals(
            listOf(listOf(null, false, true, null, null, false)),
            query("WITH [1, null] AS a, [1, null] AS b, [2, null] AS c, [null, 1] AS d RETURN [a = b, a = c, a <> c, a = d, a IN [b, c], a IN [c]]"),
        )
        assertEquals(
            listOf(listOf(false, null, null, false, true)),
            query("WITH [[null], 1] AS a, [[null], 2] AS b, {k: [null]} AS m, {k: [1]} AS n, {k: [1], j: 2} AS o RETURN [a = b, m = n, m = m, m = o, n = n]"),
        )
        assertEquals(
            listOf(listOf(null, false, null)),
            query("UNWIND [[1, null], [2, null]] AS l WITH collect(l) AS ls RETURN [ls = [[1, null], [2, null]], ls[0] = ls[1], ls[0] IN ls]"),
        )
        assertEquals(listOf(listOf(3L)), query("UNWIND [[1, null], [2, null], [3]] AS l WITH l WHERE l = [1, null] OR l = [3] RETURN l"))
        // A parameter's list compared item by item as the statement is compiled, at any depth.
        assertEquals(
            listOf(listOf(null, false, false, null, true, false)),
            query(
                "UNWIND [[1, [2, 3]], [1, [2]], 5] AS l WITH collect(l) AS ls RETURN [ls[0] = \$p, ls[1] = \$p, ls[2] = \$p, \$p = ls[0], ls[0] <> \$q, \$q = ls[1]]",
                mapOf("p" to listOf(null, listOf(2, 3)), "q" to listOf(1, listOf(null, 4))),
            ),
        )
    }

    @Test
    fun `a text that holds a null's text, and a value too long for a short text, compare as values that hold no null`() {
        // Beside the spec's cases since v0.6.6 (#225): Oracle reads whether a value holds a null from its JSON's text.
        assertEquals(
            List(6) { listOf(true, true, true) },
            query("UNWIND ['a,null]', 'x:null}', 'null', 'a\\\",null]', 'é😀\\\",null]', 'a\\\\'] AS s WITH s, [s] AS v, [[s]] AS l, {k: s} AS m, [{k: s}] AS ms RETURN [v IN l, v = [s], m IN ms]"),
        )
        // A text that ends in a backslash ends where its quote does.
        assertEquals(listOf(listOf(true, true)), query("UNWIND ['a\\\\'] AS s WITH s, [s, ',null]'] AS v RETURN [v = [s, ',null]'], v IN [[s, ',null]']]]"))
        val long = "[x IN range(1, 600) | 'abcdefghij']"
        assertEquals(listOf(listOf(true, true, false)), query("WITH $long AS v, $long AS w, [$long] AS l RETURN [v = w, v IN l, v <> w]"))
        // A long value that holds a null is read whole, so the null is found.
        assertEquals(
            listOf(listOf(null, null, null, false)),
            query("WITH $long + [null] AS v, $long + [null] AS w, [$long + [null]] AS l, ['z'] + $long + [null] AS u RETURN [v = w, v IN l, v <> w, v = u]"),
        )
        // A text past 32 KB beside a null is compared whole (#293), with one as long or with a short one, either side.
        assertEquals(
            listOf(listOf(null, false, false, false)),
            query("WITH [null, \$t] AS a, [null, \$t] AS b, [null, \$t + 'y'] AS c, [null, 'x'] AS d RETURN [a = b, a = c, a = d, d = a]", mapOf("t" to "x".repeat(40000))),
        )
        // In the step of a reduce, as in a statement that writes, on Oracle.
        assertEquals(listOf(listOf("null", false)), query("WITH [1, null] AS a, [2, null] AS c RETURN reduce(acc = [], x IN [a, c] | acc + [coalesce(x = a, 'null')])"))
        assertEquals(listOf(listOf(true, true)), query("UNWIND ['a,null]'] AS s WITH s, [s] AS v, [[s]] AS l RETURN reduce(acc = [], x IN [v] | acc + [x = [s], x IN l])"))
    }

    @Test
    fun `texts past 32 KB in a parameter's lists that hold a null compare as texts`() {
        // A parameter's list with such a text is walked, not written in the statement item by item, past the literal Oracle
        // takes (ORA-01704) (#303).
        val text = "x".repeat(40_000)
        val given = mapOf("a" to listOf(text, null), "b" to listOf(text, null), "c" to listOf(text + "y", null))
        assertEquals(listOf(listOf(null, false, null, false)), query("RETURN [\$a = \$b, \$a = \$c, \$a IN [\$b], \$a IN [\$c]]", given))
        assertEquals(listOf(listOf(null, false, null)), query("WITH \$a AS a, \$b AS b, \$c AS c RETURN [a = b, a = c, {k: a} = {k: b}]", given))
    }

    @Test
    fun `a null node or relationship in a list or a map makes its comparison null`() {
        // Beside the spec's cases since v0.6.6 (#225): a node or a relationship OPTIONAL MATCH finds none of is null in what holds it.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(null, null, null, null, null, null, null, null, false)),
            query("OPTIONAL MATCH (x:Nope)-[r]->() RETURN [[r] = [r], {x: r} = {x: r}, [r] <> [r], r IN [r], [r] IN [[r]], x IN [x], [x] = [x], {x: x} = {x: x}, [r, 1] = [r, 2]]"),
        )
        assertEquals(
            listOf(listOf(null, null, null, null, null, null, false)),
            query(
                "MATCH (:Person {name: 'Keanu'})-[r1:KNOWS]->() OPTIONAL MATCH (:Person {name: 'Hugo'})-[r2:KNOWS]->() " +
                    "WITH r1, r2, [r1, r2] AS a, {x: r2} AS m " +
                    "RETURN [[r1, r2] = [r1, r2], {a: r1, b: r2} = {a: r1, b: r2}, a = a, m = m, a = [r1, r1], r1 IN [r2], a = [r1]]"
            ),
        )
    }

    @Test
    fun `a statement that writes compares a list that holds a null item by item`() {
        // Beside the spec's cases since v0.6.6 (#225); on Oracle a procedure calls no function of its own, so the store's walks them (#270).
        clear()
        assertEquals(
            listOf(listOf("null", false, true)),
            query("CREATE (n:CyNullEquality) WITH n, [1, null] AS a, [1, null] AS b, [2, null] AS c SET n.same = coalesce(a = b, 'null'), n.other = a = c, n.unequal = a <> c RETURN [n.same, n.other, n.unequal]"),
        )
        // Values that hold no null compare as before, a text that holds a null's text and a long value among them.
        val long = "[x IN range(1, 600) | 'abcdefghij']"
        assertEquals(
            listOf(listOf(true, true, true, true)),
            query("UNWIND ['a,null]'] AS s WITH s, [s] AS v, [[s]] AS l, $long AS w, [$long] AS ws CREATE (n:CyNullEquality) SET n.eq = v = [s], n.in = v IN l, n.longEq = w = $long, n.longIn = w IN ws RETURN [n.eq, n.in, n.longEq, n.longIn]"),
        )
    }

    @Test
    fun `round() reads a precision from data, or a whole float, as a whole number toward zero, and a null as null`() {
        // Not in the spec yet: its round() case takes the precision as written or given.
        clear()
        seed("CREATE (:Round {p: 1.7, q: -0.5, w: 2.0, r: -2, z: 0.0, b: 400})")
        assertEquals(
            listOf(listOf(2.6, 2.6, 3.0, 3.0, 2.56, 2.56)),
            query("MATCH (n:Round) RETURN [round(2.555, n.p), round(2.55, n.p, 'UP'), round(2.5, n.q), round(2.5, n.q, 'HALF_UP'), round(2.555, n.w), round(2.555, 2.0, 'HALF_UP')]"),
        )
        assertEquals(listOf(listOf(3.0, -2.0, 2.0, 3.0, 2.0, -3.0)), query("RETURN [round(2.5, 0, 'HALF_UP'), round(-2.5, 0, 'HALF_EVEN'), round(2.5, 0, 'DOWN'), round(2.1, 0, 'CEILING'), round(2.9, 0, 'FLOOR'), round(-2.1, 0, 'UP')]"))
        assertEquals(listOf(listOf(null, null, null, null)), query("RETURN [round(null, 2), round(2.5, null), round(null, 2, 'UP'), round(2.5, null, 'UP')]"))
        // To 0 places, as round(x) rounds (#278): an integer as a float, a huge or tiny number, a precision from data, and the
        // doubles that a binary sum with a half rounds up, the last below a half and the first odd past 2^52; round(x) likewise.
        assertEquals(
            listOf(listOf(3.0, -3.0, 1.0E20, -1.0E100, 0.0, 0.0, -2.0, 0.0, 4.503599627370497E15, 0.0, 4.503599627370497E15)),
            query("MATCH (n:Round) RETURN [round(3, 0), round(-3, 0), round(1.0E20, 0), round(-1.0E100, 0), round(1.0E-120, 0), round(-1.0E-120, 0), round(-2.5, n.q), " +
                "round(0.49999999999999994, 0), round(4503599627370497.0, 0), round(0.49999999999999994), round(4503599627370497.0)]"),
        )
        // Places past a number's digits give it back, where Oracle's NUMBER would overflow (#266); short of them, it rounds.
        assertEquals(listOf(listOf(2.5, 2.5, 2.5)), query("RETURN [round(2.5, 400), round(2.5, 400, 'HALF_UP'), round(2.5, 400, 'CEILING')]"))
        // Read from data, and so far past the digits that writing them all out would not finish, where Postgres's numeric would overflow.
        assertEquals(listOf(listOf(2.5, 2.5)), query("MATCH (n:Round) RETURN [round(2.5, n.b), round(2.5, n.b, 'FLOOR')]"))
        assertEquals(listOf(listOf(2.5, 2.5)), query("RETURN [round(2.5, 1000000000), round(2.5, 1000000000, 'HALF_UP')]"))
        assertEquals(
            listOf(listOf(1.0E-100, 123.456, 1.0E100, 0.3333333333333333, 0.0, -2.5)),
            query("RETURN [round(1.0E-100, 120), round(123.456, 200, 'HALF_UP'), round(1.0E100, 30), round(1.0 / 3, 20), round(1.0E-100, 99), round(-2.5, 300, 'FLOOR')]"),
        )
        // Zero, whose digits Oracle cannot count by its logarithm, and a precision short of a tiny number's digits but past NUMBER's range.
        assertEquals(
            listOf(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0E-120, 1.0E-120, -1.0E-120)),
            query("MATCH (n:Round) RETURN [round(0.0, 400), round(0, 400, 'HALF_UP'), round(n.z, 2), round(n.z, 400), round(n.z, 2, 'UP'), round(1.0E-120, 130), round(1.0E-120, 130, 'HALF_UP'), round(-1.0E-120, 136, 'CEILING')]"),
        )
        // Either side of the last place a double holds, and short of it, where a mode still rounds.
        assertEquals(
            listOf(listOf(123.456, 123.456, 123.456, -123.456, 1234.5678901234567, 1234.567890123457)),
            query("RETURN [round(123.456, 14, 'UP'), round(123.456, 15, 'UP'), round(123.456, 16, 'UP'), round(-123.456, 14, 'FLOOR'), round(1234.5678901234567, 13), round(1234.5678901234567, 12, 'HALF_EVEN')]"),
        )
        // A null number past its digits, and numbers near NUMBER's least, whose digits Oracle's LOG cannot count (#292).
        assertEquals(
            listOf(listOf(null, null, null, 0.0, -0.01, 1.0E-130, -1.0E-125, 1.234567890123457E-130, 6.0E-128, 0.0)),
            query(
                "MATCH (n:Round) RETURN [round(null, 400), round(n.none, 400, 'UP'), round(n.none, n.b), round(1.0E-129, 2), round(-1.0E-129, 2, 'FLOOR'), round(1.0E-130, 140), " +
                    "round(-1.0E-125, 141, 'HALF_EVEN'), round(1.2345678901234567E-130, 145), round(5.5E-128, 128, 'HALF_EVEN'), round(1.0E-130, 2)]"
            ),
        )
        // A negative precision from data fails when the row is read, on every store, in both forms.
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(1234.5, n.r, 'HALF_UP')") }
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(1234.5, n.r)") }
        // Zero too, whose places Oracle gives back without rounding.
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(n.z, n.r, 'HALF_UP')") }
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(n.z, n.r)") }
        // The least doubles, whose last digits are short of the 400 places Postgres holds a precision to. Oracle's NUMBER holds none of them.
        if (pm.type != DatabaseType.ORACLE) assertEquals(
            listOf(listOf(2.225073858507201E-308, 2.2250738585072014E-308, 4.9E-324, 9.9E-324)),
            query("RETURN [round(2.2250738585072014E-308, 323), round(2.2250738585072014E-308, 400), round(4.9E-324, 324, 'UP'), round(4.9E-324, 323, 'UP')]"),
        )
    }

    @Test
    fun `round() by a mode and sign() take the least positive numbers as positive, where Oracle's SIGN gives 0`() {
        // Oracle's SIGN gives 0 for a positive number from 1.0E-130 to under 1.0E-128 (#294); UP, DOWN and the HALF_ modes round by the sign.
        assertEquals(
            listOf(listOf(0.01, 1.0, -0.01, 0.0, 0.0, 0.01, 0.01, 1.0E-129)),
            query("RETURN [round(1.0E-129, 2, 'UP'), round(1.0E-129, 0, 'UP'), round(-1.0E-129, 2, 'UP'), round(1.0E-129, 2, 'DOWN'), round(-1.0E-129, 2, 'DOWN'), round(1.0E-130, 2, 'UP'), round(1.0E-129, 2, 'CEILING'), round(1.0E-129, 129, 'UP')]"),
        )
        assertEquals(
            listOf(listOf(1.0E-129, 0.0, 0.0, 2.0E-129, -1.0E-129, 1.0E-128)),
            query("RETURN [round(5.0E-130, 129, 'HALF_UP'), round(5.0E-130, 129, 'HALF_DOWN'), round(5.0E-130, 129, 'HALF_EVEN'), round(1.5E-129, 129, 'HALF_EVEN'), round(-5.0E-130, 129, 'HALF_UP'), round(9.0E-129, 128, 'HALF_UP')]"),
        )
        assertEquals(
            listOf(listOf(1L, -1L, 0L, null, 1L, 1L, 1L)),
            query("RETURN [sign(1.0E-129), sign(-1.0E-129), sign(0.0), sign(null), sign(1.0E-130), sign(9.99E-129), sign(\$x)]", mapOf("x" to 1.0E-129)),
        )
        // Read from a row, beside 0, -0.0 and null, whose sign the rounding does not ask, and under an aggregate.
        assertEquals(
            listOf(listOf(1L, 0.01, 1.0E-129, 0.0), listOf(-1L, -0.01, -1.0E-129, 0.0), listOf(0L, 0.0, 0.0, 0.0), listOf(0L, 0.0, 0.0, 0.0), listOf(null, null, null, null)),
            query("UNWIND [1.0E-129, -1.0E-129, 0.0, -0.0, null] AS x RETURN [sign(x), round(x, 2, 'UP'), round(x, 129, 'HALF_EVEN'), round(x, 0, 'DOWN')]"),
        )
        assertEquals(
            listOf(listOf(1L, 2L, 1L, 0.01, 1L)),
            query("UNWIND [1.0E-129, 5.0E-130] AS x RETURN [sign(sum(x)), sum(sign(x)), sign(min(x)), round(max(x), 2, 'UP'), sign(sign(sign(min(x))))]"),
        )
        // Numbers near NUMBER's greatest, which a sign told by a multiple of them would overflow.
        assertEquals(listOf(listOf(1L, -1L, 1L, -1L, 1L)), query("RETURN [sign(9.0E125), sign(-9.0E125), sign(7), sign(-0.5), sign(0.5)]"))
    }

    @Test
    fun `round() to places the statement gives picks its rounding when compiling, as Neo4j reads them, NaN as 0`() {
        // #301: either side of 0, the first odd double past 2^52 and numbers near NUMBER's greatest, to 0 places written,
        // picked when compiling, and from an expression, by the CASE.
        listOf("0", "toInteger('0')").forEach { places ->
            assertEquals(
                listOf(listOf(0.0, 0.0, 0.0, -4.503599627370497E15, 9.0E125, -9.0E125)),
                query("RETURN [round(-0.0, $places), round(-0.4, $places), round(-0.49999999999999994, $places), round(-4503599627370497.0, $places), round(9.0E125, $places), round(-9.0E125, $places)]"),
                places,
            )
        }
        // Places a parameter or a parameter's key gives: a fraction, -0.5, negated, or NaN is 0 places, as Neo4j casts NaN
        // to 0, and 1.0 is 1; a mode rounds by them alone, NaN too.
        assertEquals(
            listOf(listOf(-2.0, -2.0, -2.0, -2.0, -2.0, -2.5, -2.6, -2.0, -2.5, -3.0, -3.0, -2.0, -2.0, -2.0, -3.0, -2.0)),
            query(
                "RETURN [round(-2.5, \$p), round(-2.5, \$q), round(-2.5, -\$r), round(-2.5, \$z), round(-2.5, \$n), round(-2.5, \$o), round(-2.55, \$o), round(-2.5, 0.9999), " +
                    "round(-2.5, 1.0), round(-2.5, \$p, 'HALF_UP'), round(-2.5, 0, 'HALF_UP'), round(-2.5, \$m.z), round(-2.5, -0.5), round(-2.5, -0.9999), " +
                    "round(-2.5, \$n, 'HALF_UP'), round(-2.5, \$n, 'HALF_DOWN')]",
                mapOf("p" to 0.7, "q" to -0.5, "r" to 0.5, "z" to 0L, "n" to Double.NaN, "o" to 1.0, "m" to mapOf("z" to 0L)),
            ),
        )
        // Over an aggregate, where nothing is shared, written or read from a row.
        assertEquals(
            listOf(listOf(0L, listOf(-2.0, -2.0, -2.5, -2.0))),
            query("UNWIND [-1.0, -1.5] AS x WITH x, 0 AS p RETURN p, [round(sum(x), 0), round(sum(x), p), round(sum(x), 1), round(avg(x) * 2, p)]"),
        )
    }

    @Test
    fun `round() of zero, which has no digits to count, gives zero, or null to a null precision, and fails on a negative one`() {
        // #292: zero, among rows that are not zero, wherever it is read.
        clear()
        seed("CREATE (:Round {x: 0.0}), (:Round {x: 1.25}), (:Round {x: 0.0}), (:Round {x: -2.5})")
        assertEquals(listOf(2L), query("MATCH (n:Round) WHERE round(n.x, 400) = 0 RETURN count(*)"))
        assertEquals(listOf(2L), query("MATCH (n:Round) WHERE round(n.x, 2) = 0 AND round(n.x, 1, 'UP') = 0 RETURN count(*)"))
        assertEquals(
            listOf(listOf(-2.5, 1L), listOf(0.0, 2L), listOf(1.3, 1L)),
            query("MATCH (n:Round) RETURN round(n.x, 1) AS r, count(*) AS c ORDER BY r"),
        )
        assertEquals(listOf(1.25, 0.0, 0.0, -2.5), query("MATCH (n:Round) RETURN n.x ORDER BY round(n.x, 1, 'UP') DESC"))
        assertEquals(
            listOf(listOf(0.0, 0.0, 0.0, 0.0)),
            query("UNWIND [0.0, 0.0] AS x RETURN [round(sum(x), 2), round(avg(x), 400), round(min(x), 2, 'HALF_UP'), round(sum(x), 1, 'CEILING')]"),
        )
        assertEquals(
            listOf(listOf(0.0, 0.0, 0.0)),
            query("MATCH (n:Round) WITH n.x AS x WHERE round(x, 2) = 0 RETURN [round(sum(x), 2), round(avg(x), 400), round(sum(x), 1, 'CEILING')]"),
        )
        assertEquals(
            listOf(listOf(1500.0, 2000.0, 2000.0, 3000L)),
            query("UNWIND range(0, 2999) AS i WITH toFloat(i % 3) * 0.5 AS x RETURN [sum(round(x, 400)), sum(round(x, 0)), sum(round(x, 0, 'UP')), count(*)]"),
        )
        assertEquals(listOf(3000L), query("UNWIND range(0, 2999) AS i WITH toFloat(i % 3) * 0.5 AS x WHERE round(x, 2) = 0 OR round(x, 400, 'UP') > 0 RETURN count(*)"))
        assertEquals(listOf(listOf(0.0, 1.6, 0.0)), query("RETURN [x IN [0.0, 1.55, 0.0] | round(x, 1)]"))
        assertEquals(listOf(listOf(0.0, 0.0, 0.0, 0.0)), query("RETURN [round(\$z, 2), round(\$z, 400, 'FLOOR'), round(0, 2), round(0.0, 2, 'UP')]", mapOf("z" to 0.0)))
        // The precision is read before zero is given back: a null one gives null, and a negative one from data fails, under an
        // aggregate too, where it is written out where it is read.
        assertEquals(
            listOf(listOf(null, null, null, null), listOf(null, null, null, null)),
            query("MATCH (n:Round) WHERE n.x = 0 RETURN [round(0.0, null), round(0, null, 'HALF_UP'), round(n.x, n.none), round(n.x, n.none, 'UP')]"),
        )
        seed("CREATE (:Round {x: 0.0, r: -2})")
        listOf(
            "UNWIND [0.0, 0.0] AS x WITH x, -2 AS r RETURN r, round(sum(x), r)",
            "UNWIND [0.0, 0.0] AS x WITH x, -2 AS r RETURN r, round(sum(x), r, 'UP')",
            "MATCH (n:Round) WHERE n.r IS NOT NULL WITH n.x AS x, n.r AS r RETURN r, round(sum(x), r)",
            "MATCH (n:Round) WHERE n.r IS NOT NULL WITH n.x AS x, n.r AS r RETURN r, round(min(x), r, 'HALF_UP')",
        ).forEach { statement ->
            val failed = assertThrows<Exception>(statement) { query(statement) }.message!!
            if (compiles) assertTrue(failed.contains("round() takes a precision from 0 to 2147483647, as in Neo4j."), "$statement: $failed")
        }
    }

    @Test
    fun `an aggregate of floats gives a float, whole or not, and a sum of integers an integer`() {
        // #304: Oracle's NUMBER keeps no kind, so a sum of floats that was whole came back an integer there. Each value is
        // checked with its kind: 2.0 is not 2.
        clear()
        seed("CREATE (:F {k: 'a', x: 0.5}), (:F {k: 'a', x: 1.0}), (:F {k: 'a', x: 0.5}), (:F {k: 'b', x: 1}), (:F {k: 'b', x: 2})")
        fun kinds(value: Any?): Any? = if (value is List<*>) value.map(::kinds) else value?.let { it to it::class.simpleName }
        val cases = listOf(
            "UNWIND [0.5, 1.0, 0.5] AS x RETURN sum(x)" to 2.0,
            "UNWIND [1, 1.0] AS x RETURN sum(x)" to 2.0,
            "UNWIND [1, 0.5, 0.5] AS x RETURN sum(x)" to 2.0,
            "UNWIND [1, 2] AS x RETURN sum(x)" to 3L,
            "UNWIND [] AS x RETURN sum(x)" to 0L,
            "UNWIND [1.0, 1.0] AS x RETURN sum(DISTINCT x)" to 1.0,
            // Summed as doubles: past a NUMBER's range, 1e300 is no NUMBER.
            "UNWIND [1e300, 1.0] AS x RETURN sum(x)" to 1e300,
            // An integer among floats is summed as a double too, as Neo4j sums it, and a computed float is one.
            "UNWIND [9223372036854775807, 0.5] AS x RETURN sum(x)" to 9.223372036854776E18,
            "UNWIND [0.5, 2] AS x RETURN sum(x * 10)" to 25.0,
            "UNWIND [0.5, 1.5] AS x RETURN avg(x)" to 1.0,
            "UNWIND [1, 3] AS x RETURN avg(x)" to 2.0,
            "UNWIND [] AS x RETURN avg(x)" to null,
            "UNWIND [2.0, 3.5] AS x RETURN min(x)" to 2.0,
            "UNWIND [2.0, 1.5] AS x RETURN max(x)" to 2.0,
            "UNWIND [1, 2.0] AS x RETURN max(x)" to 2.0,
            "UNWIND [0.5, 1.0, 0.5] AS x RETURN [toString(sum(x)), sum(x) + 1, sum(x) * 2]" to listOf("2.0", 3.0, 4.0),
            "UNWIND [['a', 0.5], ['a', 1.5], ['b', 1], ['b', 1]] AS r WITH r[0] AS k, r[1] AS x WITH k, [sum(x), avg(x), min(x), max(x)] AS a ORDER BY k RETURN collect(a)"
                to listOf(listOf(2.0, 1.0, 0.5, 1.5), listOf(2L, 1.0, 1L, 1L)),
            "MATCH (n:F) WITH n.k AS k, sum(n.x) AS s ORDER BY k RETURN collect(s)" to listOf(2.0, 3L),
            "MATCH (n:F) WHERE n.k = 'a' AND n.x = 1.0 RETURN sum(n.x)" to 1.0,
        ).map { (statement, value) -> Triple(statement, emptyMap<String, Any?>(), value) } + listOf(
            // Parameters: a whole float is bound so it stays one, and a fraction is a number on Oracle.
            listOf(0.5, 1.0, 0.5) to 2.0, listOf(0.5, 0.5) to 1.0, listOf(1.0) to 1.0, listOf(2L, 3L) to 5L,
            // Oracle writes a fraction of more than 40 digits with an exponent and no point.
            listOf(1e-41, -1e-41) to 0.0,
        ).map { (xs, sum) -> Triple("UNWIND \$xs AS x RETURN sum(x)", mapOf("xs" to xs), sum) }
        // Each that differs, so a store's every difference shows at once.
        val differing = cases.mapNotNull { (statement, parameters, value) ->
            val given = kinds(query(statement, parameters).single())
            if (given == kinds(value)) null else "$statement $parameters: $given, not ${kinds(value)}"
        }
        assertEquals(emptyList(), differing)
        // Not compiled to SQL yet, so they give no integer for a float.
        listOf(
            "UNWIND [2.0, 3.0] AS x RETURN percentileDisc(x, 0.5)" to 2.0,
            "UNWIND [1.0, 3.0] AS x RETURN percentileCont(x, 0.5)" to 2.0,
            "UNWIND [1.0, 3.0] AS x RETURN stDevP(x)" to 1.0,
            "UNWIND [1.0, 3.0] AS x RETURN stDev(x)" to 1.4142135623730951,
            "UNWIND [1, 3] AS x RETURN percentileDisc(x, 0.5)" to 1L,
        ).forEach { (statement, value) ->
            if (compiles) {
                val failed = assertThrows<Exception>(statement) { query(statement) }.message!!
                assertTrue(failed.contains("is Cypher, and is not compiled to SQL yet."), "$statement: $failed")
            } else {
                assertEquals(kinds(value), kinds(query(statement).single()), statement)
            }
        }
    }


    @Test
    fun `min() and max() of lists the statement makes compare them item by item, whatever their lengths`() {
        // Not in the spec yet: a nested list (#220). The spec holds DISTINCT and the grouping WITH from v0.6.3.
        assertEquals(listOf(listOf(listOf(listOf(1L), listOf(2L)), listOf(listOf(3L)))), query("UNWIND [[[1, 2]], [[3]], [[1], [2]]] AS xs RETURN [min(xs), max(xs)]"))
        assertEquals(listOf(listOf(listOf(1L, 5L), listOf(10L))), query("UNWIND [[2], [1, 5], [1, 5], [10]] AS xs RETURN [min(DISTINCT xs), max(DISTINCT xs)]"))
        assertEquals(
            listOf(listOf(false, listOf(2L, 1L), listOf(3L)), listOf(true, listOf(0L, 9L, 9L), listOf(1L, 2L))),
            query("UNWIND [[1, 2], [3], [0, 9, 9], [2, 1]] AS xs WITH xs[0] < 2 AS k, min(xs) AS lo, max(xs) AS hi RETURN k, lo, hi ORDER BY k"),
        )
        // The least and the greatest moment still, whatever their offsets.
        assertEquals(
            listOf(listOf("2020-01-01T00:30:00+02:00", "2020-01-01T00:00:00+01:00")),
            query("UNWIND [datetime('2020-01-01T00:00+01:00'), datetime('2020-01-01T00:30+02:00')] AS x RETURN [toString(min(x)), toString(max(x))]"),
        )
        // Oracle puts a null item first and a number before a text (#205).
        if (pm.type == DatabaseType.ORACLE) return
        assertEquals(listOf(listOf(listOf("a", 1L), listOf(1L, "a"))), query("UNWIND [[1, 'a'], ['a', 1], [true]] AS xs RETURN [min(xs), max(xs)]"))
        assertEquals(listOf(listOf(listOf(1L), listOf(null))), query("UNWIND [[1, null], [1], [null], null] AS xs RETURN [min(xs), max(xs)]"))
    }

    @Test
    fun `a list of zoned values worked out with temporal arithmetic, by a comprehension or collect(), sorts as the moments they are`() {
        // Beside the spec's case since v0.6.18 (#199): its long list is worked out from one moment, not from a CASE, and it
        // has no list collect() gathers of them. As text, 10:00+02:00 (08:00 UTC) sorts after 09:00Z: k = 0's start at 08:00 UTC.
        val (a, b) = listOf("datetime('2024-03-15T10:00:00+02:00')", "datetime('2024-03-15T09:00:00Z')")
        val at = "CASE k WHEN 0 THEN $a ELSE $b END"
        assertEquals(listOf(0L, 1L), query("UNWIND [0, 1] AS k WITH k, [x IN range(1, 200) | $at + duration({minutes: x})] AS l RETURN k ORDER BY l"))
        assertEquals(listOf(0L, 1L), query("UNWIND [0, 1] AS k UNWIND range(1, 3) AS x WITH k, $at + duration({minutes: x}) AS t WITH k, collect(t) AS l RETURN k ORDER BY l"))
    }

    @Test
    fun `a list of zoned values sorts as their moments however it was made, and an empty list or a null item sorts as Cypher puts it`() {
        // Beside the spec's case since v0.6.17 (#199). By moment 10:00+02:00, 08:30Z, 09:00Z; as text 08:30Z, 09:00Z, 10:00+02:00.
        val (a, b, c) = listOf("datetime('2024-03-15T10:00:00+02:00')", "datetime('2024-03-15T09:00:00Z')", "datetime('2024-03-15T08:30:00Z')")
        val hours = listOf(10L, 8L, 9L)
        fun sorted(by: String, list: String = "[[t]]") = query("UNWIND [$b, $a, $c] AS t WITH t, $list AS l RETURN t.hour ORDER BY $by")
        // Taken from a list of lists, by an index, head(), last(), a slice, reverse(), tail() and a comprehension.
        for (by in listOf("l[0]", "head(l)", "last(l)", "l[0..1]", "reverse(l)", "[x IN l WHERE x IS NOT NULL]", "[x IN l | x]")) {
            assertEquals(hours, sorted(by), by)
        }
        assertEquals(hours, sorted("tail(l)", "[[t], [t]]"))
        // Made by a CASE, collect(DISTINCT), a UNION, and a comprehension over nodes or a pattern.
        assertEquals(hours, sorted("CASE WHEN t IS NOT NULL THEN [t] END"))
        assertEquals(hours, query("UNWIND [$b, $a, $c] AS t WITH t.hour AS h, collect(DISTINCT [t]) AS l RETURN h ORDER BY l"))
        assertEquals(
            hours,
            query("CALL { UNWIND [$b] AS t RETURN [[t]] AS l UNION UNWIND [$a, $c] AS t RETURN [[t]] AS l } RETURN l[0][0].hour ORDER BY l"),
        )
        clear()
        seed(
            """
            CREATE (:CyZoned {h: 10})-[:AT]->(:CyZonedAt {at: '2024-03-15T10:00:00+02:00'}),
                   (:CyZoned {h: 9})-[:AT]->(:CyZonedAt {at: '2024-03-15T09:00:00Z'}),
                   (:CyZoned {h: 8})-[:AT]->(:CyZonedAt {at: '2024-03-15T08:30:00Z'})
            """,
        )
        assertEquals(hours, query("MATCH (z:CyZoned) RETURN z.h ORDER BY [(z)-[:AT]->(w) | [datetime(w.at)]]"))
        assertEquals(hours, query("MATCH (z:CyZoned)-[:AT]->(w) WITH z, collect(w) AS ws RETURN z.h ORDER BY [n IN ws | [datetime(n.at)]]"))
        // The empty list first, then a list of a moment, then a null item or no list; as items of a list too.
        val ordered = listOf(2L, 1L, 3L)
        val empty = "[i IN [$a] WHERE false]"
        assertEquals(ordered, query("UNWIND [1, 2, 3] AS k WITH k, CASE k WHEN 1 THEN [$a] WHEN 2 THEN $empty END AS l RETURN k ORDER BY l"))
        assertEquals(ordered.reversed(), query("UNWIND [1, 2, 3] AS k WITH k, CASE k WHEN 1 THEN [$a] WHEN 2 THEN $empty END AS l RETURN k ORDER BY l DESC"))
        assertEquals(ordered, query("UNWIND [1, 2, 3] AS k WITH k, [CASE k WHEN 1 THEN [$a] WHEN 2 THEN $empty END] AS l RETURN k ORDER BY l"))
        assertEquals(ordered.reversed(), query("UNWIND [1, 2, 3] AS k WITH k, [CASE k WHEN 1 THEN [$a] WHEN 2 THEN $empty END] AS l RETURN k ORDER BY l DESC"))
        // Two lists of lists of them put together, and min() or max() of lists of them, grouped.
        assertEquals(hours, sorted("l + l"))
        assertEquals(hours, query("UNWIND [$b, $a, $c] AS t WITH t.hour AS h, min([t]) AS m RETURN h ORDER BY m"))
        assertEquals(hours, query("UNWIND [$b, $a, $c] AS t WITH t.hour AS h, max([[t]]) AS m RETURN h ORDER BY m"))
        // Carried past a write, as a list and as a list of lists.
        assertEquals(hours, query("UNWIND [$b, $a, $c] AS t WITH t, [t] AS l CREATE (:CyZonedWrite) RETURN t.hour ORDER BY l"))
        assertEquals(hours, query("UNWIND [$b, $a, $c] AS t WITH t, [[t]] AS l CREATE (:CyZonedWrite) RETURN t.hour ORDER BY l"))
        // Read from the row a write works its value out of (#326's rowKeys), in a subquery that sorts them.
        assertEquals(
            listOf(1L),
            query(
                "UNWIND [$b, $a, $c] AS t WITH collect([t]) AS ls CREATE (n:CyZonedWrite) " +
                    "SET n.first = COUNT { UNWIND ls AS l WITH l ORDER BY l LIMIT 1 WITH l WHERE l[0].hour = 10 RETURN l } RETURN n.first",
            ),
        )
        // After a term whose kind is open, which sorts by its kind first (#308): the text before the numbers, then by moment.
        assertEquals(listOf(8L, 10L, 9L), query("UNWIND [$b, $a, $c] AS t WITH t, [t] AS l, CASE t.hour WHEN 8 THEN 'x' ELSE 1 END AS o RETURN t.hour ORDER BY o, l"))
        assertEquals(listOf(9L, 10L, 8L), query("UNWIND [$b, $a, $c] AS t WITH t, [t] AS l, CASE t.hour WHEN 8 THEN 'x' ELSE 1 END AS o RETURN t.hour ORDER BY o DESC, l DESC"))
    }

    @Test
    fun `a list of temporal values worked out on each row is read item by item, in order, on its own row, wherever it is read`() {
        // Not in the spec yet: Oracle reads such a list by its places, not with JSON_TABLE (#331). Of 0, 1 and 2 items, at 10:01, 10:02.
        val at = "localdatetime('2024-03-15T10:00:00')"
        val rows = "UNWIND [0, 1, 2] AS k WITH k, [i IN range(1, k) | $at + duration({minutes: i})] AS l"
        fun minute(m: Int) = "localdatetime('2024-03-15T10:0$m:00')"
        // A comprehension, an IN, the quantifiers, and the list read twice in one statement.
        assertEquals(
            listOf(listOf(0L, emptyList<Long>(), false, false, true, true, false), listOf(1L, listOf(1L), true, false, true, false, true), listOf(2L, listOf(1L, 2L), true, true, true, false, true)),
            query("$rows RETURN k, [x IN l | x.minute], ${minute(1)} IN l, ${minute(2)} IN l, all(x IN l WHERE x.minute <= k), none(x IN l WHERE x.minute = k), single(x IN l WHERE x.minute = 1) ORDER BY k"),
        )
        // A comprehension within one over the outer item and the row, in order.
        assertEquals(
            listOf(emptyList(), listOf(listOf(1L, 2L)), listOf(listOf(1L, 3L), listOf(2L, 4L))),
            query("$rows RETURN [x IN l | [y IN [x, x + duration({minutes: k})] | y.minute]] ORDER BY k"),
        )
        // An IN over another row's value: each pair whose minute is in the list.
        assertEquals(listOf(listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L))), query("$rows UNWIND [1, 2] AS j WITH k, j, l WHERE $at + duration({minutes: j}) IN l WITH k, j ORDER BY j RETURN k, collect(j) ORDER BY k"))
        // Null and a null item: no list, and a null that is compared.
        assertEquals(
            listOf(listOf(0L, null, null), listOf(1L, listOf(1L), true), listOf(2L, null, null)),
            query("$rows WITH k, CASE WHEN k = 1 THEN l END AS m RETURN k, [x IN m | x.minute], any(x IN m WHERE x.minute = 1) ORDER BY k"),
        )
        assertEquals(
            listOf(listOf(0L, emptyList<Long>(), false), listOf(1L, listOf(null), null), listOf(2L, listOf(null, 2L), null)),
            query("UNWIND [0, 1, 2] AS k WITH k, [i IN range(1, k) | CASE WHEN i > 1 THEN $at + duration({minutes: i}) END] AS l RETURN k, [x IN l | x.minute], ${minute(1)} IN l ORDER BY k"),
        )
        // A slice, from either end, an UNWIND, and a CALL { … }.
        assertEquals(listOf(listOf(emptyList<Long>(), emptyList<Long>()), listOf(emptyList(), listOf(1L)), listOf(listOf(2L), listOf(2L))), query("$rows RETURN [x IN l[1..] | x.minute], [x IN l[-1..] | x.minute] ORDER BY k"))
        assertEquals(listOf(listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L))), query("$rows UNWIND l AS t RETURN k, collect(t.minute) ORDER BY k"))
        assertEquals(listOf(listOf(0L, 0L), listOf(1L, 1L), listOf(2L, 2L)), query("$rows CALL { WITH l UNWIND l AS t RETURN count(t) AS c } RETURN k, c ORDER BY k"))
        // On each side of a UNION, and a list of such lists.
        assertEquals(
            setOf(emptyList(), listOf(1L), listOf(1L, 2L), listOf(2L, 1L)),
            query("$rows RETURN [x IN l | x.minute] AS ms UNION $rows RETURN [x IN reverse(l) | x.minute] AS ms").toSet(),
        )
        assertEquals(listOf(listOf(0L, 0L), listOf(1L, 1L), listOf(2L, 2L)), query("$rows WITH k, [l, l] AS ll RETURN [x IN ll | size(x)][1], size([x IN ll[0] WHERE x.minute > 0]) ORDER BY k"))
        // collect(DISTINCT …) of such lists, and of their items, each once where first.
        val twice = "UNWIND [2, 1, 2] AS k WITH k, [i IN range(1, k) | $at + duration({minutes: i})] AS l"
        assertEquals(listOf(listOf(listOf(1L, 2L), listOf(1L))), query("$twice WITH collect(DISTINCT l) AS ls RETURN [x IN ls | [y IN x | y.minute]]"))
        assertEquals(listOf(listOf(1L, 2L)), query("$twice UNWIND l AS t WITH collect(DISTINCT t) AS ts RETURN [x IN ts | x.minute]"))
        // A list that holds a null is kept once, as Cypher keeps it; zoned values of one moment and two offsets are not equal in
        // Cypher, so each is kept, in the order first seen.
        val nulls = "UNWIND [2, 1, 2, 1] AS k WITH k, [i IN range(1, k) | CASE WHEN i > 1 THEN $at + duration({minutes: i}) END] AS l"
        assertEquals(listOf(listOf(listOf(null, 2L), listOf(null))), query("$nulls WITH collect(DISTINCT l) AS ls RETURN [x IN ls | [y IN x | y.minute]]"))
        val zoned = "UNWIND [0, 1, 2] AS k WITH k, [i IN range(1, 2) | datetime(CASE k WHEN 1 THEN '2024-03-15T10:00:00+02:00' ELSE '2024-03-15T08:00:00Z' END) + duration({minutes: i})] AS l"
        assertEquals(listOf(listOf(listOf(8L, 8L), listOf(10L, 10L))), query("$zoned WITH collect(DISTINCT l) AS ls RETURN [x IN ls | [y IN x | y.hour]]"))
        assertEquals(listOf(listOf(8L, 8L, 10L, 10L)), query("$zoned UNWIND l AS t WITH collect(DISTINCT t) AS ts RETURN [x IN ts | x.hour]"))
    }

    @Test
    fun `a coalesce of a list of temporal values worked out on each row, and lists or maps that hold such a list deeper, read item by item`() {
        // Not in the spec yet: Oracle reads these by their places too, not with JSON_TABLE (#347). Of 0, 1 and 2 items, at 10:01, 10:02.
        val at = "localdatetime('2024-03-15T10:00:00')"
        val rows = "UNWIND [0, 1, 2] AS k WITH k, [i IN range(1, k) | $at + duration({minutes: i})] AS l"
        assertEquals(
            listOf(listOf(0L, emptyList<Long>(), 0L), listOf(1L, listOf(1L), 1L), listOf(2L, listOf(1L, 2L), 2L)),
            query("$rows RETURN k, [x IN coalesce(l, []) | x.minute], size([x IN coalesce(l, []) WHERE x IS NOT NULL]) ORDER BY k"),
        )
        assertEquals(
            listOf(listOf(0L, emptyList<Long>()), listOf(1L, listOf(1L)), listOf(2L, emptyList<Long>())),
            query("$rows WITH k, CASE WHEN k = 1 THEN l END AS m RETURN k, [x IN coalesce(m, []) | x.minute] ORDER BY k"),
        )
        // collect(DISTINCT …) of lists of lists, and of lists of maps, that hold such a list, each once where first.
        val twice = "UNWIND [2, 1, 2] AS k WITH k, [i IN range(1, k) | $at + duration({minutes: i})] AS l"
        assertEquals(
            listOf(listOf(listOf(listOf(1L, 2L), listOf(1L, 2L)), listOf(listOf(1L), listOf(1L)))),
            query("$twice WITH collect(DISTINCT [l, l]) AS ls RETURN [x IN ls | [y IN x | [z IN y | z.minute]]]"),
        )
        assertEquals(listOf(listOf(listOf(1L, 2L), listOf(1L))), query("$twice WITH collect(DISTINCT [x IN l | [x]]) AS ls RETURN [x IN ls | [y IN x | y[0].minute]]"))
        assertEquals(
            listOf(listOf(listOf(listOf(1L, null), listOf(2L, null)), listOf(listOf(1L, null)))),
            query("$twice WITH collect(DISTINCT [x IN l | [x, null]]) AS ls RETURN [x IN ls | [y IN x | [y[0].minute, y[1]]]]"),
        )
        assertEquals(listOf(listOf(2L, 1L)), query("$twice WITH k, [l, l] AS ll WITH collect(DISTINCT ll) AS lls RETURN [x IN lls | size(x[0])]"))
        assertEquals(listOf(listOf(listOf(1L, 2L), listOf(1L))), query("$twice WITH collect(DISTINCT [x IN l | {t: x}]) AS ms RETURN [x IN ms | [y IN x | y.t.minute]]"))
        assertEquals(listOf(listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L))), query("$rows UNWIND [x IN l | {t: x}] AS m RETURN k, collect(m.t.minute) ORDER BY k"))
        // A list taken back out of a map that holds it.
        assertEquals(listOf(listOf(0L, emptyList<Long>()), listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L))), query("$rows WITH k, {l: l} AS m RETURN k, [x IN m.l | x.minute] ORDER BY k"))
        // An item a quantifier takes from a list of such lists, and a coalesce in a list worked out item by item that is a temporal value or a number.
        assertEquals(listOf(listOf(0L, false), listOf(1L, false), listOf(2L, true)), query("$rows WITH k, [l, l] AS ll RETURN k, any(x IN ll WHERE size([y IN x WHERE y.minute = 2]) > 0) ORDER BY k"))
        assertEquals(
            listOf(listOf(0L, emptyList<Long>()), listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L))),
            query("UNWIND [0, 1, 2] AS k WITH k, [i IN range(1, k) | coalesce(CASE WHEN k > 5 THEN k END, $at + duration({minutes: i}))] AS l RETURN k, [x IN l | x.minute] ORDER BY k"),
        )
        // Such a list a reduce gives, one at a map's key a temporal value's field is named for, and the keys of a map that holds one.
        val each = listOf(listOf(0L, emptyList<Long>()), listOf(1L, listOf(1L)), listOf(2L, listOf(1L, 2L)))
        assertEquals(each, query("$rows WITH k, reduce(acc = [], x IN l | acc + [x]) AS m RETURN k, [x IN m | x.minute] ORDER BY k"))
        assertEquals(each, query("$rows WITH k, {minute: l} AS m RETURN k, [x IN m.minute | x.minute] ORDER BY k"))
        assertEquals(listOf(listOf(0L, listOf("l")), listOf(1L, listOf("l")), listOf(2L, listOf("l"))), query("$rows WITH k, {l: l} AS m RETURN k, keys(m) ORDER BY k"))
        // A list of a number and a temporal value sorts, and is searched with IN, as Cypher has it.
        val mixed = "UNWIND [[2, $at + duration({minutes: 2})], [1, $at], [2, $at + duration({minutes: 1})]] AS l"
        assertEquals(
            listOf(listOf(1L, 0L, false, false), listOf(2L, 1L, true, true), listOf(2L, 2L, false, true)),
            query("$mixed RETURN l[0], l[1].minute, $at + duration({minutes: 1}) IN l, 2 IN l ORDER BY l"),
        )
    }

    @Test
    fun `a negative precision from data fails only on a row that reads it, in a WHERE and an aggregate too, with the compiler's message`() {
        // The spec reads a precision from data since v0.6.4; here, in a WHERE and an aggregate, and on rows filtered out.
        clear()
        seed("CREATE (:Round {r: -2, k: 1}), (:Round {r: 1, k: 2})")
        listOf(
            "MATCH (n:Round) RETURN round(1234.5, n.r)",
            "MATCH (n:Round) WHERE round(1234.5, n.r) > 0 RETURN n.k",
            "MATCH (n:Round) RETURN sum(round(1234.5, n.r))",
            "MATCH (n:Round) RETURN collect(round(1234.5, n.r, 'HALF_UP'))",
            // A constant the compiler does not read as given is worked out by the store, as one from data.
            "RETURN round(1234.5, 1 - 3)",
            "RETURN round(1234.5, toInteger('-2'))",
            // An integer is a number too, whose kind each store names apart from a float's.
            "MATCH (n:Round) WHERE n.k = 1 RETURN round(n.k, n.r)",
            "MATCH (n:Round) WHERE n.k = 1 RETURN round(n.k, n.r, 'UP')",
        ).forEach { statement ->
            val failed = assertThrows<Exception>(statement) { query(statement) }.message!!
            if (compiles) assertTrue(failed.contains("round() takes a precision from 0 to 2147483647, as in Neo4j."), failed)
        }
        assertEquals(listOf(1234.5), query("MATCH (n:Round) WHERE n.r >= 0 RETURN round(1234.5, n.r)"))
        assertEquals(listOf(1234.5), query("MATCH (n:Round) WHERE n.k = 2 RETURN sum(round(1234.5, n.r))"))
        assertEquals(listOf(0.0, 1234.5), query("MATCH (n:Round) RETURN CASE WHEN n.r < 0 THEN 0.0 ELSE round(1234.5, n.r) END ORDER BY n.k"))
        assertEquals(listOf(listOf(1234.5, 1234.5)), query("RETURN [round(1234.5, 3 - 1), round(1234.5, toInteger('2'))]"))
        // A null rounds to null whatever the precision, a negative one too (#288): from data, worked out by the store, and
        // under an aggregate, where nothing is shared.
        assertEquals(listOf(listOf(null, null)), query("MATCH (n:Round) WHERE n.k = 1 RETURN [round(n.none, n.r), round(n.none, n.r, 'HALF_UP')]"))
        assertEquals(listOf(listOf(null, null, null)), query("RETURN [round(null, toInteger('-2')), round(null, toInteger('-2'), 'CEILING'), round(null, 1 - 3)]"))
        assertEquals(listOf(listOf(-2L, null, null)), query("UNWIND [null, null] AS x WITH x, -2 AS r RETURN r, round(min(x), r), round(max(x), r, 'UP')"))
        // So far below 0 that the digits a number holds past them, 10^(17 - p), would overflow Oracle's NUMBER.
        assertEquals(listOf(listOf(null, null)), query("UNWIND [-1000] AS p RETURN [round(null, p), round(null, p, 'HALF_EVEN')]"))
    }

    @Test
    fun `a precision written below 0 or past 2147483647 fails only on a row that rounds a number by it, with the compiler's message`() {
        // The spec's cases since #290 and #305, here in a WHERE, an aggregate and a CASE, on rows filtered out, and by a
        // parameter no YAML holds.
        clear()
        seed("CREATE (:Round {x: 1.5, k: 1}), (:Round {k: 2})")
        listOf(
            "MATCH (n:Round) WHERE n.k = 1 RETURN round(n.x, -2)" to emptyMap(),
            "MATCH (n:Round) WHERE round(n.x, 2147483648, 'UP') > 0 RETURN n.k" to emptyMap(),
            "MATCH (n:Round) RETURN sum(round(n.x, -\$p))" to mapOf("p" to 2),
            "MATCH (n:Round) RETURN collect(round(n.x, \$p, 'HALF_UP'))" to mapOf("p" to 1.0e20),
            "RETURN round(1.5, \$p)" to mapOf("p" to Double.POSITIVE_INFINITY),
            "RETURN round(1.5, \$p, 'UP')" to mapOf("p" to Double.NEGATIVE_INFINITY),
            "RETURN round(1.5, -9223372036854775808)" to emptyMap(),
        ).forEach { (statement, parameters) ->
            val failed = assertThrows<Exception>(statement) { query(statement, parameters) }.message!!
            if (compiles) assertTrue(failed.contains("round() takes a precision from 0 to 2147483647, as in Neo4j."), "$statement: $failed")
        }
        // A row that holds no number rounds to null, and a row the CASE does not round, or no row at all, fails nothing.
        assertEquals(listOf(listOf(null, null, null)), query("MATCH (n:Round) WHERE n.k = 2 RETURN [round(n.x, -2), round(n.x, 2147483648, 'UP'), round(n.x, \$p)]", mapOf("p" to 1.0e20)))
        assertEquals(listOf(listOf(null, null)), query("RETURN [round(null, \$p), round(null, \$p, 'FLOOR')]", mapOf("p" to Double.POSITIVE_INFINITY)))
        assertEquals(listOf(0.0, 0.0), query("MATCH (n:Round) RETURN CASE WHEN n.k > 0 THEN 0.0 ELSE round(1.5, -2) + round(n.x, 2147483648) END ORDER BY n.k"))
        assertEquals(listOf(emptyList<Any>()), query("MATCH (n:Round) WHERE n.k = 1 RETURN collect(CASE WHEN n.k = 2 THEN round(n.x, -2, 'UP') END)"))
        assertEquals(emptyList(), query("MATCH (n:Round) WHERE n.k = 3 RETURN [round(1.5, -2), round(1.5, \$p, 'UP')]", mapOf("p" to 1.0e20)))
        assertEquals(emptyList(), query("UNWIND [] AS x RETURN round(1.5, -2)"))
        // Nor in an ORDER BY whose round() reads nothing of the row. Not in a WHERE: Neo4j may test a condition that reads
        // nothing of a row before the rows' own, and Postgres does.
        assertEquals(emptyList(), query("MATCH (n:Round) WHERE n.k = 3 RETURN n.k ORDER BY round(1.5, -2), round(1.5, \$p, 'UP')", mapOf("p" to 1.0e20)))
    }

    @Test
    fun `substring(), left() and right() refuse a written float, even a whole one, and a given start or length out of range, as Neo4j does`() {
        // Beside the spec's cases since v0.6.9 (#263): a start or a length. Neo4j refuses a float it knows to be one whatever the text;
        // a bound one it cuts toward zero. It refuses one out of range only where the text is not null.
        listOf(
            Triple("RETURN left('hello', 2.7)", emptyMap<String, Any?>(), float("left", "length")),
            Triple("RETURN left('hello', 2.0)", emptyMap(), float("left", "length")),
            Triple("RETURN right('hello', -0.5)", emptyMap(), float("right", "length")),
            Triple("RETURN substring('hello', 1.0)", emptyMap(), float("substring", "start")),
            Triple("RETURN substring('hello', 1, 2.6)", emptyMap(), float("substring", "length")),
            Triple("RETURN left(null, 2.7)", emptyMap(), float("left", "length")),
            Triple("RETURN left('hello', -1)", emptyMap(), outOfRange("left", "length")),
            Triple("RETURN right('hello', -1)", emptyMap(), outOfRange("right", "length")),
            Triple("RETURN substring('hello', -1, 2)", emptyMap(), outOfRange("substring", "start")),
            Triple("RETURN substring('hello', 1, -1)", emptyMap(), outOfRange("substring", "length")),
            Triple("RETURN left('hello', 9223372036854775807)", emptyMap(), outOfRange("left", "length")),
            Triple("RETURN substring(\$t, \$s)", mapOf("t" to "hello", "s" to -1L), outOfRange("substring", "start")),
            // Bound, -1.5 is cut to -1, and 1.0E20 is past the greatest.
            Triple("RETURN left('hello', \$n)", mapOf("n" to -1.5), outOfRange("left", "length")),
            Triple("RETURN left('hello', \$n)", mapOf("n" to 1.0e20), outOfRange("left", "length")),
            // Negated, a bound number is negated before it is cut: -2.7 is -2.
            Triple("RETURN left('hello', -\$n)", mapOf("n" to 2.7), outOfRange("left", "length")),
        ).forEach { (statement, parameters, message) ->
            val failed = assertThrows<Exception>(statement) { query(statement, parameters) }
            if (compiles) assertTrue(failed.message!!.contains(message), "$statement: ${failed.message}")
        }
        assertEquals(
            listOf(listOf("he", "lo", "ello", "el", null, null, null, null, null, null, "hello", empty, empty)),
            query(
                "RETURN [left('hello', 2), right('hello', 2), substring('hello', 1), substring('hello', 1, 2), left('hello', null), substring('hello', null), " +
                    "substring('hello', 1, null), left(null, -1), right(null, -1), substring(null, 1, -1), left('hello', 10), left('hello', 0), substring('hello', 10)]",
            ),
        )
        assertEquals(
            listOf(listOf(null, "he", "he", "el", empty, null, "he", null, "h", empty)),
            query(
                "RETURN [left(\$t, \$m), left('hello', \$i), left('hello', \$f), substring('hello', \$s, \$l), left('hello', \$q), right(\$t, \$f), left('hello', \$p.f), left(\$p.none, \$m), left('hello', -\$m), left('hello', -\$q)]",
                mapOf("t" to null, "m" to -1L, "i" to 2L, "f" to 2.7, "s" to 1.7, "l" to 2.6, "q" to -0.5, "p" to mapOf("f" to 2.7)),
            ),
        )
        assertEquals(listOf("he", null), query("UNWIND \$xs AS c RETURN left('hello', c)", mapOf("xs" to listOf(2.7, null))))
        // The greatest start or length is taken: a start past the text gives the empty text, written, bound or from a list.
        assertEquals(
            listOf(listOf(empty, "hello", "hello", "ello", empty)),
            query("RETURN [substring('hello', 2147483647), left('hello', 2147483647), right('hello', 2147483647), substring('hello', 1, 2147483647), substring('hello', \$m)]", mapOf("m" to 2147483647L)),
        )
        assertEquals(listOf(empty), query("UNWIND \$xs AS s RETURN substring('hello', s)", mapOf("xs" to listOf(2147483647L))))
    }

    @Test
    fun `a start or length from data is cut toward zero, and one out of range fails only on a row that reads it, with the compiler's message`() {
        // Not in the spec yet: a start or a length read from data (#263), in a WHERE and an aggregate too, and on rows filtered out.
        clear()
        seed(
            "CREATE (:Count {k: 1, f: 2.7, w: 2.0, q: -0.5, r: -1.5, m: -1, h: 2147483647.9, hp: 2147483648.5, t: 'hello'}), " +
                "(:Count {k: 2, m: 1, t: 'hello'}), (:Count {k: 3, m: -1, r: -1.5, hp: 2147483648.5})",
        )
        listOf(
            "MATCH (n:Count) RETURN left(n.t, n.m)" to outOfRange("left", "length"),
            "MATCH (n:Count) WHERE left(n.t, n.m) = 'h' RETURN n.k" to outOfRange("left", "length"),
            "MATCH (n:Count) RETURN collect(right(n.t, n.m))" to outOfRange("right", "length"),
            "MATCH (n:Count) RETURN substring(n.t, n.m)" to outOfRange("substring", "start"),
            "MATCH (n:Count) RETURN substring(n.t, 0, n.r)" to outOfRange("substring", "length"),
            // -1.5 is cut to -1, and 2147483648.5 to past the greatest.
            "MATCH (n:Count) RETURN left(n.t, n.r)" to outOfRange("left", "length"),
            "MATCH (n:Count) RETURN left(n.t, n.hp)" to outOfRange("left", "length"),
            // Written, below 0, beside a text from data: it fails on the row whose text is not null.
            "MATCH (n:Count) RETURN left(n.t, -1)" to outOfRange("left", "length"),
            // A constant the compiler does not read as given is worked out by the store, as one from data.
            "RETURN left('hello', 1 - 3)" to outOfRange("left", "length"),
            "RETURN substring('hello', toInteger('-2'))" to outOfRange("substring", "start"),
        ).forEach { (statement, message) ->
            val failed = assertThrows<Exception>(statement) { query(statement) }.message!!
            if (compiles) assertTrue(failed.contains(message), "$statement: $failed")
        }
        assertEquals(
            listOf(listOf("he", "lo", "llo", "he", "hello", "he", null, empty)),
            query("MATCH (n:Count) WHERE n.k = 1 RETURN [left(n.t, n.f), right(n.t, n.w), substring(n.t, n.f), substring(n.t, n.q, n.f), left(n.t, n.h), left(n.t, n.f * 1), left(n.none, n.f), left(n.t, n.q)]"),
        )
        assertEquals(
            listOf(listOf(empty, "hello")),
            query("MATCH (n:Count) WHERE n.k = 1 RETURN [substring(n.t, n.h), substring(n.t, 0, n.h)]"),
        )
        // Grouped by, as a key beside an aggregate.
        assertEquals(listOf(listOf("ello", 1L)), query("MATCH (n:Count) WHERE n.k = 2 RETURN substring(n.t, n.m), count(*)"))
        // Out of range beside a null text is null, as in Neo4j, and so is a null start or length.
        assertEquals(listOf(listOf(null, null, null, null)), query("MATCH (n:Count) WHERE n.k = 3 RETURN [left(n.t, n.m), substring(n.t, n.m), left(n.t, -1), right(n.t, n.r)]"))
        // A constant out of range, which the compiler does not read as given, and one past SQL's integer: null beside a null text,
        // and not refused for the row filtered out.
        assertEquals(
            listOf(listOf(null, null, null, null)),
            query("MATCH (n:Count) WHERE n.k = 3 RETURN [left(n.t, 1 - 3), substring(n.t, 0, 1 - 3), left(n.t, n.hp), left(n.t, 2147483647 + 1)]"),
        )
        assertEquals(listOf(listOf(null, null, null)), query("MATCH (n:Count) WHERE n.k = 2 RETURN [left(n.t, n.none), substring(n.t, n.none), substring(n.t, 1, n.none)]"))
        assertEquals(listOf("none", "h", "none"), query("MATCH (n:Count) RETURN CASE WHEN n.m < 0 THEN 'none' ELSE left(n.t, n.m) END ORDER BY n.k"))
    }

    /** The empty text, which Oracle reads as null. */
    private val empty get() = if (pm.type == DatabaseType.ORACLE) null else ""

    /** What the compiler refuses a written float start or length [what] of [function] with. */
    private fun float(function: String, what: String) = "$function() takes an integer for its $what, not a float, as in Neo4j."

    /** What the compiler refuses a start or length [what] of [function] out of range with. */
    private fun outOfRange(function: String, what: String) = "$function() takes a $what from 0 to 2147483647, as in Neo4j."

    @Test
    fun `a sum of a text the statement makes and a text or a float gives the text Cypher writes`() {
        // A side the statement makes a text is written as its own text on a SQL store (#268); a float beside it still as toString writes it.
        assertEquals(
            listOf(listOf("x1B", "aX1", "x12.5", "px1"), listOf("x2B", "aX2", "x22.5", "px2")),
            query("UNWIND [1, 2] AS i WITH 'x' + toString(i) AS t RETURN t + toUpper('b'), 'a' + toUpper(t), t + 2.5, split('p,q', ',')[0] + t ORDER BY t"),
        )
        assertEquals(listOf(listOf("x11.0E7", "bc0.5")), query("WITH 1.0E7 AS f, 0.5 AS h RETURN 'x' + toString(1) + f, substring('abc', 1) + h"))
        assertEquals(listOf("A1.5", "B1.5"), query("UNWIND ['a', 'b'] AS s WITH collect(toUpper(s)) AS ss, 1.5 AS f UNWIND ss AS u RETURN u + f ORDER BY u"))
        // A CASE, a coalesce, a list and a CALL's UNION that may give a float beside a text write the float as toString does.
        assertEquals(
            listOf(listOf("2.5x", "2.5x", "2.5x")),
            query("WITH 2.5 AS f RETURN CASE WHEN f > 1 THEN f ELSE 'a' END + 'x', coalesce(CASE WHEN f < 1 THEN 'a' END, f) + 'x', ['a', f][1] + 'x'"),
        )
        assertEquals(listOf("2.5x", "Ax"), query("CALL { RETURN 2.5 AS v UNION RETURN toUpper('a') AS v } RETURN v + 'x' AS s ORDER BY s"))
        // The least and the greatest of texts are a text, put together with a float as Cypher writes it.
        assertEquals(listOf(listOf("A2.5", "b2.5")), query("UNWIND ['b', 'a'] AS s WITH min(toUpper(s)) AS lo, max(s) AS hi RETURN lo + 2.5, hi + 2.5"))
    }

    @Test
    fun `a text past 4,000 bytes is measured, cut, changed, searched and added to whole, as a short one is`() {
        // Beside the spec's case since v0.6.17 (#298): stored and made by the statement too, and more functions and edge cases.
        // Oracle read a text from JSON as a VARCHAR2, which holds 4,000 bytes, and a longer one as null. One function a
        // statement. Past 32 KB too, given, stored and made: Oracle closed the session (ORA-03113) after statements that read such
        // a text from a property and from a parameter in turn, and its sessions held megabytes for each compiled statement the
        // driver kept, up to the instance's limit (ORA-04036, #313).
        clear()
        for (length in listOf(5_000, 40_000)) {
            val t = " " + "aB".repeat((length - 2) / 2) + " "
            val functions = mapOf(
                "size(t)" to length.toLong(), "toUpper(t)" to t.uppercase(), "toLower(t)" to t.lowercase(), "trim(t)" to t.trim(),
                "ltrim(t)" to t.trimStart(), "rtrim(t)" to t.trimEnd(), "substring(t, 1, size(t) - 2)" to t.substring(1, length - 1),
                "substring(t, 2)" to t.substring(2), "left(t, size(t) - 1)" to t.dropLast(1), "right(t, size(t) - 1)" to t.drop(1),
                "replace(t, 'B', 'bb')" to t.replace("B", "bb"), "split(t, 'B')" to t.split("B"),
                // A separator past 4,000 bytes, too, but within 32 KB, past which Oracle's REPLACE looks for none.
                "split('x' + t + 'y', left(t, 4100))" to ("x" + t + "y").split(t.take(4100)), "split('x', left(t, 4100))" to listOf("x"),
                "t STARTS WITH ' aB'" to true, "t STARTS WITH 'x'" to false, "t STARTS WITH t" to true, "t ENDS WITH 'B '" to true, "t ENDS WITH t" to true,
                "left(t, 10) STARTS WITH t" to false, "left(t, 10) ENDS WITH t" to false,
                // INSTR looks for no text past 32 KB on Oracle.
                "t CONTAINS 'BaB'" to true, "t CONTAINS 'z'" to false, "t CONTAINS left(t, 4100)" to true, "t =~ ' (aB)+ '" to true,
                "t + 'z'" to t + "z", "'z' + t" to "z" + t, "t + t" to t + t, "t + 1" to t + "1", "toString(t)" to t, "isEmpty(t)" to false,
            )
            query("CREATE (n:CyLongText {t: \$t})", mapOf("t" to t))
            val parts = mapOf("a" to t.substring(0, length / 2), "b" to t.substring(length / 2))
            for ((function, expected) in functions) {
                // Given, and stored and read in one row, and made by the statement.
                assertEquals(listOf(expected), query("WITH \$t AS t RETURN $function", mapOf("t" to t)), "$function given, $length")
                assertEquals(listOf(expected), query("MATCH (n:CyLongText) WITH n.t AS t RETURN $function"), "$function stored, $length")
                assertEquals(listOf(expected), query("WITH \$a + \$b AS t RETURN $function", parts), "$function made, $length")
            }
            clear()
        }
        // Two short texts whose sum is past 4,000 bytes.
        assertEquals(listOf(6_000L), query("WITH \$a AS a RETURN size(a + a)", mapOf("a" to "é".repeat(3_000))))
        // Sums at and around 4,000 bytes, of texts of two-byte characters and of a float, put together short on Oracle only where they fit.
        val sides = listOf("é".repeat(1_000) to "é".repeat(1_000), "a".repeat(1_968) to "a".repeat(1_968), "a".repeat(1_969) to "a".repeat(1_968), "a".repeat(2_000) to "a".repeat(2_001), "a".repeat(3_999) to 1.5)
        for ((a, b) in sides) assertEquals(listOf("$a$b"), query("WITH \$a AS a, \$b AS b RETURN a + b", mapOf("a" to a, "b" to b)), "${a.length} + $b")
        // What to replace, and what with, made by the statement, so their lengths are measured for each row: a short text made
        // past 4,000 bytes, made shorter, and made no longer.
        val twoByte = mapOf("a" to "é".repeat(1_500))
        assertEquals(listOf(listOf("é".repeat(3_000), "é".repeat(750), "é".repeat(1_500))),
            query("WITH \$a AS a RETURN replace(a, left(a, 1), left(a, 2)), replace(a, left(a, 2), left(a, 1)), replace(a, left(a, 1), right(a, 1))", twoByte))
        // A null text, and a function of a short text that gives Oracle's empty text, null, are not read again as long ones to give another value.
        for (function in listOf("toUpper(n)", "trim(n)", "substring(n, 1)", "replace(n, 'a', 'b')", "split(n, ',')", "size(n)", "toString(n)", "n + 'a'", "n STARTS WITH 'a'", "n ENDS WITH 'a'", "n CONTAINS 'a'", "n =~ 'a'")) {
            assertEquals(listOf(null), query("WITH \$n AS n RETURN $function", mapOf("n" to null)), function)
        }
        val empty = if (pm.type == DatabaseType.ORACLE) null else ""
        assertEquals(listOf(listOf(empty, empty, empty, empty)), query("WITH \$s AS s, \$w AS w RETURN substring(s, 5), left(s, 0), replace(s, 'abc', ''), trim(w)", mapOf("s" to "abc", "w" to "   ")))
        // Two empty texts put together are empty: on Oracle null, as its empty text is, and not read as a long one.
        assertEquals(listOf(if (pm.type == DatabaseType.ORACLE) null else ""), query("WITH \$e AS e RETURN e + e", mapOf("e" to "")))
    }

    @Test
    fun `a text past 4,000 bytes is reversed whole, as a short one is`() {
        // Oracle reversed a text with LISTAGG, which gives a VARCHAR2 of 4,000 bytes, and read a longer one from JSON as null
        // (#311). Given, stored and read in one row, and made by the statement, as above; reversed by its characters, so a
        // character past U+FFFF, two UTF-16 units, stays whole.
        clear()
        fun reversed(t: String) = t.codePoints().toArray().reversedArray().let { String(it, 0, it.size) }
        val texts = listOf(
            // 5,000 characters, and past 32 KB.
            " " + "aB".repeat(2_499) + "é", "aB".repeat(20_000),
            // Multibyte, under 4,000 characters and past 4,000 bytes; and at 4,000 bytes, short, and one byte past.
            "é中".repeat(1_999) + "x", "é".repeat(2_000), "é".repeat(2_000) + "x",
            // Characters past U+FFFF, two UTF-16 units each: across each 1,000th unit, where Oracle cuts a long text, and reversed,
            // across each other unit; then the other way about.
            "x" + "😀".repeat(1_500) + "aé" + "😀".repeat(1_000), "😀".repeat(5_000) + "x",
            // One that ends on a character across the 2,000th unit, so the last piece Oracle cuts is empty; and a piece of the
            // most bytes, 999 three-byte characters and one of four.
            "x" + "😀".repeat(1_000), "中".repeat(999) + "😀" + "中".repeat(1_000),
            // What JSON writes otherwise: quotes, a backslash, a line break and a control character.
            "a\"\",\"x\\\n\u0001".repeat(800),
            "abc", "a😀é中",
        )
        for (t in texts) {
            val label = "${t.length} characters, ${t.toByteArray().size} bytes"
            query("CREATE (n:CyLongText {t: \$t})", mapOf("t" to t))
            val parts = mapOf("a" to t.substring(0, t.offsetByCodePoints(0, t.codePointCount(0, t.length) / 2)))
                .let { it + ("b" to t.substring(it.getValue("a").length)) }
            assertEquals(listOf(reversed(t)), query("WITH \$t AS t RETURN reverse(t)", mapOf("t" to t)), "given, $label")
            assertEquals(listOf(reversed(t)), query("MATCH (n:CyLongText) RETURN reverse(n.t)"), "stored, $label")
            assertEquals(listOf(reversed(t)), query("MATCH (n:CyLongText) WITH n.t AS t RETURN reverse(t)"), "passed on, $label")
            assertEquals(listOf(listOf(reversed(t))), query("MATCH (n:CyLongText) RETURN collect(reverse(n.t))"), "collected, $label")
            // Not where a sum on Oracle puts a character past U+FFFF across its 4,000th UTF-16 unit: the sum gives two U+FFFD there.
            if (pm.type != DatabaseType.ORACLE || t.length == t.codePointCount(0, t.length)) {
                assertEquals(listOf(reversed(t)), query("WITH \$a + \$b AS t RETURN reverse(t)", parts), "made, $label")
            }
            assertEquals(listOf(t), query("WITH \$t AS t RETURN reverse(reverse(t))", mapOf("t" to t)), "twice, $label")
            // In a statement that writes, which Oracle runs as a PL/SQL block.
            assertEquals(listOf(reversed(t)), query("MATCH (n:CyLongText) SET n.r = reverse(n.t) RETURN n.r"), "written, $label")
            clear()
        }
        // Several in one statement, each row and each item reversed from its own text, long and short.
        val rows = listOf("aB".repeat(3_000), "abc", "x" + "😀".repeat(1_500), "é中".repeat(1_999) + "x")
        assertEquals(rows.map(::reversed), query("UNWIND \$ts AS t RETURN reverse(t)", mapOf("ts" to rows)))
        assertEquals(listOf(rows.map(::reversed)), query("RETURN [t IN \$ts | reverse(t)]", mapOf("ts" to rows)))
        // A null text is null, and an empty one empty: on Oracle null, as its empty text is, and not read again as a long one.
        assertEquals(listOf(null), query("WITH \$n AS n RETURN reverse(n)", mapOf("n" to null)))
        assertEquals(listOf(if (pm.type == DatabaseType.ORACLE) null else ""), query("WITH \$e AS e RETURN reverse(e)", mapOf("e" to "")))
        // A literal, a text or a list, whose CLOB Oracle bound without a type, and found no DBMS_LOB.SUBSTR for (PLS-307).
        assertEquals(listOf(listOf("unaeK", listOf(3L, 2L, 1L))), query("RETURN [reverse('Keanu'), reverse([1, 2, 3])]"))
    }

    @Test
    fun `a stored text past 32 KB, read in one row, is measured and changed whole, passed on by a WITH or read where it is used`() {
        // Beside the spec's writes case since v0.6.17 (#298): read where it is used too, and by more functions.
        // On Oracle a WITH passes a property on read through its text (#255), and a function reads a text past 4,000 bytes as a
        // CLOB (#298): the two together. One node: Oracle crashes on a long property read from two rows (#255).
        clear()
        val t = "aB".repeat(20_000)
        query("CREATE (n:CyLongText {t: \$t})", mapOf("t" to t))
        val functions = listOf(
            "size(#)" to 40_000L, "toUpper(#)" to t.uppercase(), "substring(#, 2)" to t.substring(2), "replace(#, 'B', 'b')" to t.lowercase(),
            "# ENDS WITH 'aB'" to true, "size(# + 'z')" to 40_001L, "toString(#)" to t,
        )
        for ((function, expected) in functions) {
            assertEquals(listOf(expected), query("MATCH (n:CyLongText) WITH n.t AS t RETURN ${function.replace("#", "t")}"), "$function, passed on")
            assertEquals(listOf(expected), query("MATCH (n:CyLongText) RETURN ${function.replace("#", "n.t")}"), "$function, read where used")
        }
        clear()
    }

    @Test
    fun `texts compare and sort by their code points, not by a locale's collation`() {
        // Beside the spec's cases since v0.6.10 (#273): Postgres compared and sorted texts by the database's collation.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(" ", "-", "A", "B", "John Wick", "Johnny Mnemonic", "Z", "_", "a", "a b", "a-c", "ab", "b", "e", "z", "~", "é"),
            query("UNWIND ['John Wick', 'Johnny Mnemonic', 'b', 'B', 'a', 'A', 'é', 'e', 'z', 'Z', '~', '_', '-', ' ', 'a b', 'ab', 'a-c'] AS t RETURN t ORDER BY t"),
        )
        assertEquals(
            listOf(listOf("é", "b", "a", "Johnny Mnemonic", "John Wick", "B")),
            query("UNWIND ['John Wick', 'Johnny Mnemonic', 'b', 'B', 'a', 'é'] AS t WITH t ORDER BY t DESC RETURN collect(t)"),
        )
        assertEquals(listOf("B", "a", "b"), query("UNWIND ['b', 'B', 'b', 'a', 'B'] AS t RETURN DISTINCT t ORDER BY t"))
        assertEquals(
            listOf(listOf(false, true, true, true, true, false)),
            query("RETURN ['a' < 'B', 'John Wick' < 'Johnny Mnemonic', 'é' > 'f', 'Z' < '_', 'a' >= 'B', 'ab' <= 'a b']"),
        )
        assertEquals(listOf(listOf("B", "é")), query("UNWIND ['b', 'B', 'a', 'é'] AS t RETURN min(t), max(t)"))
        assertEquals(
            listOf("B", "John Wick", "Johnny Mnemonic", "a", "b"),
            query("UNWIND \$ts AS t RETURN t ORDER BY t", mapOf("ts" to listOf("b", "B", "John Wick", "Johnny Mnemonic", "a"))),
        )
        // A property compared with a text, and sorted: 'The Matrix' is before 'The matrix', which a locale puts first.
        assertEquals(
            listOf("Johnny Mnemonic", "The Matrix", "The Matrix Reloaded"),
            query("MATCH (m:Movie) WHERE m.title < \$t RETURN m.title ORDER BY m.title", mapOf("t" to "The matrix")),
        )
        assertEquals(emptyList(), query("MATCH (m:Movie) WHERE m.title >= 'the' RETURN m.title"))
        assertEquals(
            listOf(listOf("The Matrix", "The Matrix Reloaded", "Johnny Mnemonic")),
            query("MATCH (m:Movie) WITH m.title AS t ORDER BY toLower(t) + 'A' DESC RETURN collect(t)"),
        )
        assertEquals(
            listOf(listOf("The Matrix", "johnny mnemonic")),
            query("MATCH (m:Movie) WITH CASE WHEN m.released < 1996 THEN toLower(m.title) ELSE m.title END AS t RETURN min(t), max(t)"),
        )
        assertEquals(listOf(0L), query("MATCH (p:Person), (q:Person) WHERE toLower(p.name) < q.name RETURN count(*)"))
        // A sort by a list, and min and max of lists, compare the texts in them so too.
        assertEquals(
            listOf(listOf("B"), listOf("John Wick"), listOf("Johnny Mnemonic"), listOf("a", "B"), listOf("a", "b"), listOf("a", "~"), listOf("a", "é"), listOf("b")),
            query("UNWIND [['a', 'b'], ['a', 'B'], ['B'], ['b'], ['John Wick'], ['Johnny Mnemonic'], ['a', 'é'], ['a', '~']] AS l RETURN l ORDER BY l"),
        )
        assertEquals(listOf(listOf(listOf("B"), listOf("b"))), query("UNWIND [['a', 'b'], ['a', 'B'], ['B'], ['b']] AS l RETURN min(l), max(l)"))
        // Long texts too: Postgres keys the first 500 characters of a text in a list by their code points.
        val (a499, a600) = "a".repeat(499) to "a".repeat(600)
        assertEquals(
            listOf("1", "2", "4", "5", "3"),
            query(
                "UNWIND \$ls AS g WITH [g[0], g[1]] AS l RETURN l[1] ORDER BY l",
                mapOf("ls" to listOf(listOf(a499 + "b", "3"), listOf(a499 + "B", "2"), listOf(a499, "1"), listOf(a600 + "b", "5"), listOf(a600, "4"))),
            ),
        )
        assertEquals(
            listOf(listOf("the matrix"), listOf("johnny mnemonic", "the matrix", "the matrix reloaded", "The Matrix"), listOf("The matrix")),
            query(
                "MATCH (m:Movie) WITH m ORDER BY m.released WITH collect(toLower(m.title)) + ['The Matrix'] AS ts " +
                    "UNWIND [ts, ['the matrix'], ['The matrix']] AS l RETURN l ORDER BY l DESC"
            ),
        )
    }

    @Test
    fun `a character above U+FFFF sorts after every one below it, as its code point says`() {
        // Beside the spec's cases since v0.6.10 (#273): Java's UTF-16 puts such a character before U+E000 to U+FFFF, as Neo4j compares a text given.
        val (smile, tilde, privateUse) = listOf("\uD83D\uDE00", "\uFF5E", "\uE000")
        assertEquals(listOf("a", "\uD7FF", privateUse, tilde, smile), query("UNWIND ['$smile', '$tilde', '$privateUse', 'a', '\uD7FF'] AS t RETURN t ORDER BY t"))
        assertEquals(listOf(listOf(false, true, true)), query("RETURN ['$smile' < '$tilde', '$smile' > '$privateUse', '$smile' > '\uD7FF']"))
        assertEquals(listOf(listOf(tilde), listOf(smile)), query("UNWIND [['$smile'], ['$tilde']] AS l RETURN l ORDER BY l"))
        // Neo4j compares a text given by its UTF-16 code units, where it compares one written or stored by its code points (neo4j/neo4j#13997).
        if (pm.type != DatabaseType.NEO4J) {
            assertEquals(listOf(listOf(privateUse, smile)), query("UNWIND \$ts AS t RETURN min(t), max(t)", mapOf("ts" to listOf(smile, tilde, privateUse))))
        }
    }

    @Test
    fun `a text in a list sorts by its code points up to and across its 500th character`() {
        // Beside the spec's cases since v0.6.10 (#273): Postgres keys a text in a list by the bits of its first 500 characters, then by the rest. 500
        // characters of four bytes are 16,000 bits, near the 16,383 places Postgres holds; the 500th may be of 1 to 4 bytes.
        val smile = "😀"
        val (s499, s500) = smile.repeat(499) to smile.repeat(500)
        val texts = listOf(s499, s499 + "a", s499 + "～", s500, s500 + "a", s500 + "b")
        val written = texts.reversed().joinToString(", ") { "['$it']" }
        assertEquals(texts.map { listOf(it) }, query("UNWIND [$written] AS l RETURN l ORDER BY l"))
        assertEquals(texts.reversed().map { listOf(it) }, query("UNWIND [$written] AS l RETURN l ORDER BY l DESC"))
        val (a499, a500) = "a".repeat(499) to "a".repeat(500)
        assertEquals(
            listOf(listOf(a499 + "B"), listOf(a500), listOf(a500 + "B"), listOf(a499 + "b"), listOf(a499 + "é")),
            query("UNWIND [['${a500}B'], ['$a500'], ['${a499}é'], ['${a499}b'], ['${a499}B']] AS l RETURN l ORDER BY l"),
        )
    }

    @Test
    fun `a text sorts beside a null and a number as before, and by its code points among the texts`() {
        // Beside the spec's cases since v0.6.10 (#273): Postgres sorts by a row of the value, each text blanked, and of the text by its code points.
        assertEquals(listOf("B", "b", null), query("UNWIND ['b', null, 'B'] AS t RETURN t ORDER BY t"))
        assertEquals(listOf(null, "b", "B"), query("UNWIND ['b', null, 'B', 'b'] AS t RETURN DISTINCT t ORDER BY t DESC"))
        assertEquals(listOf("B", "b", 1L, 2L, null), query("UNWIND [2, 'b', null, 1, 'B'] AS t RETURN t ORDER BY t"))
        assertEquals(listOf(null, 2L, 1L, "b", "B"), query("UNWIND [2, 'b', null, 1, 'B'] AS t RETURN t ORDER BY t DESC"))
        // A text compared with a value of another kind is null, and min and max of several kinds take the least text by its code points.
        assertEquals(listOf(listOf(null, null, null, false, false)), query("RETURN ['a' < 1, 'B' > null, 1 >= 'a', coalesce(null, 'a') < 'B', CASE WHEN true THEN 'a' END < 'B']"))
        assertEquals(listOf("B"), query("UNWIND ['b', 1, 'B', true] AS t WITH t WHERE t < 'a' RETURN t"))
        assertEquals(listOf(1L), query("UNWIND ['b', 1, 'B', true] AS t WITH t WHERE t > 0 RETURN t"))
        assertEquals(listOf(listOf("B", "b")), query("UNWIND ['b', 'B', 'a', 'B'] AS t RETURN min(DISTINCT t), max(DISTINCT t)"))
        assertEquals(listOf(listOf("B", 2L)), query("UNWIND ['b', 1, 'B', 2, true] AS t RETURN min(t), max(t)"))
    }

    @Test
    fun `values of several kinds sort as Cypher orders their kinds, a map, a list, a text, a truth value, a number, then null`() {
        // Beside the spec's case since v0.6.16 (#299): Oracle and SQLite sort first by where a value's kind comes, then by the value.
        // Postgres takes jsonb's order for every kind but a text: a number before a truth value, a map and a list last (#307).
        if (pm.type == DatabaseType.POSTGRES) return
        val values = "[1, {a: 1}, 'b', true, [2], 2.5, null, false, 'A', [1, 2], -1]"
        val ascending = listOf(mapOf("a" to 1L), listOf(1L, 2L), listOf(2L), "A", "b", false, true, -1L, 1L, 2.5, null)
        // Carried out of a CALL { … }, grouped by, kept distinct by another name, and sorted by when it is not returned.
        assertEquals(listOf(mapOf("a" to 1L), listOf(1L, 2L), listOf(2L), "A"), query("CALL { UNWIND $values AS t RETURN t ORDER BY t LIMIT 4 } RETURN t"))
        assertEquals(
            listOf(listOf(listOf(1L), 1L), listOf("b", 2L), listOf(true, 1L), listOf(2L, 2L)),
            query("UNWIND [2, 'b', true, 2, 'b', [1]] AS t RETURN t, count(*) ORDER BY t"),
        )
        assertEquals(listOf(2L, true, "b"), query("UNWIND [2, 'b', true, 2] AS t RETURN DISTINCT t AS u ORDER BY u DESC"))
        assertEquals(listOf("b", "true", "2"), query("UNWIND [2, 'b', true] AS t RETURN toString(t) AS s ORDER BY t"))
        // Among their own kind: texts by their code points, and whole and floating-point numbers as numbers.
        assertEquals(listOf("a", "～", "😀", 1L), query("UNWIND ['😀', '～', 1, 'a'] AS t RETURN t ORDER BY t"))
        assertEquals(listOf(true, 0L, 0.5, 1L, 1.5), query("UNWIND [1, 0.5, true, 1.5, 0] AS t RETURN t ORDER BY t"))
        // Paged by the query that returns them, and nulls first descending, carried out of a CALL { … }.
        assertEquals(listOf(listOf(2L), "A", "b", false, true), query("UNWIND $values AS t RETURN t ORDER BY t SKIP 2 LIMIT 5"))
        assertEquals(listOf(2.5, 1L, -1L, true), query("UNWIND $values AS t RETURN t ORDER BY t DESC SKIP 1 LIMIT 4"))
        assertEquals(listOf(null, null, 1L), query("CALL { UNWIND [1, null, 'a', true, null, [1]] AS t RETURN t ORDER BY t DESC LIMIT 3 } RETURN t"))
        // Collected in the order a WITH gives them, and indexed or iterated there.
        assertEquals(listOf(ascending.dropLast(1).reversed()), query("UNWIND $values AS t WITH t ORDER BY t DESC WITH collect(t) AS xs RETURN xs"))
        assertEquals(listOf(ascending.take(3)), query("UNWIND $values AS t WITH t ORDER BY t RETURN [x IN collect(t) | x][0..3]"))
        assertEquals(listOf(listOf(listOf(2L), "A", "b", false, true)), query("UNWIND $values AS t WITH t ORDER BY t SKIP 2 LIMIT 5 RETURN collect(t)"))
        // The last query of a UNION sorted, and a UNION carried out of a CALL { … } sorted and paged.
        assertEquals(listOf(2L, "b", true, mapOf("a" to 1L), listOf(1L), "a", null), query("UNWIND [2, 'b', true] AS t RETURN t UNION UNWIND [[1], {a: 1}, null, 'a'] AS t RETURN t ORDER BY t"))
        assertEquals(listOf(listOf(1L), "b", true, 2L, null), query("CALL { UNWIND [2, 'b'] AS t RETURN t UNION UNWIND [true, [1], null] AS t RETURN t } RETURN t ORDER BY t"))
        assertEquals(listOf(2L, true, "b"), query("CALL { UNWIND [2, 'b'] AS t RETURN t UNION UNWIND [true, [1], null] AS t RETURN t } WITH t ORDER BY t DESC SKIP 1 LIMIT 3 RETURN t"))
    }

    @Test
    fun `a property stored as several kinds sorts as Cypher orders their kinds`() {
        // Beside the spec's case since v0.6.16 (#299): a property read from the store may be of any kind, so its kind is ranked first.
        // Postgres puts a number before a truth value, and a list last (#307).
        if (pm.type == DatabaseType.POSTGRES) return
        clear()
        seed("CREATE (:Kind {v: 2}), (:Kind {v: 'b'}), (:Kind {v: true}), (:Kind {v: [1]}), (:Kind {v: 1.5}), (:Kind {v: 'B'}), (:Kind {v: false}), (:Kind)")
        assertEquals(listOf(null, 2L), query("CALL { MATCH (k:Kind) RETURN k.v AS v ORDER BY v DESC LIMIT 2 } RETURN v"))
        assertEquals(listOf(listOf(listOf(1L), "B", "b", false, true, 1.5, 2L)), query("MATCH (k:Kind) WITH k ORDER BY k.v WITH collect(k.v) AS vs RETURN vs"))
        assertEquals(listOf(2L, 1.5, true), query("MATCH (k:Kind) RETURN k.v ORDER BY k.v DESC SKIP 1 LIMIT 3"))
    }

    @Test
    fun `lists of several kinds of item, nested, sort item by item among values of other kinds`() {
        // Not in the spec yet (#299): a list sorts first among values of other kinds, and among lists item by item, the
        // items of each as Cypher orders their kinds at any depth. Postgres takes jsonb's order (#307), and Oracle puts a
        // number before a text and a null first within a list (#205).
        if (pm.type == DatabaseType.POSTGRES || pm.type == DatabaseType.ORACLE) return
        val values = "[[1, 'a'], 'x', [[2], 1], [[2], 'b'], [1, null], 3, [1], [[2], [1]], [{a: 1}], [true]]"
        val ascending = listOf(
            listOf(mapOf("a" to 1L)), listOf(listOf(2L), listOf(1L)), listOf(listOf(2L), "b"), listOf(listOf(2L), 1L),
            listOf(true), listOf(1L), listOf(1L, "a"), listOf(1L, null), "x", 3L,
        )
        assertEquals(ascending, query("UNWIND $values AS t RETURN t ORDER BY t"))
        assertEquals(ascending.reversed(), query("UNWIND $values AS t RETURN t ORDER BY t DESC"))
        assertEquals(ascending.take(4), query("CALL { UNWIND $values AS t RETURN t ORDER BY t LIMIT 4 } RETURN t"))
    }

    @Test
    fun `a text sorts by its code points where its sort key is long enough to be worked out in a subquery`() {
        // Beside the spec's cases since v0.6.10 (#273): Postgres binds a long term once in a subquery, which gives its sort key as a row.
        val key = "coalesce(toLower(toUpper(trim(t))), toUpper(t), 'zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz') + t"
        assertEquals(listOf("a", "B", "b"), query("UNWIND ['b', 'B', 'a'] AS t RETURN DISTINCT t ORDER BY $key"))
        assertEquals(listOf("b", "B", "a"), query("UNWIND ['b', 'B', 'a'] AS t RETURN DISTINCT t ORDER BY $key DESC"))
    }

    @Test
    fun `a day and a moment are their ISO text`() {
        // A graph engine's driver gives a day and a moment as values of its own.
        if (!compiles) return
        val (day, moment) = query("RETURN [date(), localdatetime()]").single() as List<*>
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(day as String), day)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6})?").matches(moment as String), moment)
    }

    /**
     * Runs [block] with a query of the statements it is given. On Oracle they run on one session of their own, which is
     * asked again after each: a statement that crashes the session can do so after its rows came back.
     */
    protected open fun onOneSession(block: (query: (String) -> List<Any?>) -> Unit) = block { query(it) }

    @Test
    fun `an OPTIONAL CALL or an OPTIONAL MATCH in a COUNT or an EXISTS subquery keeps the row it finds nothing for`() {
        // Beside the spec's case since v0.6.17 (#338). Oracle refused an optional join on TRUE in a scalar subquery followed by WHERE … AND … (ORA-00907),
        // and crashed the session on one without (ORA-03113, ORA-07445 in its alert log) (#338): it is joined on 1 = 1 there.
        clear()
        seed("CREATE (:CyOpt {id: 1}), (:CyOpt {k: 'p'}), (:CyOpt {k: 'q'}), (:CyOpt {k: 'q'})")
        val none = "OPTIONAL CALL { MATCH (z:Nope) RETURN z.k AS u }"
        onOneSession { query ->
            fun counted(subquery: String) = query("MATCH (n:CyOpt {id: 1}) RETURN COUNT { $subquery }")
            // At the top of a statement, as it ran before.
            assertEquals(listOf(null), query("$none RETURN u"))
            assertEquals(listOf(2L), query("$none MATCH (y:CyOpt) WHERE y.k = 'q' RETURN count(y)"))
            // In a COUNT { }: refused on Oracle, with a WHERE … AND … after the join, and else crashed the session.
            assertEquals(listOf(2L), counted("$none MATCH (y:CyOpt) WHERE y.k = 'q' RETURN y"))
            assertEquals(listOf(1L), counted("$none RETURN u"))
            assertEquals(listOf(4L), counted("$none MATCH (y:CyOpt) RETURN y"))
            assertEquals(listOf(0L), counted("MATCH (y:CyOpt) $none WITH y, u WHERE y.k = u RETURN y"))
            // What a CALL that finds something gives is counted, and a plain CALL that finds nothing gives no row.
            assertEquals(listOf(4L), counted("OPTIONAL CALL { MATCH (z:CyOpt) WHERE z.k = 'q' RETURN z.k AS u } MATCH (y:CyOpt) WHERE y.k = u RETURN y"))
            assertEquals(listOf(0L), counted("CALL { MATCH (z:Nope) RETURN z.k AS u } MATCH (y:CyOpt) WHERE y.k = 'q' RETURN y"))
            // An OPTIONAL MATCH is the same join.
            assertEquals(listOf(2L), counted("OPTIONAL MATCH (z:Nope) MATCH (y:CyOpt) WHERE y.k = 'q' RETURN y"))
            assertEquals(listOf(1L), counted("OPTIONAL MATCH (z:Nope) RETURN z"))
            // In an EXISTS { }, and in a WHERE.
            assertEquals(listOf(listOf(true, false)), query("MATCH (n:CyOpt {id: 1}) RETURN [EXISTS { $none RETURN u }, EXISTS { CALL { MATCH (z:Nope) RETURN z.k AS u } RETURN u }]"))
            assertEquals(listOf(1L), query("MATCH (n:CyOpt) WHERE COUNT { $none RETURN u } = 1 AND n.id = 1 RETURN n.id"))
        }
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

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
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

    override fun onOneSession(block: (query: (String) -> List<Any?>) -> Unit) {
        val connection = OracleTestDatabase.connections(manager).connect()
        fun query(cypher: String): List<Any?> = connection.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        try {
            block { cypher -> query(cypher).also { assertEquals(listOf(1L), query("RETURN 1"), "the session after: $cypher") } }
        } finally {
            connection.release()
        }
    }
}

class CypherQuerySqliteTest : CypherQueryContract() {
    private val manager = SqliteTestDatabase.graphManager("cypher")
    override val pm: PersistenceManager get() = manager
    override val compiles = true
    override fun seed(cypher: String) = SqliteTestDatabase.seed(manager, cypher)
    override fun clear() = SqliteTestDatabase.clear(manager)
}
