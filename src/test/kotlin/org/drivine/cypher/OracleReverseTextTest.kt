package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, `reverse()` reverses a text as a `VARCHAR2` with `LISTAGG`, and where that gives null, a text past 4,000 bytes,
 * reads it as a CLOB and reverses it in pieces, put together as JSON (#311).
 */
class OracleReverseTextTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, mapOf("t" to "abc")).sql

    @Test
    fun `a text is reversed as a VARCHAR2 first, and where that gives null as a CLOB in pieces put together as JSON`() {
        val sql = sql("WITH \$t AS t RETURN reverse(t)")
        val short = "COALESCE(JSON_SCALAR((SELECT LISTAGG(SUBSTR("
        assertTrue(sql.contains(short), sql)
        val long = sql.substringAfter(short)
        // The CLOB, bound once, cut at each 1,000th UTF-16 unit, or one past it where a character past U+FFFF starts there.
        assertTrue(long.contains("TO_CLOB(CASE WHEN") && long.contains("JSON_VALUE(w2.c1, '\$' RETURNING CLOB) END) AS v1"), long)
        assertTrue(long.contains("CONNECT BY LEVEL <= CEIL(LENGTH(sh"), long)
        assertTrue(long.contains("CASE WHEN LENGTH(REGEXP_SUBSTR(sh") && long.contains(".v1, '.', LEVEL * 1000, 1, 'n')) = 2 THEN 1 ELSE 0 END"), long)
        assertTrue(long.contains("DBMS_LOB.SUBSTR(sh"), long)
        // Each piece reversed as a short text is, and the pieces put together last to first, written in ASCII and read as JSON.
        assertTrue(long.contains("(SELECT LISTAGG(SUBSTR(rv_piece.s, LEVEL, 1)) WITHIN GROUP (ORDER BY LEVEL DESC) FROM DUAL CONNECT BY LEVEL <= LENGTH(rv_piece.s)) r"), long)
        val joined = "JSON_SERIALIZE(JSON_ARRAYAGG('x' || rv_reversed.r ORDER BY rv_reversed.n DESC RETURNING JSON) RETURNING CLOB ASCII)"
        assertTrue(long.contains("(SELECT JSON_QUERY(JSON(REPLACE(REPLACE($joined, '\",\"x'), '[\"x', '[\"')), '\$[0]' RETURNING JSON)"), long)
        // Worked out once, and not made JSON again.
        assertEquals(1, Regex("JSON_ARRAYAGG\\('x'").findAll(sql).count(), sql)
        assertTrue(!long.contains("JSON_SCALAR(CASE WHEN LENGTH((SELECT JSON_QUERY"), long)
    }

    @Test
    fun `a stored text reversed is read by its own path, and on Postgres a text is reversed as it was`() {
        val stored = sql("MATCH (n:Doc) RETURN reverse(n.a)")
        assertTrue(stored.contains("JSON_VALUE(n1.props, '\$.a')") && stored.contains("JSON_VALUE(n1.props, '\$.a' RETURNING CLOB)"), stored)
        val postgres = PostgresCypherCompiler("g").compile("WITH \$t AS t RETURN reverse(t)", mapOf("t" to "abc")).sql
        assertTrue(postgres.contains("THEN to_jsonb(reverse(CASE WHEN jsonb_typeof(w2.c1) = 'string' THEN w2.c1 #>> '{}' END))"), postgres)
    }
}
