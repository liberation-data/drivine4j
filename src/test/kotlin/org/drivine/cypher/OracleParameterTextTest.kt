package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a parameter's float that is a whole number is bound as extended JSON's double, as `JSON(… EXTENDED)` reads
 * one, and nothing else is: a `BigDecimal` is a decimal, bound as the number it is, every digit kept.
 */
class OracleParameterTextTest {

    @Test
    fun `a whole BigDecimal parameter is bound as the number it is, and a whole float or Float as extended JSON's double`() {
        val compiled = OracleCypherCompiler("g").compile("RETURN [\$d, \$f, \$g]", mapOf("d" to BigDecimal("12345678901234567890"), "f" to 2.0, "g" to 3.0f))
        assertEquals(0, BigDecimal(compiled.parameters["_cy_d"] as String).compareTo(BigDecimal("12345678901234567890")), "${compiled.parameters["_cy_d"]}")
        assertEquals("{\"\$numberDouble\":\"2.0\"}", compiled.parameters["_cy_f"])
        assertEquals("{\"\$numberDouble\":\"3.0\"}", compiled.parameters["_cy_g"])
        assertTrue(!compiled.sql.contains("_cy_d EXTENDED") && compiled.sql.contains("_cy_f EXTENDED"), compiled.sql)
    }

    @Test
    fun `a write binds its parameters as text, and reads as extended JSON only those that hold a whole float`() {
        val compiled = OracleCypherCompiler("g").compile("MATCH (n) SET n.a = \$a, n.f = \$f, n.p = \$p.x", mapOf("a" to "x", "f" to 2.0, "p" to mapOf("x" to listOf(1.0))))
        val sql = compiled.sql
        assertTrue(sql.contains("cy_params CLOB :=") && !sql.contains("cy_params JSON"), sql)
        assertTrue(sql.contains("JSON_QUERY(cy_params, '\$.a' RETURNING JSON)") && !sql.contains("JSON_QUERY(cy_params, '\$.a' RETURNING CLOB)"), sql)
        listOf("f", "p.x").forEach { assertTrue(sql.contains("JSON(JSON_QUERY(cy_params, '\$.$it' RETURNING CLOB) EXTENDED)"), sql) }
        assertEquals("{\"a\":\"x\",\"f\":{\"\$numberDouble\":\"2.0\"},\"p\":{\"x\":[{\"\$numberDouble\":\"1.0\"}]}}", compiled.parameters["_cy_params"])
    }

    @Test
    fun `a value in a map parameter is read with one path from the parameter's text, as extended JSON only where it holds a whole float`() {
        // Oracle crashed the session where a value was read from a parameter past 32 KB taken as JSON first (#256).
        val p = mapOf("p" to mapOf("text" to "x", "n" to 2.0, "m" to mapOf("b" to 1)))
        val write = OracleCypherCompiler("g").compile("MATCH (n) SET n.t = \$p.text, n.n = \$p.n, n.b = \$p.m.b", p).sql
        assertTrue(write.contains("JSON_QUERY(cy_params, '\$.p.text' RETURNING JSON)") && write.contains("JSON_QUERY(cy_params, '\$.p.m.b' RETURNING JSON)"), write)
        assertTrue(write.contains("JSON(JSON_QUERY(cy_params, '\$.p.n' RETURNING CLOB) EXTENDED)") && !write.contains("'\$.p' RETURNING"), write)
        val read = OracleCypherCompiler("g").compile("RETURN [\$p.text, \$p.n, \$p.m.b, \$q.text]", p + mapOf("q" to mapOf("text" to "y"))).sql
        assertTrue(read.contains("JSON_QUERY(\$_cy_p, '\$.text' RETURNING JSON)") && read.contains("JSON(JSON_QUERY(\$_cy_p, '\$.n' RETURNING CLOB) EXTENDED)"), read)
        assertTrue(read.contains("JSON_QUERY(\$_cy_p, '\$.m.b' RETURNING JSON)") && read.contains("JSON_QUERY(\$_cy_q, '\$.text' RETURNING JSON)") && !read.contains("JSON(\$_cy_"), read)
        val union = OracleCypherCompiler("g").compile("RETURN \$p.text AS t UNION RETURN \$p.n AS t", p).sql
        assertTrue(union.contains("JSON_QUERY(\$_cy_p, '\$.text' RETURNING JSON)") && union.contains("JSON(JSON_QUERY(\$_cy_p, '\$.n' RETURNING CLOB) EXTENDED)"), union)
    }

