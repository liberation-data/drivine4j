package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, CONTAINS looks for a text with `INSTR`, as `VARCHAR2`s first, and where that gives null as CLOBs. `INSTR` refuses
 * to look for a text past 32,767 UTF-16 units (ORA-22828), so a longer one is compared with `DBMS_LOB.COMPARE` where it is as
 * long as the text, and else at each place its first 1,000 units are, carried from one to the next by `MODEL` (#312).
 */
class OracleContainsTextTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, mapOf("t" to "abc", "x" to "b")).sql

    @Test
    fun `a text is looked for by INSTR as a VARCHAR2, and as a CLOB by INSTR, DBMS_LOB COMPARE or a search by its first units`() {
        val sql = sql("WITH \$t AS t, \$x AS x RETURN t CONTAINS x")
        val short = "COALESCE((INSTR(CASE WHEN"
        assertTrue(sql.contains(short), sql)
        val long = sql.substringAfter(short).substringAfter("(SELECT (CASE WHEN ")
        val (t, x) = Regex("^(sh\\d+\\.v1) IS NULL OR (sh\\d+\\.v2) IS NULL THEN NULL ").find(long)!!.destructured
        // The CLOBs, bound once.
        assertTrue(long.contains("JSON_VALUE(w2.c1, '\$' RETURNING CLOB) END AS v1"), long)
        // Each a number, and only the CASE compared with 1: Oracle found (SELECT … MODEL …) = 1 false where the subquery gave 1.
        assertTrue(long.contains("WHEN LENGTH($x) <= 32767 THEN SIGN(INSTR($t, $x)) "), long)
        assertTrue(long.contains("WHEN LENGTH($x) >= LENGTH($t) THEN CASE WHEN LENGTH($x) = LENGTH($t) AND DBMS_LOB.COMPARE($t, $x) = 0 THEN 1 ELSE 0 END "), long)
        val search = "ELSE (SELECT cs_found FROM DUAL MODEL DIMENSION BY (0 cs_i) MEASURES (INSTR($t, SUBSTR($x, 1, 1000)) cs_at, 0 cs_found) " +
            "RULES ITERATE (4294967295) UNTIL (cs_found[0] <> 0) (cs_found[0] = CASE WHEN cs_at[0] = 0 OR cs_at[0] > LENGTH($t) - LENGTH($x) + 1 THEN -1 " +
            "WHEN DBMS_LOB.COMPARE($t, $x, LENGTH($x), cs_at[0], 1) = 0 THEN 1 ELSE 0 END, " +
            "cs_at[0] = CASE WHEN cs_found[0] = 0 THEN INSTR($t, SUBSTR($x, 1, 1000), cs_at[0] + 1) ELSE cs_at[0] END)) END = 1)"
        assertTrue(long.contains(search), long)
        // A short text's VARCHAR2 is looked for as before, and only by INSTR.
        assertEquals(1, Regex("MODEL DIMENSION BY").findAll(sql).count(), sql)
        assertTrue(!sql.substringBefore(", (SELECT (CASE WHEN").contains("DBMS_LOB"), sql)
    }

    @Test
    fun `a stored text is bound by its own path for its CLOB, and on Postgres and SQLite a text is looked for as it was`() {
        val stored = sql("MATCH (n:Doc) RETURN n.t CONTAINS n.x")
        // Read inside the bound subquery, as #370 has a property beside one read.
        assertTrue(stored.contains("JSON_VALUE(n1.props, '\$.t' RETURNING CLOB) END AS v1"), stored)
        assertTrue(stored.contains("JSON_VALUE(n1.props, '\$.x' RETURNING CLOB) END AS v2"), stored)
        assertTrue(stored.contains("(INSTR(CASE WHEN JSON_VALUE(n1.props, '\$.t.type()') = 'string' THEN JSON_VALUE(n1.props, '\$.t') END"), stored)
        val postgres = PostgresCypherCompiler("g").compile("WITH \$t AS t, \$x AS x RETURN t CONTAINS x", mapOf("t" to "abc", "x" to "b")).sql
        assertTrue(postgres.contains("strpos(") && !postgres.contains("MODEL"), postgres)
        val sqlite = SqliteCypherCompiler("g").compile("WITH \$t AS t, \$x AS x RETURN t CONTAINS x", mapOf("t" to "abc", "x" to "b")).sql
        assertTrue(sqlite.contains("(instr(") && !sqlite.contains("MODEL"), sqlite)
    }

    @Test
    fun `CONTAINS in a write, a WHERE and beside a long value takes the same search`() {
        val written = OracleCypherCompiler("g").compile("MATCH (n:Doc) SET n.r = n.t CONTAINS \$x", mapOf("x" to "b")).sql
        assertTrue(written.contains("MODEL DIMENSION BY (0 cs_i)"), written)
        val filtered = sql("MATCH (n:Doc) WHERE n.t CONTAINS \$x RETURN n")
        assertTrue(filtered.contains("MODEL DIMENSION BY (0 cs_i)"), filtered)
        val beside = OracleCypherCompiler("g").compile(
            "MATCH (n:Doc), (m:Doc) WHERE n.t CONTAINS toLower(trim(coalesce(\$p.q, m.name))) RETURN count(*)", mapOf("p" to mapOf("q" to null)),
        ).sql
        assertTrue(beside.contains("MODEL DIMENSION BY (0 cs_i)") && beside.contains("JSON_VALUE(n1.props, '\$.t' RETURNING CLOB) END AS v"), beside)
    }
}
