package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SqlParametersTest {

    private fun positional(sql: String) = SqlParameters.positional(sql).let { it.statement to it.names }

    @Test
    fun `each parameter becomes a marker, in the order they occur`() {
        assertEquals(
            "SELECT * FROM t WHERE a = ? AND b = ? OR a = ?" to listOf("a", "b_2", "a"),
            positional("SELECT * FROM t WHERE a = \$a AND b = \$b_2 OR a = \$a"),
        )
    }

    @Test
    fun `a dollar inside an identifier is not a parameter`() {
        assertEquals("SELECT banner FROM v\$version WHERE x = ?" to listOf("x"), positional("SELECT banner FROM v\$version WHERE x = \$x"))
    }

    @Test
    fun `a string literal is left as it is, a doubled quote and a JSON path with it`() {
        val sql = "SELECT JSON_VALUE(doc, '\$.name'), 'it''s \$notOne' FROM t WHERE JSON_EXISTS(doc, '\$[*]?(@ == \$l)' PASSING \$label AS \"l\")"
        assertEquals(sql.replace("\$label", "?") to listOf("label"), positional(sql))
    }

    @Test
    fun `a quoted identifier, a comment and an alternative-quoted literal are left as they are`() {
        val sql = "SELECT \"\$col\" FROM t -- \$no\n WHERE a = \$a /* \$no */ AND b = q'[it's \$no]' AND c = \$c"
        assertEquals(sql.replace("= \$a", "= ?").replace("= \$c", "= ?") to listOf("a", "c"), positional(sql))
    }

    @Test
    fun `a national literal, with or without alternative quoting, is left as it is`() {
        val sql = "SELECT n'it''s \$no', nq'[it's \$no]', NQ'{\$no}' FROM t WHERE a = \$a"
        assertEquals(sql.replace("= \$a", "= ?") to listOf("a"), positional(sql))
    }

    @Test
    fun `a conditional-compilation directive is not a parameter`() {
        val sql = "BEGIN \$IF DBMS_DB_VERSION.VER_LE_23 \$THEN NULL; \$ELSE x := \$x; \$END END;"
        assertEquals(sql.replace(":= \$x", ":= ?") to listOf("x"), positional(sql))
    }

    @Test
    fun `a dollar that names nothing is left alone`() {
        assertEquals("SELECT '1' || \$ FROM dual WHERE x = \$1" to emptyList(), positional("SELECT '1' || \$ FROM dual WHERE x = \$1"))
    }

    @Test
    fun `a SQL statement pages with OFFSET and FETCH, a Cypher one with SKIP and LIMIT`() {
        fun compiled(statement: Statement) = SpecCompiler(QuerySpecification.withStatement(statement).skip(20).limit(10)).compile().statement
        assertEquals("SELECT id FROM t ORDER BY id OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY", compiled(sqlStatement("SELECT id FROM t ORDER BY id")))
        assertEquals("MATCH (n) RETURN n SKIP 20 LIMIT 10", compiled(cypherStatement("MATCH (n) RETURN n")))
    }
}