    @Test
    fun `a value in a map parameter taken by a subscript of known text is read with one path from the parameter's text`() {
        // As by a property (#256): a text key, written or bound, is the property of that name.
        val p = mapOf("p" to mapOf("text" to "x", "m" to mapOf("b" to 1)), "k" to "text")
        val read = OracleCypherCompiler("g").compile("RETURN [\$p['text'], \$p['m'].b, \$p.m['b'], \$p[\$k]]", p).sql
        assertTrue(read.contains("JSON_QUERY(\$_cy_p, '\$.text' RETURNING JSON)") && read.contains("JSON_QUERY(\$_cy_p, '\$.m.b' RETURNING JSON)"), read)
        assertTrue(!read.contains("JSON(\$_cy_p)") && !read.contains("'\$.m' RETURNING"), read)
        // A subscript takes any text, so a key with a dot in it is one key, quoted, not a path of two.
        val dotted = OracleCypherCompiler("g").compile("RETURN \$p['a.b']", mapOf("p" to mapOf("a.b" to "x"))).sql
        assertTrue(dotted.contains("JSON_QUERY(\$_cy_p, '\$.\"a.b\"' RETURNING JSON)"), dotted)
        val write = OracleCypherCompiler("g").compile("MATCH (n) SET n.t = \$p['text'], n.b = \$p['m']['b'], n.k = \$p[\$k]", p).sql
        assertTrue(write.contains("JSON_QUERY(cy_params, '\$.p.text' RETURNING JSON)") && write.contains("JSON_QUERY(cy_params, '\$.p.m.b' RETURNING JSON)"), write)
        assertTrue(!write.contains("'\$.p' RETURNING") && !write.contains("CASE"), write)
        // A reduce() step reads its parameters from the map its function is given, so a subscript there is read as it was.
        val reduced = OracleCypherCompiler("g").compile("RETURN reduce(s = '', x IN [1, 2] | s + \$p['text'])", p).sql
        assertTrue(reduced.contains("JSON_QUERY(JSON_QUERY(cy_context, '\$.\"\$p\"' RETURNING JSON), '\$.text' RETURNING JSON)") && !reduced.contains("JSON_QUERY(\$_cy_p, '\$.text'"), reduced)
    }

    @Test
    fun `an item of a list parameter taken by a number is read from the parameter as JSON, as it was`() {
        val read = OracleCypherCompiler("g").compile("RETURN [\$p.l[0], \$p['l'][-1]]", mapOf("p" to mapOf("l" to listOf("x")))).sql
        assertTrue(read.contains("JSON_QUERY(\$_cy_p, '\$.l' RETURNING JSON)") && !read.contains("'\$.l[0]'"), read)
    }

    @Test
    fun `a value in a parameter that is no map, or that a reduce() step is given, is read from the parameter as JSON, as it was`() {
        // A list or a date on the way to the key is no map to take one path into, and a reduce() step reads its
        // parameters from the map its function is given, not from the text they are bound as.
        val given = mapOf("p" to mapOf("text" to "x", "l" to listOf(1)), "d" to LocalDate.of(2020, 1, 2))
        val read = OracleCypherCompiler("g").compile("RETURN [\$p.l.x, \$d.year]", given).sql
        assertTrue(read.contains("JSON_QUERY(JSON_QUERY(\$_cy_p, '\$.l' RETURNING JSON), '\$.x' RETURNING JSON)") && read.contains("EXTRACT(YEAR FROM"), read)
        val reduced = OracleCypherCompiler("g").compile("RETURN reduce(s = '', x IN [1, 2] | s + \$p.text)", given).sql
        assertTrue(reduced.contains("JSON_QUERY(JSON_QUERY(cy_context, '\$.\"\$p\"' RETURNING JSON), '\$.text' RETURNING JSON)") && !reduced.contains("JSON_QUERY(\$_cy_p, '\$.text'"), reduced)
    }
}
