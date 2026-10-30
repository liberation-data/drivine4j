package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle and Postgres, which sum integers exactly, an integer `sum` past a long's range is a double, as on Neo4j (#339):
 * the test reads the sum the statement works out anyway.
 */
class IntegerSumPastLongTest {

    private fun value(type: String) = "JSON_VALUE(u1.item, '\$?(@.type() != \"string\")' RETURNING $type)"

    @Test
    fun `on Oracle an integer sum is summed as doubles, as floats are, where it is past a long's range`() {
        val sql = OracleCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        val number = value("NUMBER")
        assertTrue(
            sql.contains(
                " IS NOT NULL OR sum($number) NOT BETWEEN -9223372036854775808 AND 9223372036854775807 " +
                    "THEN JSON_SCALAR(sum(${value("BINARY_DOUBLE")})) ELSE JSON_SCALAR(COALESCE(sum($number), 0)) END"
            ),
            sql,
        )
    }

    @Test
    fun `DISTINCT sums each value once in each aggregate`() {
        val sql = OracleCypherCompiler("g").compile("UNWIND [1, 1] AS x RETURN sum(DISTINCT x)").sql
        val number = value("NUMBER")
        assertTrue(sql.contains(" OR sum(DISTINCT $number) NOT BETWEEN -9223372036854775808 AND 9223372036854775807 THEN"), sql)
        assertEquals(3, Regex("sum\\(DISTINCT ").findAll(sql).count(), sql)
        assertEquals(0, Regex("sum\\((?!DISTINCT)").findAll(sql).count(), sql)
    }

    @Test
    fun `on Postgres the double is rounded through double precision's text, and SQLite's sum fails past the range as it did`() {
        val postgres = PostgresCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        assertTrue(postgres.contains("CASE WHEN sum(CASE WHEN jsonb_typeof(u1.item) = 'number' THEN CAST(u1.item #>> '{}' AS numeric) END) NOT BETWEEN -9223372036854775808 AND 9223372036854775807 THEN"), postgres)
        assertTrue(postgres.contains("THEN to_jsonb((trim_scale(CAST(CAST(CAST(sum("), postgres)
        assertTrue(postgres.contains(") AS double precision) AS text) AS numeric)) + 0.0)) ELSE to_jsonb(COALESCE(sum("), postgres)
        val sqlite = SqliteCypherCompiler("g").compile("UNWIND [1, 2] AS x RETURN sum(x)").sql
        assertFalse(sqlite.contains("NOT BETWEEN"), sqlite)
        assertTrue(sqlite.contains("COALESCE(sum("), sqlite)
    }
}
