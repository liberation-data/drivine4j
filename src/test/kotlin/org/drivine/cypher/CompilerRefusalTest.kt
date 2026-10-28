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
        // Not in the spec yet (#349; https://github.com/liberation-data/drivine-cypher-spec/pull/28): Postgres and SQLite gave the list's JSON text, each spaced its own way, and Oracle null.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "WITH [1, 2] AS l RETURN toString(l)" to "list",
                "WITH [localdatetime('2024-03-15T10:00:00')] AS l RETURN toString(l)" to "list",
                "MATCH (n) RETURN toString(collect(n.x))" to "list",
                "RETURN toString({a: 1})" to "map",
                "MATCH (n) RETURN toString(n {.name})" to "map",
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
