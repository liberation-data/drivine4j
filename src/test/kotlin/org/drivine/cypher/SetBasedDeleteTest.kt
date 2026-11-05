package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `DELETE` compiles to one statement for each table it deletes from, over all its rows at once, and not to a loop of a
 * statement for each row (#587): `MATCH (n) DETACH DELETE n` over 1.1M nodes ran 1.1M single-row deletes, 17 minutes on
 * Postgres. One that ends the statement and deletes from one table reads its ids from the match itself, so the match's
 * rows are not gathered into a list first.
 */
class SetBasedDeleteTest {

    private val compilers: List<SqlCypherCompiler> = listOf(OracleCypherCompiler("g"), PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** The rows gathered into the procedure's list of them: the step that sets it, in PL/pgSQL and PL/SQL, or in SQLite's steps. */
    private val gathered = Regex("INTO cy_rows\\b|\"variable\":\"cy_rows\"")

    /** A loop over the rows, in PL/pgSQL and PL/SQL, or in SQLite's steps. */
    private val loop = Regex("FOR cy_record IN|\"op\":\"loop\"")

    /** How often [table]'s rows are deleted in [cypher] as compiled by [compiler], whether it loops over rows, and whether it gathers them. */
    private fun shape(compiler: SqlCypherCompiler, cypher: String, table: String): Triple<Int, Boolean, Boolean> {
        val sql = compiler.compile(cypher).sql
        return Triple(Regex("DELETE FROM $table\\b").findAll(sql).count(), loop.containsMatchIn(sql), gathered.containsMatchIn(sql))
    }

    @Test
    fun `a DETACH DELETE that ends the statement deletes the nodes its match reads in one statement, with no rows gathered`() {
        for (cypher in listOf("MATCH (n) DETACH DELETE n", "MATCH (n:X)-[:R]->() WHERE n.k > 1 DETACH DELETE n", "MATCH (n:X) WITH n ORDER BY n.k LIMIT 2 DETACH DELETE n")) {
            compilers.forEach { compiler ->
                val (deletes, loops, gathers) = shape(compiler, cypher, "g_node")
                val name = "${compiler::class.simpleName}: $cypher"
                assertEquals(1, deletes, name)
                assertFalse(loops, name)
                assertFalse(gathers, name)
            }
        }
    }

    @Test
    fun `a DELETE of relationships that ends the statement deletes them in one statement, with no rows gathered`() {
        for (cypher in listOf("MATCH ()-[r:R]->() DELETE r", "MATCH ()-[r:R]->() DETACH DELETE r")) {
            compilers.forEach { compiler ->
                val (deletes, loops, gathers) = shape(compiler, cypher, "g_edge")
                assertEquals(Triple(1, false, false), Triple(deletes, loops, gathers), "${compiler::class.simpleName}: $cypher")
            }
        }
    }

    @Test
    fun `a DELETE that ends the statement after a WITH that binds a value gathers the rows, and deletes them in one statement`() {
        compilers.forEach { compiler ->
            val (deletes, loops, gathers) = shape(compiler, "MATCH (n) WITH n, n.k AS k DETACH DELETE n", "g_node")
            assertEquals(Triple(1, false, true), Triple(deletes, loops, gathers), "${compiler::class.simpleName}")
        }
    }

    @Test
    fun `a DELETE of nodes without DETACH that ends the statement gathers its rows once, so its test for relationships reads the nodes it deletes`() {
        // Read from the match twice, a match that makes a value anew for each row would test some nodes and delete others.
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (n) WHERE randomUUID() < '8' DELETE n").sql
            val name = "${compiler::class.simpleName}"
            assertEquals(1, gathered.findAll(sql).count(), name)
            assertEquals(1, Regex("DELETE FROM g_node\\b").findAll(sql).count(), name)
            assertFalse(loop.containsMatchIn(sql), name)
        }
    }

    @Test
    fun `on Oracle a DELETE that reads its match reads the key with + 0, which Oracle does not unnest into a wrong answer`() {
        assertTrue(Regex("DELETE FROM g_node WHERE nid IN \\(SELECT n\\d+\\.nid \\+ 0 AS id").containsMatchIn(OracleCypherCompiler("g").compile("MATCH (g:Gone) DETACH DELETE g").sql))
        assertTrue(Regex("DELETE FROM g_edge WHERE eid IN \\(SELECT (/\\*\\+ [^*]*\\*/ )?e\\d+\\.eid \\+ 0 AS id").containsMatchIn(OracleCypherCompiler("g").compile("MATCH ()-[r:R]->() DELETE r").sql))
        for (compiler in listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            val sql = compiler.compile("MATCH (g:Gone) DETACH DELETE g").sql
            assertTrue(Regex("DELETE FROM g_node WHERE nid IN \\(SELECT n\\d+\\.nid AS id").containsMatchIn(sql), "${compiler::class.simpleName}: $sql")
        }
    }

    @Test
    fun `on Oracle a DELETE that reads its match tests labels by their text, in a LATERAL's query too, which the index over labels does not answer`() {
        // Planned with the index over labels, Oracle crashed the session (ORA-07445 in qjsnmvFindOpt) after rolled-back deletes of the label (#595).
        fun subquery(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql.substringAfter(" WHERE ").substringAfter("IN (SELECT ")
        for (cypher in listOf(
            "MATCH (a:X) OPTIONAL MATCH (a)-[r:R]->(:X {k: 2}) DELETE r",
            "MATCH (a:X) OPTIONAL MATCH (a)-[:R]->(m:X {k: 3}) DETACH DELETE m",
            "MATCH (g:Gone:Also) DETACH DELETE g",
        )) {
            val read = subquery(cypher)
            assertFalse(Regex("JSON_EXISTS\\([\\w.]+\\.labels").containsMatchIn(read), "$cypher: $read")
            assertTrue(Regex("INSTR\\(',' \\|\\| LTRIM\\(RTRIM\\(JSON_SERIALIZE\\(n\\d+\\.labels\\)").containsMatchIn(read), "$cypher: $read")
        }
        assertEquals(2, Regex("JSON_SERIALIZE\\(n\\d+\\.labels\\)").findAll(subquery("MATCH (a:X) OPTIONAL MATCH (a)-[r:R]->(:X {k: 2}) DELETE r")).count())
        // A label that SQL and JSON quote, or with a space, a backslash before a line break, or past ASCII, is looked for as itself.
        for (label in listOf("it's \"x\\", "a \\\nb é")) {
            val read = subquery("MATCH (g:`$label`) DETACH DELETE g")
            val alias = Regex("JSON_SERIALIZE\\((n\\d+\\.labels)\\)").find(read)!!.groupValues[1]
            assertTrue(org.drivine.backend.oracle.OracleSql.holdsLabel(alias, label) in read, read)
        }
        // A string of the statement's that reads as the test is a string still.
        val read = subquery("MATCH (g:Gone) WHERE g.s = \"JSON_EXISTS(n1.labels, '\$[*]?(@ == \\\"Y\\\")')\" DETACH DELETE g")
        assertEquals(1, Regex("JSON_EXISTS\\(n1\\.labels").findAll(read).count(), read)
        assertFalse(org.drivine.backend.oracle.OracleSql.holdsLabel("n1.labels", "Y") in read, read)
        // A read that deletes nothing keeps the test the index answers.
        assertTrue("JSON_EXISTS(" in OracleCypherCompiler("g").compile("MATCH (g:Gone) RETURN g.k").sql)
    }

    @Test
    fun `a DELETE that ends the statement refuses what a DELETE over the rows refuses`() {
        compilers.forEach { compiler ->
            val name = "${compiler::class.simpleName}"
            assertEquals(
                "m is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                assertFailsWith<DrivineException>(name) { compiler.compile("MATCH (n) DETACH DELETE m") }.message,
                name,
            )
            assertEquals(
                "A DELETE of anything but a variable is Cypher, and is not compiled to SQL yet.",
                assertFailsWith<DrivineException>(name) { compiler.compile("MATCH (n) DETACH DELETE n.x") }.message,
                name,
            )
        }
    }

    @Test
    fun `a DELETE with rows after it, or of nodes and relationships, deletes each table's in one statement over the rows, with no loop`() {
        for ((cypher, tables) in listOf(
            "MATCH (n) DETACH DELETE n RETURN count(*)" to listOf(0, 1),
            "MATCH (n) DELETE n" to listOf(0, 1),
            "MATCH (a)-[r]->(b) DELETE r, a" to listOf(1, 1),
            "MATCH (a)-[r]->(b) DETACH DELETE r, a, b" to listOf(1, 1),
            "MATCH (a)-[r]->(b) DETACH DELETE a DELETE r" to listOf(1, 1),
            "MATCH (n) WITH n, n.k AS k DETACH DELETE n RETURN sum(k)" to listOf(0, 1),
        )) {
            compilers.forEach { compiler ->
                val name = "${compiler::class.simpleName}: $cypher"
                assertEquals(tables, listOf("g_edge", "g_node").map { shape(compiler, cypher, it).first }, name)
                assertFalse(shape(compiler, cypher, "g_node").second, name)
            }
        }
    }
}
