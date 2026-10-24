package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
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

    @Test
    fun `collect(DISTINCT) beside a variable no other item is, outside its aggregates, is refused as collect() is`() {
        // Not in the spec yet: aggregatesFirst groups by each such variable, so it is refused before.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("MATCH (p:Person) RETURN p.born, collect(DISTINCT p.name) + p.name") }.message!!
            assertTrue(refused.contains("names p outside its aggregates"), refused)
        }
    }

    @Test
    fun `a date written as a text that is no date is refused, with what the JVM's parser says of it`() {
        // Not in the spec: the message ends with the JVM's own text.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("RETURN date('2024-02-30')") }.message!!
            assertTrue(refused.startsWith("'2024-02-30' is no date"), refused)
        }
    }
}
