package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operations nested in one another compile to a statement that grows with them, not exponentially: an operand
 * read more than once, to test its kind and then its value, is bound once where it is long. Unbound, the
 * clause that stamps a node compiled to 650 KB on Oracle, which took half a gigabyte of a session's memory.
 */
class CompiledSizeTest {

    private val compilers = listOf(OracleCypherCompiler("g"), PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))

    @Test
    fun `the clause that stamps a node compiles to a statement of a few kilobytes on each store`() {
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (c:Claim {id: \$id}) SET ${Stamps.setClause("c")}", mapOf("id" to "x")).sql
            assertTrue(sql.length < 20_000, "${compiler::class.simpleName}: ${sql.length} characters")
        }
    }

    @Test
    fun `a duration added to a value read from the store compiles to a statement that grows with each addition, not exponentially`() {
        fun added(times: Int) = "MATCH (c:Claim) RETURN " + "(".repeat(times) + "c.day" + (1..times).joinToString("") { " + duration('P1D'))" }
        compilers.forEach { compiler ->
            val (one, two, four) = listOf(1, 2, 4).map { compiler.compile(added(it)).sql.length }
            // A sum that may be a text writes each side as toString does, a float as Java writes it.
            assertTrue(one < 25_000, "${compiler::class.simpleName}: $one characters")
            assertTrue(four < 3 * two, "${compiler::class.simpleName}: $two, then $four characters")
        }
    }

    @Test
    fun `a WITH sorted by a duration added to a value read from the store, which is none of its items, compiles to a statement that grows with each addition, not exponentially`() {
        fun added(times: Int) = "MATCH (c:Claim) WITH c ORDER BY " + "(".repeat(times) + "c.day" + (1..times).joinToString("") { " + duration('P1D'))" } + " RETURN c.id"
        compilers.forEach { compiler ->
            val (one, two, four) = listOf(1, 2, 4).map { compiler.compile(added(it)).sql.length }
            assertTrue(one < 40_000, "${compiler::class.simpleName}: $one characters")
            assertTrue(four < 3 * two, "${compiler::class.simpleName}: $two, then $four characters")
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

    @Test
    fun `a quantified path pattern of twice the hops, each node read by its WHERE, compiles to about twice the statement`() {
        fun group(hops: Int) = "MATCH (a:Claim) ((n0)" + (1..hops).joinToString("") { "-[:R]->(n$it)" } +
            " WHERE " + (1..hops).joinToString(" AND ") { "n${it - 1}.v < n$it.v" } + "){1,3} (b) RETURN b"
        compilers.forEach { compiler ->
            val (short, long) = listOf(4, 8).map { compiler.compile(group(it)).sql.length }
            assertTrue(short < 20_000, "${compiler::class.simpleName}: $short characters")
            assertTrue(long < 3 * short, "${compiler::class.simpleName}: $short, then $long characters")
        }
    }

    @Test
    fun `items taken out of lists nested twice as deep, holding a relationship, compile to about twice the statement`() {
        fun taken(depth: Int) = "MATCH (c:Claim)-[r:R]->() WITH ${"[".repeat(depth)}r, 1${"]".repeat(depth)} AS m RETURN m${"[0]".repeat(depth)} = 1"
        compilers.forEach { compiler ->
            val (short, long) = listOf(4, 8).map { compiler.compile(taken(it)).sql.length }
            assertTrue(short < 20_000, "${compiler::class.simpleName}: $short characters")
            assertTrue(long < 3 * short, "${compiler::class.simpleName}: $short, then $long characters")
        }
    }

    @Test
    fun `a sort by a property on Postgres is by its JSON alone, and only a list the statement makes is keyed as a list`() {
        // Keyed as a list, a term costs thousands a row in the planner's estimate, which brings in its JIT for the whole
        // statement past a few dozen rows.
        val compiler = PostgresCypherCompiler("g")
        val property = compiler.compile("MATCH (c:Claim) RETURN c.name ORDER BY c.name").sql.substringAfter("ORDER BY ")
        assertEquals("NULLIF(p2.s1, 'null'::jsonb)", property)
        val list = compiler.compile("MATCH (c:Claim) WITH c.name AS n, collect(c.id) AS ids RETURN n ORDER BY ids").sql.substringAfter("ORDER BY ")
        assertTrue(list.contains("WITH RECURSIVE"), list)
        // Its items as arrays unnested, which the planner takes for 10 items, not a set-returning function's 100: a 150th of the cost.
        assertTrue(!list.contains("FROM jsonb_array_elements(") && list.contains("unnest(ARRAY(SELECT jsonb_array_elements("), list)
    }
}
