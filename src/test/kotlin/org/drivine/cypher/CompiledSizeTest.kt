package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Operations nested in one another compile to a statement that grows with them, not exponentially: an operand
 * read more than once, to test its kind and then its value, is bound once where it is long. Unbound, the
 * clause that stamps a node compiled to 650 KB on Oracle, which took half a gigabyte of a session's memory.
 */
class CompiledSizeTest {

    private val compilers = listOf(OracleCypherCompiler("g"), PostgresCypherCompiler("g"))

    @Test
    fun `the clause that stamps a node compiles to a statement of a few kilobytes on each store`() {
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (c:Claim {id: \$id}) SET ${Stamps.setClause("c")}", mapOf("id" to "x")).sql
            assertTrue(sql.length < 20_000, "${compiler::class.simpleName}: ${sql.length} characters")
        }
    }

    @Test
    fun `a sum of twice the terms compiles to about twice the statement`() {
        fun sum(terms: Int) = "MATCH (c:Claim) RETURN " + (1..terms).joinToString(" + ") { "c.p$it" }
        compilers.forEach { compiler ->
            val (short, long) = listOf(8, 16).map { compiler.compile(sum(it)).sql.length }
            assertTrue(long < 3 * short, "${compiler::class.simpleName}: $short, then $long characters")
        }
    }
}
