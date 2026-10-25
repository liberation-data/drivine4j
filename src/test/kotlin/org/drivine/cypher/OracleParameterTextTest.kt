package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
        listOf("f", "p").forEach { assertTrue(sql.contains("JSON(JSON_QUERY(cy_params, '\$.$it' RETURNING CLOB) EXTENDED)"), sql) }
        assertEquals("{\"a\":\"x\",\"f\":{\"\$numberDouble\":\"2.0\"},\"p\":{\"x\":[{\"\$numberDouble\":\"1.0\"}]}}", compiled.parameters["_cy_params"])
    }
}
