package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NaN and the infinities given to a compiled statement (#295): what each store binds and compiles, without a store. What they
 * give on each store is the spec's, in `cypher/queries.yaml` and `cypher/writes.yaml`.
 */
class NonFiniteCompileTest {

    private val statement = "RETURN [\$p = \$p, \$p > 1.0, \$p + 1, toString(\$p)]"

    @Test
    fun `a statement given no NaN or infinity compiles as it would without them`() {
        // Postgres and SQLite work NaN out only where the statement is given one, so no other statement grows.
        val finite = PostgresCypherCompiler("g").compile(statement, mapOf("p" to 1.5))
        assertFalse(finite.nonFinite)
        assertFalse(finite.sql.contains("2e309"), finite.sql)
        val sqlite = SqliteCypherCompiler("g").compile(statement, mapOf("p" to 1.5))
        assertFalse(sqlite.sql.contains("numberDouble"), sqlite.sql)
    }

    @Test
    fun `NaN and the infinities are bound as each store holds them, alone and in a list or a map`() {
        val given = mapOf("p" to Double.NaN, "l" to listOf(1.0, Double.POSITIVE_INFINITY), "m" to mapOf("x" to Double.NEGATIVE_INFINITY))
        val cypher = "RETURN [\$p, \$l, \$m]"
        val postgres = PostgresCypherCompiler("g").compile(cypher, given)
        assertTrue(postgres.nonFinite)
        val nan = "2" + "0".repeat(309) + ".5"
        val infinity = "1" + "0".repeat(309) + ".5"
        assertEquals(listOf(nan, "[1.0,$infinity]", "{\"x\":-$infinity}"), postgres.parameters.values.toList())
        val sqlite = SqliteCypherCompiler("g").compile(cypher, given)
        assertEquals(listOf("{\"\$numberDouble\":\"NaN\"}", "[1.0,9e999]", "{\"x\":-9e999}"), sqlite.parameters.values.toList())
        val oracle = OracleCypherCompiler("g").compile(cypher, given)
        assertEquals(
            listOf("{\"\$numberDouble\":\"NaN\"}", "[{\"\$numberDouble\":\"1.0\"},{\"\$numberDouble\":\"Infinity\"}]", "{\"x\":{\"\$numberDouble\":\"-Infinity\"}}"),
            oracle.parameters.values.toList(),
        )
        assertTrue(oracle.sql.contains("EXTENDED"), oracle.sql)
    }

    @Test
    fun `a statement is given NaN or an infinity in a float, an array, a union and a write alike`() {
        // Each says so, as its rows are read by it.
        val postgres = PostgresCypherCompiler("g")
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to Float.NaN)).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to floatArrayOf(1f, Float.POSITIVE_INFINITY))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to doubleArrayOf(Double.NEGATIVE_INFINITY))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to arrayOf<Any?>(mapOf("x" to Double.NaN)))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p AS x UNION RETURN 1 AS x", mapOf("p" to Double.NaN)).nonFinite)
        assertFalse(postgres.compile("RETURN \$p AS x UNION RETURN 1 AS x", mapOf("p" to 1.5)).nonFinite)
        assertTrue(OracleCypherCompiler("g").compile("CREATE (n:A {x: \$p}) RETURN n.x", mapOf("p" to Double.NaN)).nonFinite)
        assertTrue(SqliteCypherCompiler("g").compile("CREATE (n:A {x: \$p}) RETURN n.x", mapOf("p" to Double.POSITIVE_INFINITY)).nonFinite)
    }

    @Test
    fun `the numbers Postgres writes for NaN and the infinities are read back as them`() {
        assertTrue(PostgresCypherCompiler.read(BigDecimal("2e309").add(BigDecimal("0.5"))).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, PostgresCypherCompiler.read(BigDecimal("1e309").add(BigDecimal("0.5"))))
        assertEquals(Double.NEGATIVE_INFINITY, PostgresCypherCompiler.read(BigDecimal("-1e309").subtract(BigDecimal("0.5"))))
        // SQLite's number for an infinity, read in the same rows.
        assertEquals(Double.POSITIVE_INFINITY, PostgresCypherCompiler.read(BigDecimal("9.0e+999")))
        assertEquals(1.5, PostgresCypherCompiler.read(BigDecimal("1.5")))
    }

    @Test
    fun `a statement that writes NaN is refused where the store's JSON holds none`() {
        val nan = mapOf("p" to Double.NaN)
        val infinity = mapOf("p" to Double.POSITIVE_INFINITY)
        assertThrows<DrivineException> { PostgresCypherCompiler("g").compile("CREATE (:A {x: \$p})", nan) }
        assertThrows<DrivineException> { PostgresCypherCompiler("g").compile("CREATE (:A {x: \$p})", infinity) }
        assertThrows<DrivineException> { SqliteCypherCompiler("g").compile("CREATE (:A {x: [\$p]})", nan) }
        // SQLite holds an infinity, and Oracle both.
        SqliteCypherCompiler("g").compile("CREATE (:A {x: \$p})", infinity)
        OracleCypherCompiler("g").compile("CREATE (:A {x: \$p})", nan)
    }
}
