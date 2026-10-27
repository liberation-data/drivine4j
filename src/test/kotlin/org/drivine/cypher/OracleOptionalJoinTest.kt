package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * On Oracle, an `OPTIONAL CALL` or an `OPTIONAL MATCH` is a lateral join on `1 = 1`: in a scalar subquery, as a `COUNT { … }`
 * is, Oracle refused a join on `TRUE` followed by `WHERE … AND …` (ORA-00907), and crashed the session on one without (#338).
 * See docs/oracle-on-true-in-scalar-subquery-crash.md. Postgres joins on `TRUE` still.
 */
class OracleOptionalJoinTest {

    private val statements = listOf(
        "MATCH (n:CyOpt {id: 1}) RETURN COUNT { OPTIONAL CALL { MATCH (z:Nope) RETURN z.k AS u } MATCH (y:CyOpt) WHERE y.k = 'q' RETURN y }",
        "MATCH (n:CyOpt {id: 1}) RETURN COUNT { OPTIONAL CALL { MATCH (z:Nope) RETURN z.k AS u } RETURN u }",
        "MATCH (n:CyOpt {id: 1}) RETURN COUNT { OPTIONAL MATCH (z:Nope) RETURN z }",
        "OPTIONAL CALL { MATCH (z:Nope) RETURN z.k AS u } RETURN u",
        "UNWIND \$ts AS t MATCH (n {id: t.id}) SET n.a = COUNT { OPTIONAL CALL { WITH t MATCH (z:Nope) RETURN t AS u } RETURN u }",
    )

    @Test
    fun `an optional lateral join is on 1 = 1, not on TRUE`() {
        val ts = mapOf("ts" to emptyList<Any>())
        for (cypher in statements) {
            val sql = OracleCypherCompiler("g").compile(cypher, ts).sql
            assertTrue(Regex("LEFT JOIN LATERAL \\(\\n(.*\\n)*?\\s*\\) [co]\\d+ ON 1 = 1\\n").containsMatchIn(sql) && "ON TRUE" !in sql, sql)
        }
    }

    @Test
    fun `a CALL that is not optional is still a cross join, and Postgres still joins on TRUE`() {
        val called = OracleCypherCompiler("g").compile("MATCH (n:CyOpt) RETURN COUNT { CALL { MATCH (z:Nope) RETURN z.k AS u } RETURN u }").sql
        assertTrue(Regex("CROSS JOIN LATERAL \\(\\n(.*\\n)*?\\s*\\) c\\d+\\n").containsMatchIn(called) && " ON " !in called, called)
        val postgres = PostgresCypherCompiler("g").compile(statements[1]).sql
        assertTrue(Regex("\\) c\\d+ ON TRUE").containsMatchIn(postgres), postgres)
    }
}
