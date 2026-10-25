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
    fun `a sum writes a side the statement makes a text as its own text, and only a side that may be a float as toString writes it`() {
        // Written as toString writes a float, a side that is always a text made `count('x' + toString(i))` 2.5 times as slow on Oracle (#268).
        val written = mapOf("PostgresCypherCompiler" to "to_char(", "OracleCypherCompiler" to "TO_CHAR(")
        compilers.filter { it::class.simpleName in written }.forEach { compiler ->
            val name = compiler::class.simpleName!!
            fun writes(cypher: String) = compiler.compile(cypher).sql.split(written.getValue(name)).size - 1
            // toString writes its float once, and the sum writes the text it gives as it is.
            val once = writes("MATCH (c:Claim) RETURN toString(c.i)")
            assertTrue(once > 0, name)
            assertEquals(once, writes("MATCH (c:Claim) RETURN 'x' + toString(c.i)"), name)
            assertEquals(0, writes("MATCH (c:Claim) RETURN 'a' + toUpper(c.s), trim(c.s) + substring(c.s, 1) + left(c.s, 2) + replace(c.s, 'a', 'b')"), name)
            assertEquals(0, writes("MATCH (c:Claim)-[r]->() RETURN split(c.s, ',')[0] + reverse(toLower(c.s)) + type(r)"), name)
            // Carried through a WITH, a collect and an UNWIND, still a text.
            assertEquals(0, writes("MATCH (c:Claim) WITH toLower(c.s) AS t, collect(toUpper(c.s)) AS ts UNWIND ts AS u RETURN t + u + ts[0] + head(ts)"), name)
            // A property may hold a float: it is written as toString writes it, beside a text or not.
            assertEquals(once, writes("MATCH (c:Claim) RETURN c.s + 'b'"), name)
            assertEquals(once, writes("MATCH (c:Claim) RETURN toUpper(c.s) + c.f"), name)
            assertEquals(2 * once, writes("MATCH (c:Claim) RETURN 'x' + toString(c.i) + c.f"), name)
            // A CASE, a coalesce and a list written are a text, or hold texts, where each of theirs is; a text unwound is one.
            assertEquals(0, writes("MATCH (c:Claim) RETURN CASE WHEN c.b THEN toUpper(c.s) ELSE 'n' END + coalesce(toLower(c.s), 'z') + ['a', toUpper(c.s)][1]"), name)
            assertEquals(0, writes("MATCH (c:Claim) RETURN ltrim(c.s) + rtrim(c.s) + right(c.s, 2)"), name)
            assertEquals(0, writes("MATCH (c:Claim) UNWIND toUpper(c.s) AS u RETURN u + 'x'"), name)
            // A list of texts kept distinct, sliced, its tail, its last, and an item an expression names, still holds texts.
            assertEquals(0, writes("MATCH (c:Claim) WITH collect(DISTINCT toUpper(c.s)) AS ts RETURN last(ts) + ts[1..][0] + tail(ts)[0] + ts[size(ts) - 1]"), name)
            assertEquals(0, writes("MATCH (c:Claim) WITH collect(toUpper(c.s)) AS ts RETURN reverse(ts)[0] + coalesce(ts, split('a,b', ','))[0]"), name)
            // Joined to texts, too: the join writes its sides as any `+` does, and its item, added to a text, is written as it is.
            val joined = "MATCH (c:Claim) WITH collect(toUpper(c.s)) AS ts, collect(c.f) AS fs RETURN (ts + ['a'])[0], ('b' + ts)[0], (ts + fs)[0]"
            assertEquals(writes(joined), writes(joined.replace("[0],", "[0] + 'x',")), name)
            assertEquals(writes(joined) + once, writes("$joined + 'x'"), name)
            // Each query of a CALL's UNION makes it a text, and a write carries it on.
            assertEquals(0, writes("MATCH (c:Claim) CALL { WITH c RETURN toUpper(c.s) AS v UNION WITH c RETURN 'n' AS v } RETURN v + 'x'"), name)
            assertEquals(0, writes("MATCH (c:Claim) WITH c, toUpper(c.s) AS t SET c.t = t RETURN t + 'x'"), name)
            // Where any one of them may be a float, it is written as toString writes it.
            assertEquals(once, writes("MATCH (c:Claim) RETURN CASE WHEN c.b THEN 'a' ELSE c.f END + 'x'"), name)
            assertEquals(once, writes("MATCH (c:Claim) RETURN coalesce(toUpper(c.s), c.f) + 'x'"), name)
            assertEquals(once, writes("MATCH (c:Claim) RETURN ['a', c.f][1] + 'x'"), name)
            assertEquals(once, writes("MATCH (c:Claim) CALL { WITH c RETURN toUpper(c.s) AS v UNION WITH c RETURN c.f AS v } RETURN v + 'x'"), name)
            // The least and the greatest of texts are a text; of a property, which may hold a float, not.
            assertEquals(0, writes("MATCH (c:Claim) WITH min(toUpper(c.s)) AS lo, max(toLower(c.s)) AS hi RETURN lo + hi + 'x'"), name)
            assertEquals(once, writes("MATCH (c:Claim) WITH max(c.f) AS hi RETURN hi + 'x'"), name)
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
