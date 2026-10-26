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
            // A sum that may be a text writes each side as toString does, a float as Java writes it: on Oracle twice, the
            // second time for a text past 4,000 bytes (#298).
            assertTrue(one < 30_000, "${compiler::class.simpleName}: $one characters")
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
            // A property may hold a float: it is written as toString writes it, beside a text or not. Oracle's sum writes such a
            // side twice, the second time in the branch it takes for a text past 4,000 bytes (#298).
            val side = if (compiler is OracleCypherCompiler) 2 * once else once
            assertEquals(side, writes("MATCH (c:Claim) RETURN c.s + 'b'"), name)
            assertEquals(side, writes("MATCH (c:Claim) RETURN toUpper(c.s) + c.f"), name)
            assertEquals(once + side, writes("MATCH (c:Claim) RETURN 'x' + toString(c.i) + c.f"), name)
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
            assertEquals(writes(joined) + side, writes("$joined + 'x'"), name)
            // Each query of a CALL's UNION makes it a text, and a write carries it on.
            assertEquals(0, writes("MATCH (c:Claim) CALL { WITH c RETURN toUpper(c.s) AS v UNION WITH c RETURN 'n' AS v } RETURN v + 'x'"), name)
            assertEquals(0, writes("MATCH (c:Claim) WITH c, toUpper(c.s) AS t SET c.t = t RETURN t + 'x'"), name)
            // Where any one of them may be a float, it is written as toString writes it.
            assertEquals(side, writes("MATCH (c:Claim) RETURN CASE WHEN c.b THEN 'a' ELSE c.f END + 'x'"), name)
            assertEquals(side, writes("MATCH (c:Claim) RETURN coalesce(toUpper(c.s), c.f) + 'x'"), name)
            assertEquals(side, writes("MATCH (c:Claim) RETURN ['a', c.f][1] + 'x'"), name)
            assertEquals(side, writes("MATCH (c:Claim) CALL { WITH c RETURN toUpper(c.s) AS v UNION WITH c RETURN c.f AS v } RETURN v + 'x'"), name)
            // The least and the greatest of texts are a text; of a property, which may hold a float, not.
            assertEquals(0, writes("MATCH (c:Claim) WITH min(toUpper(c.s)) AS lo, max(toLower(c.s)) AS hi RETURN lo + hi + 'x'"), name)
            assertEquals(side, writes("MATCH (c:Claim) WITH max(c.f) AS hi RETURN hi + 'x'"), name)
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
            // An item unwound from a list that holds no null holds none.
            assertTrue(!tests("UNWIND [[1, 2], [3]] AS l RETURN l = [1, 2]"), "${compiler::class.simpleName}")
            assertTrue(tests("UNWIND [[1, null], [3]] AS l RETURN l = [1, 2]"), "${compiler::class.simpleName}")
            // Lists and maps written out are compared item by item.
            assertTrue(!tests("MATCH (c:Claim) WITH c.a AS a, c.b AS b RETURN [a, b] = [b, a], {x: a} = {x: b}, a IN [b, null]"), "${compiler::class.simpleName}")
            // A parameter's list that holds a null is compared item by item as the statement is compiled; a map is walked.
            assertTrue(!tests("MATCH (c:Claim) WHERE c.list = \$p RETURN c.id", mapOf("p" to listOf(1, null, listOf(null, 2)))), "${compiler::class.simpleName}")
            assertTrue(tests("MATCH (c:Claim) WHERE c.map = \$p RETURN c.id", mapOf("p" to mapOf("x" to null))), "${compiler::class.simpleName}")
            assertTrue(tests("MATCH (c:Claim) WITH c, [c.a] AS l RETURN l = [c.b]"), "${compiler::class.simpleName}")
            // As is a parameter's list with an item too long to write in the statement (#303).
            assertTrue(tests("MATCH (c:Claim) WHERE c.list = \$p RETURN c.id", mapOf("p" to listOf("x".repeat(2000), null))), "${compiler::class.simpleName}")
            // Up to LITERAL_BYTES of JSON's text, its quotes and each character's UTF-8 bytes counted, an item is written.
            listOf("x".repeat(998) to false, "x".repeat(999) to true, "é".repeat(499) to false, "é".repeat(500) to true).forEach { (text, walked) ->
                assertEquals(walked, tests("MATCH (c:Claim) WHERE c.list = \$p RETURN c.id", mapOf("p" to listOf(listOf(text), null))), "${compiler::class.simpleName}: ${text.length}")
            }
        }
    }

    @Test
    fun `a WHERE that searches a list that holds no null compiles to the search alone`() {
        // #225: null and false keep no row alike, so a WHERE needs no test of the value being null.
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (c:Claim) WHERE c.a IN \$l AND (c.b IN [1, 2] OR c.c > 1) RETURN c.id", mapOf("l" to listOf(1, 2))).sql
            assertTrue(!sql.contains("ELSE FALSE") && !sql.contains("THEN FALSE"), "${compiler::class.simpleName}: $sql")
            // Nor of the list being null or holding a null, beside a value that holds none.
            val nullable = compiler.compile("MATCH (c:Claim) WHERE 'a' IN c.tags AND c.a IN \$l RETURN c.id", mapOf("l" to listOf(1, null))).sql
            assertTrue(!nullable.contains("ELSE FALSE") && !nullable.contains("THEN FALSE"), "${compiler::class.simpleName}: $nullable")
            // Nor in a quantified path pattern's WHERE, which keeps a step where it is true.
            val stepped = compiler.compile("MATCH (a:Claim) ((x)-[:R]->(y) WHERE y.a IN [1, 2]){1,3} (b) RETURN b.id").sql
            assertTrue(!stepped.contains("ELSE FALSE") && !stepped.contains("THEN FALSE"), "${compiler::class.simpleName}: $stepped")
            val returned = compiler.compile("MATCH (c:Claim) RETURN c.a IN \$l", mapOf("l" to listOf(1, 2))).sql
            assertTrue(returned.contains("THEN FALSE"), "${compiler::class.simpleName}: $returned")
        }
    }

    @Test
    fun `an IN of an IN compiles to a statement that grows with each, not exponentially`() {
        fun nested(times: Int, list: String) = "MATCH (c:Claim) RETURN " + "(".repeat(times) + "c.a" + (1..times).joinToString("") { " IN $list)" }
        compilers.forEach { compiler ->
            listOf("[true]", "c.list").forEach { list ->
                val (two, four) = listOf(2, 4).map { compiler.compile(nested(it, list)).sql.length }
                assertTrue(four < 3 * two, "${compiler::class.simpleName}: $two, then $four characters")
            }
        }
    }

    @Test
    fun `values that may hold a null are walked on Postgres over arrays unnested, which the planner costs low`() {
        // As the sort key's walk is: a set-returning function's 100 rows, multiplied through the walk, bring in its JIT.
        val sql = PostgresCypherCompiler("g").compile("MATCH (c:Claim) WITH [c.a] AS l, [c.b] AS m RETURN l = m").sql
        assertTrue(sql.contains("WITH RECURSIVE") && !sql.contains("FROM jsonb_array_elements(") && !sql.contains("jsonb_each("), sql)
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
    fun `a sort by a property on Postgres is by its JSON and its text, and only a list the statement makes is keyed as a list`() {
        // Keyed as a list, a term costs thousands a row in the planner's estimate, which brings in its JIT for the whole
        // statement past a few dozen rows. A text sorts by its code points (#273), in the same row, with no subquery.
        val compiler = PostgresCypherCompiler("g")
        val property = compiler.compile("MATCH (c:Claim) RETURN c.name ORDER BY c.name").sql.substringAfter("ORDER BY ")
        assertEquals(
            "ROW(CASE WHEN jsonb_typeof(p2.s1) = 'string' THEN '\"\"'::jsonb ELSE NULLIF(p2.s1, 'null'::jsonb) END, " +
                "(CASE WHEN jsonb_typeof(p2.s1) = 'string' THEN (p2.s1 #>> '{}') END) COLLATE \"C\")",
            property,
        )
        val list = compiler.compile("MATCH (c:Claim) WITH c.name AS n, collect(c.id) AS ids RETURN n ORDER BY ids").sql.substringAfter("ORDER BY ")
        assertTrue(list.contains("WITH RECURSIVE"), list)
        // Its items as arrays unnested, which the planner takes for 10 items, not a set-returning function's 100: a 150th of the cost.
        assertTrue(!list.contains("FROM jsonb_array_elements(") && list.contains("unnest(ARRAY(SELECT jsonb_array_elements("), list)
    }

    @Test
    fun `a round() to places the statement gives, nested under an aggregate, compiles one rounding, not both`() {
        // #301: under an aggregate nothing is bound, so a CASE of both roundings, nested four deep, wrote the number out in
        // both at each level. The places, written or given, pick the rounding when compiling.
        fun nested(precision: String) = "UNWIND [1.5] AS x RETURN " + "round(".repeat(4) + "sum(x)" + "$precision)".repeat(4)
        compilers.forEach { compiler ->
            val name = compiler::class.simpleName
            fun size(precision: String, parameters: Map<String, Any?> = emptyMap()) = compiler.compile(nested(precision), parameters).sql.length
            val whole = size("")
            listOf(", 0", ", -0.5", ", 0.9", ", -\$p").forEach { assertEquals(whole, size(it, mapOf("p" to 0.5)), "$name: round(x$it)") }
            // A parameter, its key, and NaN, which Neo4j casts to 0 places.
            listOf(0L, 0, 0.7, Double.NaN).forEach { assertEquals(whole, size(", \$p", mapOf("p" to it)), "$name: \$p = $it") }
            assertEquals(whole, size(", \$m.p", mapOf("m" to mapOf("p" to 0L))), "$name")
            // To 1 place or more, it rounds by places alone, and grows as the number does, not twice over at each level.
            val read = size(", toInteger('1')")
            listOf(size(", 1"), size(", \$p", mapOf("p" to 1.0))).forEach { places -> assertTrue(places < read, "$name: $places, then $read characters") }
        }
    }

    @Test
    fun `a sign() nested under an aggregate compiles to about the statement a ceil() does, reading its number once`() {
        // Under an aggregate nothing is bound, so a sign that read its number twice wrote it out twice at each level:
        // on Oracle, where a sign is not SIGN alone (#294), four deep was 14 times the statement a ceil() is.
        fun nested(function: String) = "UNWIND [1.5] AS x RETURN " + "$function(".repeat(4) + "sum(x)" + ")".repeat(4)
        compilers.forEach { compiler ->
            val (sign, ceil) = listOf("sign", "ceil").map { compiler.compile(nested(it), emptyMap()).sql.length }
            assertTrue(sign < ceil * 1.1, "${compiler::class.simpleName}: sign $sign, ceil $ceil characters")
        }
    }

    @Test
    fun `a sum reads each value it sums as often as an avg does, and grows under functions as an avg does`() {
        // #304: on Oracle a sum tests whether any value it sums is a float. Nothing is bound under an aggregate, so a test
        // that read each value as number() does, three times more, made a sum over nested functions twice the statement.
        fun over(aggregate: String) = "MATCH (c:Claim) RETURN $aggregate(" + "ceil(".repeat(3) + "c.p" + ")".repeat(3) + ")"
        fun under(aggregate: String, depth: Int) = "UNWIND [1.5] AS x RETURN " + "sign(".repeat(depth) + "$aggregate(x)" + ")".repeat(depth)
        compilers.forEach { compiler ->
            val name = compiler::class.simpleName
            fun size(cypher: String) = compiler.compile(cypher, emptyMap()).sql.length
            val (sum, avg) = listOf("sum", "avg").map { size(over(it)) }
            assertTrue(sum < avg * 1.1, "$name: ${over("sum")}: sum $sum, avg $avg characters")
            val (sumGrowth, avgGrowth) = listOf("sum", "avg").map { size(under(it, 4)).toDouble() / size(under(it, 2)) }
            assertTrue(sumGrowth < avgGrowth * 1.1, "$name: from 2 levels to 4, sum grew $sumGrowth times, avg $avgGrowth")
        }
    }
}
