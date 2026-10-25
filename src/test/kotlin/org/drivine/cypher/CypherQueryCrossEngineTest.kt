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
    fun `round() reads a precision from data, or a whole float, as a whole number toward zero, and a null as null`() {
        // Not in the spec yet: its round() case takes the precision as written or given.
        clear()
        seed("CREATE (:Round {p: 1.7, q: -0.5, w: 2.0, r: -2})")
        assertEquals(
            listOf(listOf(2.6, 2.6, 3.0, 3.0, 2.56, 2.56)),
            query("MATCH (n:Round) RETURN [round(2.555, n.p), round(2.55, n.p, 'UP'), round(2.5, n.q), round(2.5, n.q, 'HALF_UP'), round(2.555, n.w), round(2.555, 2.0, 'HALF_UP')]"),
        )
        assertEquals(listOf(listOf(3.0, -2.0, 2.0, 3.0, 2.0, -3.0)), query("RETURN [round(2.5, 0, 'HALF_UP'), round(-2.5, 0, 'HALF_EVEN'), round(2.5, 0, 'DOWN'), round(2.1, 0, 'CEILING'), round(2.9, 0, 'FLOOR'), round(-2.1, 0, 'UP')]"))
        assertEquals(listOf(listOf(null, null, null, null)), query("RETURN [round(null, 2), round(2.5, null), round(null, 2, 'UP'), round(2.5, null, 'UP')]"))
        // Oracle overflows on a precision past its NUMBER range.
        if (pm.type != DatabaseType.ORACLE) assertEquals(listOf(listOf(2.5, 2.5, 2.5)), query("RETURN [round(2.5, 400), round(2.5, 400, 'HALF_UP'), round(2.5, 400, 'CEILING')]"))
        // A negative precision from data fails when the row is read, on every store, in both forms.
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(1234.5, n.r, 'HALF_UP')") }
        assertThrows<Exception> { query("MATCH (n:Round) RETURN round(1234.5, n.r)") }
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
        ).forEach { statement ->
            val failed = assertThrows<Exception>(statement) { query(statement) }.message!!
            if (compiles) assertTrue(failed.contains("round() takes a precision of 0 or more, as in Neo4j."), failed)
        }
        assertEquals(listOf(1234.5), query("MATCH (n:Round) WHERE n.r >= 0 RETURN round(1234.5, n.r)"))
        assertEquals(listOf(1234.5), query("MATCH (n:Round) WHERE n.k = 2 RETURN sum(round(1234.5, n.r))"))
        assertEquals(listOf(0.0, 1234.5), query("MATCH (n:Round) RETURN CASE WHEN n.r < 0 THEN 0.0 ELSE round(1234.5, n.r) END ORDER BY n.k"))
        assertEquals(listOf(listOf(1234.5, 1234.5)), query("RETURN [round(1234.5, 3 - 1), round(1234.5, toInteger('2'))]"))
    }

    @Test
    fun `a day and a moment are their ISO text`() {
        // A graph engine's driver gives a day and a moment as values of its own.
        if (!compiles) return
        val (day, moment) = query("RETURN [date(), localdatetime()]").single() as List<*>
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(day as String), day)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6})?").matches(moment as String), moment)
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
}

class CypherQuerySqliteTest : CypherQueryContract() {
    private val manager = SqliteTestDatabase.graphManager("cypher")
    override val pm: PersistenceManager get() = manager
    override val compiles = true
    override fun seed(cypher: String) = SqliteTestDatabase.seed(manager, cypher)
    override fun clear() = SqliteTestDatabase.clear(manager)
}
