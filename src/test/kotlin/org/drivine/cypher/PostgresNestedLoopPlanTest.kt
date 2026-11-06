package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On Postgres, a loop whose statements for each row read a table by its key where the loop adds to it, or join a table to a
 * JSON list of rows, is planned as nested loops over the indexes, and the planner's settings are put back after it
 * (#609, #631): see `PostgresCypherCompiler.plannedAsNestedLoops`. Their times are [CompiledWriteKeyedReadTimePostgresTest]'s.
 */
class PostgresNestedLoopPlanTest {

    private fun compiled(cypher: String) = PostgresCypherCompiler("g").compile(cypher).sql

    private val off = "PERFORM set_config('enable_seqscan', 'off', true), set_config('enable_hashjoin', 'off', true), set_config('enable_mergejoin', 'off', true), set_config('enable_material', 'off', true);"
    private val saved = "cy_planner := ARRAY[current_setting('enable_seqscan'), current_setting('enable_hashjoin'), current_setting('enable_mergejoin'), current_setting('enable_material')];"
    private val restored = "PERFORM set_config('enable_seqscan', cy_planner[1], true), set_config('enable_hashjoin', cy_planner[2], true), set_config('enable_mergejoin', cy_planner[3], true), set_config('enable_material', cy_planner[4], true);"

    @Test
    fun `a MERGE's loop is planned with seq scans, hash and merge joins and materialization off, and the settings are put back after it`() {
        val lines = compiled("MATCH (n:CyLoop) MERGE (n)-[:CY_SELF]->(n)").lines()
        assertTrue("    cy_planner text[];" in lines, lines.joinToString("\n"))
        val loop = lines.indexOfFirst { it.startsWith("    FOR cy_record IN ") }
        val end = lines.indexOf("    END LOOP;")
        assertEquals(listOf("    $saved", "    $off"), lines.subList(loop - 2, loop), lines.joinToString("\n"))
        assertEquals("    $restored", lines[end + 1], lines.joinToString("\n"))
        // Once each: the MERGE's own read of its pattern, in the loop, is planned with it.
        assertEquals(1, lines.count { it.trim() == off })
        assertTrue(lines.subList(loop, end).any { it.trim().startsWith("FOR cy_matched IN (") })
    }

    @Test
    fun `each of two loops that qualify saves the settings before it and puts them back after it`() {
        val lines = compiled("MATCH (n:CyLoop) MERGE (n)-[:CY_A]->(n) WITH n MERGE (n)-[:CY_B]->(n)").lines().map { it.trim() }
        val loops = lines.indices.filter { lines[it].startsWith("FOR cy_record IN ") }
        val ends = lines.indices.filter { lines[it] == restored }
        assertEquals(2, loops.size, lines.joinToString("\n"))
        assertEquals(loops.map { listOf(saved, off) }, loops.map { lines.subList(it - 2, it) }, lines.joinToString("\n"))
        assertEquals(2, ends.size, lines.joinToString("\n"))
        assertTrue(loops[0] < ends[0] && ends[0] < loops[1] - 2 && loops[1] < ends[1], lines.joinToString("\n"))
    }

    @Test
    fun `a loop that reads by key what it makes, or joins a table to a row's list, is planned so`() {
        listOf(
            "MATCH (n:CyLoop) MERGE (n)-[r:CY_SELF]->(n) ON CREATE SET r.w = n.i",
            "MATCH (n:CyLoop) CALL (n) { MERGE (n)-[:CY_SELF]->(n) } RETURN count(n)",
            "MATCH (n:CyLoop) CALL (n) { CREATE (n)-[r:CY_SELF]->(n) SET r.w = n.i } RETURN count(n)",
            "MATCH (n:CyLoop) FOREACH (x IN [1] | CREATE (n)-[r:CY_SELF]->(n) SET r.w = x)",
            "UNWIND range(1, 3) AS i CALL (i) { CREATE (a:CyLoop {i: i}) SET a.y = i } RETURN count(i)",
            "MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN n.i AS j } RETURN count(j)",
            "MATCH (n:CyLoop) CALL (n) { CREATE (m:CyMade) DETACH DELETE m } RETURN count(n)",
        ).forEach { cypher -> assertTrue(off in compiled(cypher), "$cypher: ${compiled(cypher)}") }
    }

    @Test
    fun `a loop with neither read, and a statement with no loop, are compiled as they were`() {
        listOf(
            "UNWIND range(1, 3) AS i CREATE (:CyLoop {i: i})",
            "MATCH (n:CyLoop) SET n.x = n.i",
            "MATCH (n:CyLoop) REMOVE n.x",
            "MATCH (n:CyLoop) CREATE (n)-[r:CY_SELF]->(n) SET r.w = n.i",
            "MATCH (n:CyLoop) DETACH DELETE n",
            "MATCH (n:CyLoop) RETURN n.i",
        ).forEach { cypher ->
            val sql = compiled(cypher)
            assertFalse("enable_seqscan" in sql || "cy_planner" in sql, "$cypher: $sql")
        }
    }

    @Test
    fun `Oracle and SQLite plan their loops as they did`() {
        val cypher = "MATCH (n:CyLoop) MERGE (n)-[:CY_SELF]->(n)"
        assertFalse("enable_seqscan" in OracleCypherCompiler("g").compile(cypher).sql)
        assertFalse("enable_seqscan" in SqliteCypherCompiler("g").compile(cypher).sql)
    }
}

/**
 * The planner's settings, after a procedure that set them off for a loop, are those the session or its transaction had before
 * it, and where the loop fails, those it had before the procedure: Postgres undoes `set_config(…, true)` with the transaction
 * or savepoint the failure rolls back.
 */
@PostgresTests
class PostgresNestedLoopSettingsTest {

    private val merge = "MATCH (n:CyLoop) MERGE (n)-[:CY_SELF]->(n)"

    /**
     * The settings after [cypher], compiled, ran in a transaction that set [setup] first, then rolled back. [fails] is a part
     * of the error the call must raise, so that a failure before the loop turns the settings off doesn't pass for one in it.
     */
    private fun settingsAfter(setup: String, cypher: String, nodes: Boolean = false, fails: String? = null): List<String> {
        PostgresTestDatabase.graphManager("cy_planner")
        val compiled = PostgresCypherCompiler("cy_planner").compile(cypher).sql
        assertTrue("set_config('enable_seqscan', 'off', true)" in compiled, compiled)
        return DriverManager.getConnection(PostgresTestContainer.getJdbcUrl(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword()).use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute(setup)
                    if (nodes) st.execute("""INSERT INTO cy_planner_node (labels, props) VALUES ('["CyLoop"]', '{}')""")
                    st.execute(compiled.substringBefore("\nSELECT cy.result FROM "))
                    val function = Regex("""pg_temp\.drivine_cypher_[0-9a-f]+""").find(compiled)!!.value
                    val savepoint = c.setSavepoint()
                    val error = runCatching { st.executeQuery("SELECT count(*) FROM $function('{}'::jsonb)").close() }.exceptionOrNull()
                    if (fails == null) assertNull(error, "$cypher failed")
                    else assertTrue(error?.message.orEmpty().contains(fails), "$cypher failed with: ${error?.message}")
                    if (error != null) c.rollback(savepoint)
                    st.executeQuery("SELECT current_setting('enable_seqscan'), current_setting('enable_hashjoin'), current_setting('enable_mergejoin'), current_setting('enable_material')").use { rs ->
                        rs.next()
                        listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                    }
                }
            } finally {
                c.rollback()
            }
        }
    }

    @Test
    fun `a procedure puts the settings back as the session had them, in its transaction`() {
        assertEquals(listOf("on", "off", "on", "on"), settingsAfter("SET enable_hashjoin = off", merge))
    }

    @Test
    fun `a procedure puts the settings back as its transaction had them`() {
        assertEquals(listOf("on", "off", "on", "on"), settingsAfter("SET LOCAL enable_hashjoin = off", merge, nodes = true))
    }

    @Test
    fun `a procedure that fails in its loop leaves the settings as they were, once its savepoint is rolled back`() {
        assertEquals(listOf("on", "off", "on", "on"), settingsAfter("SET LOCAL enable_hashjoin = off", "MATCH (n:CyLoop) MERGE (n)-[:CY_SELF {w: n.missing}]->(n)", nodes = true, fails = "because of null property value for 'w'"))
    }
}
