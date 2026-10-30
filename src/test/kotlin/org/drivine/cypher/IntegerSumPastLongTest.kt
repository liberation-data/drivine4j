package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle and Postgres, which sum integers exactly, an integer `sum` is a double where its positive values, or its
 * negative ones, pass a long's range, as Neo4j goes on summing as a double where a long would overflow (#339).
 */
class IntegerSumPastLongTest {

    private fun value(type: String) = "JSON_VALUE(u1.item, '\$?(@.type() != \"string\")' RETURNING $type)"

    @Test
    fun `on Oracle an integer sum is summed as doubles, as floats are, where its positive or negative values pass a long's range`() {
        val sql = OracleCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        val number = value("NUMBER")
        assertTrue(
            sql.contains(
                " IS NOT NULL OR sum(GREATEST($number, 0)) > 9223372036854775807 OR sum($number) - sum(GREATEST($number, 0)) < -9223372036854775808 " +
                    "THEN JSON_SCALAR(sum(${value("BINARY_DOUBLE")})) ELSE JSON_SCALAR(COALESCE(sum($number), 0)) END"
            ),
            sql,
        )
    }

    @Test
    fun `DISTINCT sums each value once in each aggregate`() {
        val sql = OracleCypherCompiler("g").compile("UNWIND [1, 1] AS x RETURN sum(DISTINCT x)").sql
        val number = value("NUMBER")
        assertTrue(sql.contains("sum(DISTINCT GREATEST($number, 0)) > 9223372036854775807 OR sum(DISTINCT $number) - sum(DISTINCT GREATEST($number, 0)) < "), sql)
        assertEquals(5, Regex("sum\\(DISTINCT ").findAll(sql).count(), sql)
        assertEquals(0, Regex("sum\\((?!DISTINCT)").findAll(sql).count(), sql)
    }

    @Test
    fun `on Postgres the double is rounded through double precision's text, and SQLite's sum fails past the range as it did`() {
        val postgres = PostgresCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        assertTrue(postgres.contains("CASE WHEN sum(GREATEST(CASE WHEN jsonb_typeof(u1.item) = 'number' THEN CAST(u1.item #>> '{}' AS numeric) END, 0)) > 9223372036854775807 OR sum(CASE WHEN"), postgres)
        assertTrue(postgres.contains("THEN to_jsonb((trim_scale(CAST(CAST(CAST(sum("), postgres)
        assertTrue(postgres.contains(") AS double precision) AS text) AS numeric)) + 0.0)) ELSE to_jsonb(COALESCE(sum("), postgres)
        val sqlite = SqliteCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        assertFalse(sqlite.contains("GREATEST"), sqlite)
        assertTrue(sqlite.contains("COALESCE(sum("), sqlite)
    }
}
