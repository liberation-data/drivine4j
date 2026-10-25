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
    fun `a sum writes a side as toString writes a float only where the statement gives neither side`() {
        // A number the statement gives is written out at compile time, and a side added to one is a text where the sum is one.
        val written = mapOf("PostgresCypherCompiler" to "to_char(", "OracleCypherCompiler" to "TO_CHAR(")
        compilers.filter { it::class.simpleName in written }.forEach { compiler ->
            fun writes(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
                compiler.compile(cypher, parameters).sql.split(written.getValue(compiler::class.simpleName!!)).size - 1
            val once = writes("MATCH (c:Claim) RETURN c.a + c.b") / 2
            assertTrue(once > 0, "${compiler::class.simpleName}")
            assertEquals(0, writes("MATCH (c:Claim) RETURN c.a + 1, 2.5 + c.b, c.a + \$n", mapOf("n" to 1.5)), "${compiler::class.simpleName}")
            assertEquals(once, writes("MATCH (c:Claim) RETURN 'x' + c.b"), "${compiler::class.simpleName}")
            // NaN and the infinities are bound as texts on Postgres, so both sides of a sum with one are written as toString writes them.
            assertEquals(2 * once, writes("MATCH (c:Claim) RETURN c.a + \$n", mapOf("n" to Double.NaN)), "${compiler::class.simpleName}")
        }
    }

    @Test
    fun `an equality of values that hold no null in a list or a map compiles with no test for one`() {
        // #225: a null a list or a map holds is tested for, and the two walked, only where one may hold one.
        val tested = mapOf("PostgresCypherCompiler" to "jsonb_path_exists(", "OracleCypherCompiler" to "[[,:]null[],}]")
        compilers.filter { it::class.simpleName in tested }.forEach { compiler ->
            val test = tested.getValue(compiler::class.simpleName!!)
            fun tests(cypher: String, parameters: Map<String, Any?> = emptyMap()) = compiler.compile(cypher, parameters).sql.contains(test)
            assertTrue(!tests("MATCH (c:Claim) WHERE c.list = \$p RETURN c.id", mapOf("p" to listOf(1, 2))), "${compiler::class.simpleName}")
            assertTrue(!tests("MATCH (c:Claim) WITH c, c.list AS l RETURN [1, 2] = [1, 2], c.a = c.b, l = c.b, c.list IN \$ls, {x: 1} <> {x: 2}, size(l) = 2", mapOf("ls" to listOf(listOf(1)))), "${compiler::class.simpleName}")
            // Lists and maps written out are compared item by item.
            assertTrue(!tests("MATCH (c:Claim) WITH c.a AS a, c.b AS b RETURN [a, b] = [b, a], {x: a} = {x: b}, a IN [b, null]"), "${compiler::class.simpleName}")
            // A parameter's list that holds a null is compared item by item as the statement is compiled; a map is walked.
            assertTrue(!tests("MATCH (c:Claim) WHERE c.list = \$p RETURN c.id", mapOf("p" to listOf(1, null, listOf(null, 2)))), "${compiler::class.simpleName}")
            assertTrue(tests("MATCH (c:Claim) WHERE c.map = \$p RETURN c.id", mapOf("p" to mapOf("x" to null))), "${compiler::class.simpleName}")
            assertTrue(tests("MATCH (c:Claim) WITH c, [c.a] AS l RETURN l = [c.b]"), "${compiler::class.simpleName}")
        }
    }

    @Test
    fun `a WHERE that searches a list that holds no null compiles to the search alone`() {
        // #225: null and false keep no row alike, so a WHERE needs no test of the value being null.
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (c:Claim) WHERE c.a IN \$l AND (c.b IN [1, 2] OR c.c > 1) RETURN c.id", mapOf("l" to listOf(1, 2))).sql
            assertTrue(!sql.contains("ELSE FALSE") && !sql.contains("THEN FALSE"), "${compiler::class.simpleName}: $sql")
            val returned = compiler.compile("MATCH (c:Claim) RETURN c.a IN \$l", mapOf("l" to listOf(1, 2))).sql
            assertTrue(returned.contains("THEN FALSE"), "${compiler::class.simpleName}: $returned")
        }
    }

    @Test
    fun `lists and maps written out that may hold a null are compared item by item, not built and compared whole`() {
        val built = mapOf(
            "PostgresCypherCompiler" to listOf("json_build_"), "OracleCypherCompiler" to listOf("JSON_ARRAY(", "JSON_OBJECT("), "SqliteCypherCompiler" to listOf("json_array(", "json_object("),
        )
        compilers.forEach { compiler ->
            listOf("[a, b] = [b, a]", "{x: a} = {x: b}").forEach { equality ->
                val sql = compiler.compile("MATCH (c:Claim) WITH c.a AS a, c.b AS b RETURN $equality AS e").sql
                assertTrue(built.getValue(compiler::class.simpleName!!).none { sql.contains(it) }, "${compiler::class.simpleName}: $sql")
            }
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
