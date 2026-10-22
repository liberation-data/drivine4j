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
    fun `a marker may be told its place among the parameters, for a statement JDBC does not bind`() {
        val named = SqlParameters.positional("SELECT * FROM t WHERE a = \$a AND b = '\$b' OR a = \$a") { ":b$it" }
        assertEquals("SELECT * FROM t WHERE a = :b0 AND b = '\$b' OR a = :b1" to listOf("a", "a"), named.statement to named.names)
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

    @Test
    fun `a dollar that follows a parameter's name continues it, and is not a second parameter`() {
        // Oracle allows `$` inside an identifier, so `$a$b` is read as the parameter `a` followed by the text `$b`.
        assertEquals("?\$b" to listOf("a"), positional("\$a\$b"))
    }

    @Test
    fun `a name may begin with an underscore`() {
        assertEquals("SELECT ? FROM dual" to listOf("_name"), positional("SELECT \$_name FROM dual"))
    }

    @Test
    fun `a dollar at the very end names nothing`() {
        assertEquals("SELECT 1 FROM dual WHERE x = \$" to emptyList(), positional("SELECT 1 FROM dual WHERE x = \$"))
    }

    @Test
    fun `an unterminated literal runs to the end, so nothing after it is a parameter`() {
        assertEquals("SELECT 'abc \$a FROM t WHERE b = \$b" to emptyList(), positional("SELECT 'abc \$a FROM t WHERE b = \$b"))
        assertEquals("SELECT q'[abc \$a FROM t WHERE b = \$b" to emptyList(), positional("SELECT q'[abc \$a FROM t WHERE b = \$b"))
    }

    @Test
    fun `a PLSQL inquiry directive passes through, its second dollar continuing the first`() {
        assertEquals("BEGIN x := \$\$PLSQL_UNIT; y := ?; END;" to listOf("y"), positional("BEGIN x := \$\$PLSQL_UNIT; y := \$y; END;"))
    }

    @Test
    fun `a directive inside a quoted identifier is part of the identifier`() {
        val sql = "SELECT \"\$IF\", \"\$x\" FROM t WHERE a = \$a"
        assertEquals(sql.replace("= \$a", "= ?") to listOf("a"), positional(sql))
    }

    @Test
    fun `an identifier that ends in n before a quote is not the prefix of a national literal`() {
        val sql = "SELECT CASE WHEN'x' = \$a THEN 1 END, DECODE(n'x', \$b, 1) FROM dual WHERE n = \$c"
        assertEquals(sql.replace("= \$a", "= ?").replace(", \$b", ", ?").replace("= \$c", "= ?") to listOf("a", "b", "c"), positional(sql))
    }

    @Test
    fun `a parameter right after a literal and an operator is a parameter`() {
        assertEquals("SELECT 'x'||? FROM dual" to listOf("a"), positional("SELECT 'x'||\$a FROM dual"))
    }

    @Test
    fun `a dollar-quoted body is left as it is, whatever its tag`() {
        val body = "CREATE FUNCTION f(p text) RETURNS text AS \$fn\$ BEGIN RETURN \$x || p; END; \$fn\$ LANGUAGE plpgsql"
        assertEquals(body to emptyList(), positional(body))
        val plain = "DO \$\$ BEGIN PERFORM \$x; END \$\$; SELECT \$y"
        assertEquals(plain.replace("SELECT \$y", "SELECT ?") to listOf("y"), positional(plain))
    }

    @Test
    fun `a dollar quote that is never closed is no quote`() {
        assertEquals("SELECT ?\$x FROM t WHERE a = ?" to listOf("t", "a"), positional("SELECT \$t\$x FROM t WHERE a = \$a"))
    }

    @Test
    fun `two inquiry directives are not a dollar quote between them`() {
        assertEquals("BEGIN x := \$\$PLSQL_UNIT || ?; y := \$\$PLSQL_LINE; END;" to listOf("z"), positional("BEGIN x := \$\$PLSQL_UNIT || \$z; y := \$\$PLSQL_LINE; END;"))
    }
}
