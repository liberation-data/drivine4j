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
    fun `a statement that may work NaN or an infinity out is compiled as one given one, and any other as it was`() {
        // A float divided by zero or a text toFloat takes as NaN (#408, #545): decided as the statement is compiled.
        fun postgres(cypher: String, parameters: Map<String, Any?> = emptyMap()) = PostgresCypherCompiler("g").compile(cypher, parameters).nonFinite
        fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()) = SqliteCypherCompiler("g").compile(cypher, parameters).nonFinite
        listOf(
            "RETURN 0.0 / 0.0", "MATCH (n:A) RETURN n.x / n.y", "RETURN 5.0 % 0", "MATCH (n:A) WHERE n.x % n.y = 1 RETURN n",
            "UNWIND [1, 0] AS z RETURN [x IN [1.0] | x / z]", "MATCH (n:A) RETURN toFloat(n.s)", "RETURN toFloat(' NaN ')",
            "RETURN toFloat('-Infinity')", "CALL { MATCH (n:A) RETURN n.x / n.y AS q } RETURN q", "RETURN 1 AS x UNION RETURN 1.0 / 0 AS x",
        ).forEach { cypher ->
            assertTrue(postgres(cypher), cypher)
            assertTrue(sqlite(cypher), cypher)
        }
        assertTrue(postgres("RETURN 1.0 / \$z", mapOf("z" to 0)))
        assertTrue(postgres("RETURN toFloat(\$s)", mapOf("s" to "Infinity")))
        listOf(
            "RETURN 1.0 / 2", "RETURN 1.0 / -2.5", "MATCH (n:A) RETURN n.x / 3 + n.x % 2", "RETURN toFloat('1.5')", "RETURN toFloat(2)",
            "RETURN toFloat(null)", "MATCH (n:A) WHERE n.x = 1 RETURN n.x * 2",
        ).forEach { cypher ->
            assertFalse(postgres(cypher), cypher)
            assertFalse(sqlite(cypher), cypher)
        }
        assertFalse(postgres("RETURN [1.0 / \$z, toFloat(\$s)]", mapOf("z" to 4, "s" to "1e3")))
        // A statement that writes on Postgres stores no such number, and fails as `numeric` does; SQLite stores an infinity.
        assertFalse(postgres("MATCH (n:A) SET n.q = n.x / n.y"))
        assertTrue(sqlite("MATCH (n:A) SET n.q = n.x / n.y"))
        // The SQL of a statement that works none out is as it was.
        val plain = PostgresCypherCompiler("g").compile("MATCH (n:A) WHERE n.x = \$x RETURN [n.x / 2, toFloat('3') > 1]", mapOf("x" to 1))
        assertFalse(plain.sql.contains("2e309"), plain.sql)
        // Oracle's floats hold them, so it compiles nothing more.
        assertFalse(OracleCypherCompiler("g").compile("RETURN 0.0 / 0.0").nonFinite)
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
    fun `x IN a list given with NaN in it finds NaN equal to nothing on Oracle, and a list without compares plainly`() {
        // Oracle's JSON finds NaN equal to itself; a given list of numbers is compared plainly, as #458 compares it, only without NaN.
        val cypher = "MATCH (n:A) WHERE n.x IN \$l RETURN n.x"
        val nan = OracleCypherCompiler("g").compile(cypher, mapOf("l" to listOf(1.0, Double.NaN))).sql
        assertTrue(nan.contains("IS NAN"), nan)
        val finite = OracleCypherCompiler("g").compile(cypher, mapOf("l" to listOf(1.0, 2.0))).sql
        assertFalse(finite.contains("IS NAN"), finite)
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
