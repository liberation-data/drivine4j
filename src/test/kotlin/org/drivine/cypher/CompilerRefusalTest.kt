package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A statement the compiler cannot make SQL of, or that is wrong as Cypher is, is refused when it is compiled,
 * with what is wrong, on each store alike: before the store is sent anything. The statements and messages are
 * the spec's, in `cypher/refusals.yaml`.
 */
class CompilerRefusalTest {

    private val spec = CypherSpec.load("refusals.yaml")

    @TestFactory
    fun postgres() = spec.tests("postgres", before = {}) { step -> PostgresCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @TestFactory
    fun oracle() = spec.tests("oracle", before = {}) { step -> OracleCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @TestFactory
    fun sqlite() = spec.tests("sqlite", before = {}) { step -> SqliteCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @Test
    fun `collect(DISTINCT) beside a variable no other item is, outside its aggregates, is refused as collect() is`() {
        // Not in the spec yet: aggregatesFirst groups by each such variable, so it is refused before.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("MATCH (p:Person) RETURN p.born, collect(DISTINCT p.name) + p.name") }.message!!
            assertTrue(refused.contains("names p outside its aggregates"), refused)
        }
    }

    @Test
    fun `round() compiles a precision below 0 or past 2147483647, written, negated or given, to fail on the row that rounds a number by it`() {
        // Neo4j reads the precision only when it rounds (#305), so none is refused when compiling, in both forms.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "RETURN round(1234.5, -2)" to emptyMap(),
                "RETURN round(1234.5, -1.2, 'UP')" to emptyMap(),
                "RETURN round(1234.5, -\$p)" to mapOf("p" to 2),
                "RETURN round(1234.5, \$p)" to mapOf("p" to -2.5),
                "RETURN round(1234.5, 2147483648)" to emptyMap(),
                "RETURN round(1234.5, \$p, 'HALF_UP')" to mapOf("p" to Double.POSITIVE_INFINITY),
            ).forEach { (cypher, parameters) ->
                val sql = compiler.compile(cypher, parameters).sql
                assertTrue(sql.contains("round() takes a precision from 0 to 2147483647, as in Neo4j."), "${compiler::class.simpleName}: $cypher: $sql")
            }
            // -0.5 and -0.9 cut toward zero are 0 places, and 2147483647.9 is 2147483647: none fails.
            listOf(
                "RETURN [round(1234.5, -0.5), round(1234.5, \$p, 'HALF_UP')]" to mapOf("p" to -0.9),
                "RETURN [round(1234.5, 2147483647), round(1234.5, \$p, 'UP')]" to mapOf("p" to 2147483647.9),
            ).forEach { (cypher, parameters) ->
                val sql = compiler.compile(cypher, parameters).sql
                assertTrue(!sql.contains("round() takes a precision"), "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }

    @Test
    fun `toString of a list or a map the statement makes is refused, as Neo4j refuses it`() {
        // #349: Postgres and SQLite gave the list's JSON text, each spaced its own way, and Oracle null. The spec's refusals hold the rest.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "WITH [1, 2] AS l RETURN toString(l)" to "list",
                "RETURN toString({a: 1})" to "map",
            ).forEach { (cypher, kind) ->
                val refused = assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message!!
                assertEquals("toString() takes a number, a text, a truth value or a temporal value, not a $kind, as in Neo4j.", refused)
            }
            // A value whose kind the statement leaves open is compiled, as Neo4j plans it.
            compiler.compile("UNWIND [1, 'a'] AS x RETURN toString(x)")
            compiler.compile("MATCH (n) RETURN toString(n.x)")
        }
    }

    @Test
    fun `a value the statement writes as one of a kind an operator or a function does not take is refused, as Neo4j refuses it`() {
        // #420: each gave null, false or a value. In the spec's refusals from v0.6.20; until this copy moves to it, held here too.
        val refused = listOf(
            "RETURN 123 AND true" to "AND takes a truth value, not an integer, as in Neo4j.",
            "RETURN false OR 'foo'" to "OR takes a truth value, not a text, as in Neo4j.",
            "RETURN [true] XOR false" to "XOR takes a truth value, not a list, as in Neo4j.",
            "RETURN null AND {}" to "AND takes a truth value, not a map, as in Neo4j.",
            "RETURN NOT -1.5" to "NOT takes a truth value, not a float, as in Neo4j.",
            "WITH 123 AS x WITH x AS y RETURN NOT y" to "NOT takes a truth value, not an integer, as in Neo4j.",
            "MATCH (n) WHERE 1 RETURN n" to "An integer is not a condition, as in Neo4j.",
            "WITH 'a' AS x MATCH (n) WHERE x RETURN n" to "A text is not a condition, as in Neo4j.",
            "RETURN CASE WHEN [] THEN 'a' END" to "A list is not a condition, as in Neo4j.",
            "RETURN [x IN [1, 2] WHERE 'a']" to "A text is not a condition, as in Neo4j.",
            "RETURN any(x IN [1] WHERE 1.5)" to "A float is not a condition, as in Neo4j.",
            "WITH 123 AS x RETURN x.num" to "An integer has no properties.",
            "WITH 'abc' AS s MATCH (n) RETURN s.size" to "A text has no properties.",
            "WITH [1] AS l RETURN l.num" to "A list has no properties.",
            "WITH true AS b RETURN b.num" to "A truth value has no properties.",
            "WITH true AS list, 0 AS idx RETURN list[idx]" to "A truth value is not a list or a map, and is indexed as one, as in Neo4j.",
            "RETURN 'abc'[0]" to "A text is not a list or a map, and is indexed as one, as in Neo4j.",
            "WITH [1, 2, 3] AS list, 4.7 AS idx RETURN list[idx]" to "A list is indexed by an integer, not a float, as in Neo4j.",
            "RETURN [1, 2, 3]['1']" to "A list is indexed by an integer, not a text, as in Neo4j.",
            "RETURN {a: 1}[1]" to "A map is indexed by a text, not an integer, as in Neo4j.",
            "RETURN 1 IN 123" to "IN takes a list, not an integer, as in Neo4j.",
            "WITH {x: []} AS m RETURN 1 IN m" to "IN takes a list, not a map, as in Neo4j.",
            "RETURN range(0, 1, 1.1)" to "range() takes integers, not a float, as in Neo4j.",
            "RETURN range(true, 1)" to "range() takes integers, not a truth value, as in Neo4j.",
            "RETURN range(2, 8, 0)" to "range() takes a step other than 0, as in Neo4j.",
            "RETURN range(2, 8, -0)" to "range() takes a step other than 0, as in Neo4j.",
            "RETURN properties(1)" to "properties() takes a node, a relationship or a map, not an integer, as in Neo4j.",
            "WITH 'Cypher' AS t RETURN properties(t)" to "properties() takes a node, a relationship or a map, not a text, as in Neo4j.",
            "RETURN properties([true, false])" to "properties() takes a node, a relationship or a map, not a list, as in Neo4j.",
            "CREATE (a) SET a.maplist = [{num: 1}]" to
                "A property is a number, a text, a truth value, a temporal value or a list of them, not a list of maps or lists, as in Neo4j.",
            "MATCH (a) SET a.m = {num: 1}" to "A property is a number, a text, a truth value, a temporal value or a list of them, not a map, as in Neo4j.",
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                assertEquals(message, assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message, "${compiler::class.simpleName}: $cypher")
            }
            assertEquals(
                "range() takes a step other than 0, as in Neo4j.",
                assertThrows<DrivineException> { compiler.compile("RETURN range(2, 8, \$s)", mapOf("s" to 0)) }.message,
            )
            // A value whose kind is known only when the row is read, a property, a parameter or an unwound item, is compiled, as
            // Neo4j plans it; and so are null and the kinds each takes.
            listOf(
                "MATCH (n) WHERE n.flag AND NOT n.other RETURN n" to emptyMap(),
                "RETURN \$p AND true" to mapOf("p" to 1),
                "MATCH (n) RETURN n.x.y, n.x[0], 1 IN n.x, range(0, n.x), properties(n.x)" to emptyMap(),
                "UNWIND [{a: 1}] AS m RETURN m.a, m['a']" to emptyMap(),
                "RETURN NOT null, null XOR true, null.num, null[0], [1, 2, 3][null], 1 IN null, properties(null)" to emptyMap(),
                "WITH {a: 1} AS m, 'a' AS k RETURN m.a, m[k], properties(m), [1, 2][-1], range(0, 10, -2)" to emptyMap(),
                "MATCH (a) SET a.tags = ['x', 'y'], a.n = -1" to emptyMap(),
                // A variable each query of a UNION makes of another kind is of none known.
                "CALL { RETURN 1 AS x UNION RETURN {a: 1} AS x } RETURN x.a" to emptyMap(),
            ).forEach { (cypher, parameters) -> compiler.compile(cypher, parameters) }
        }
    }

    @Test
    fun `two lists compared in a statement that writes are refused on Oracle, whose block calls no function the query declares`() {
        // #349: Oracle compares two lists by a function the query declares, which a procedure's queries do not call.
        val cypher = "CREATE (n:N {a: [1] < [2]})"
        val refused = assertThrows<DrivineException> { OracleCypherCompiler("g").compile(cypher) }.message!!
        assertTrue(refused.startsWith("Two lists compared with <, <=, > or >= do not run on Oracle in a statement that writes"), refused)
        PostgresCypherCompiler("g").compile(cypher)
        SqliteCypherCompiler("g").compile(cypher)
        // Nor does a reduce()'s step, which is a function the query declares.
        val folded = "RETURN reduce(acc = false, x IN [[1], [2]] | acc OR [1] < x)"
        val inStep = assertThrows<DrivineException> { OracleCypherCompiler("g").compile(folded) }.message!!
        assertTrue(inStep.startsWith("Two lists compared with <, <=, > or >= do not run on Oracle in the step of a reduce()"), inStep)
        PostgresCypherCompiler("g").compile(folded)
        SqliteCypherCompiler("g").compile(folded)
    }

    @Test
    fun `a date written as a text that is no date is refused, with what the JVM's parser says of it`() {
        // Not in the spec: the message ends with the JVM's own text.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("RETURN date('2024-02-30')") }.message!!
            assertTrue(refused.startsWith("'2024-02-30' is no date"), refused)
        }
    }
}
