package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a query that reads the graph's tables twice or more turns the JSON table transformation off, which lost a
 * node's label test beside another node's table (#415); the hint is the statement's, so its outermost query holds it. A
 * query of one table is written as it was.
 */
class OracleJsonTableTransformTest {

    private val hint = "NO_JSON_TABLE_TRANSFORM"

    private fun compiled(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql

    @Test
    fun `a query of two tables opens with the hint, once`() {
        val sql = compiled("MATCH (a:N1), (b:N2) RETURN a.n + b.n, a.n - b.n")
        assertTrue(sql.startsWith("SELECT /*+ $hint */ "), sql)
        assertEquals(1, Regex(hint).findAll(sql).count(), sql)
        assertTrue(compiled("MATCH (a:N1)-->(b:N2) RETURN b.n + 1").startsWith("SELECT /*+ $hint */ "))
    }

    @Test
    fun `a query of one table has no hint`() {
        for (cypher in listOf("MATCH (a:N1) RETURN a.n + 1, a.n - 1", "UNWIND [1, 2] AS x MATCH (a:N1) RETURN a.n - a.m, x", "RETURN 1")) {
            val sql = compiled(cypher)
            assertTrue(hint !in sql, "$cypher: $sql")
        }
    }

    @Test
    fun `a UNION's outermost query holds the hint, and a query's declared function stays before it`() {
        val union = compiled("MATCH (a:N1), (b:N2) RETURN a.n + b.n AS s UNION ALL MATCH (a:N2) RETURN a.n AS s")
        assertTrue(union.startsWith("SELECT /*+ $hint */ u.result"), union)
        val declared = compiled("MATCH (a:N1), (b:N2) RETURN reduce(t = 0, x IN [a.n, b.n] | t + x)")
        assertTrue(declared.startsWith("WITH\nFUNCTION ") && "\nSELECT /*+ $hint */ " in declared, declared)
    }

    @Test
    fun `a statement that writes has the hint in each of its queries that read two tables`() {
        val sql = compiled("MATCH (a:N1), (b:N2) SET a.x = a.n + b.n RETURN a.x")
        assertTrue(sql.startsWith("DECLARE"), sql)
        assertTrue(Regex("SELECT /\\*\\+ $hint \\*/ [^;]*INTO").containsMatchIn(sql), sql)
    }
}
