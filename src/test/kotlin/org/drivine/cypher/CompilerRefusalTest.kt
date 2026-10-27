package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
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
    fun `a date written as a text that is no date is refused, with what the JVM's parser says of it`() {
        // Not in the spec: the message ends with the JVM's own text.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("RETURN date('2024-02-30')") }.message!!
            assertTrue(refused.startsWith("'2024-02-30' is no date"), refused)
        }
    }
}
