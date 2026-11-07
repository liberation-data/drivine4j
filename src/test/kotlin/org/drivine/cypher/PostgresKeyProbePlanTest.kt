package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SqlParameters
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Postgres, a row of a write's or a list's rows reads its node or relationship back by its id through a probe of the
 * table's key, `LEFT JOIN LATERAL (… WHERE nid = … OFFSET 0)`, not a plain join, which is planned by the table's
 * statistics (#644): see `PostgresCypherCompiler.keyJoin`.
 */
class PostgresKeyProbeTest {

    private fun compiled(cypher: String) = PostgresCypherCompiler("g").compile(cypher).sql

    private val nodeProbe = Regex("""LEFT JOIN LATERAL \(\s*SELECT \* FROM g_node (\w+) WHERE \1\.nid = .* OFFSET 0\s*\) \1 ON TRUE""")
    private val edgeProbe = Regex("""LEFT JOIN LATERAL \(\s*SELECT \* FROM g_edge (\w+) WHERE \1\.eid = .* OFFSET 0\s*\) \1 ON TRUE""")
    private val plain = Regex("""LEFT JOIN g_(node|edge) \w+ ON""")

    /** Each statement, with whether it reads relationships back by id: one for each place a row is joined to its table. */
    private val statements = listOf(
        // A write's rows, read back for the statement after its loop.
        "MATCH (n:CyLoop) FOREACH (x IN [1] | CREATE (n)-[r:CY_SELF]->(n) SET r.w = x)" to false,
        "MATCH ()-[r:CY_SELF]->() FOREACH (x IN [1] | SET r.w = x)" to true,
        // A list of nodes or relationships unwound.
        "MATCH (n:CyLoop) WITH collect(n) AS ns UNWIND ns AS m RETURN m.i" to false,
        "MATCH ()-[r:CY_SELF]->() WITH collect(r) AS rs UNWIND rs AS x RETURN x.w" to true,
        // A list of nodes or relationships returned whole, and read item by item.
        "MATCH (n:CyLoop) WITH collect(n) AS ns RETURN ns" to false,
        "MATCH ()-[r:CY_SELF]->() WITH collect(r) AS rs RETURN rs" to true,
        "MATCH (n:CyLoop) WITH collect(n) AS ns RETURN [m IN ns WHERE m.i > 1 | m.i]" to false,
        "MATCH ()-[r:CY_SELF]->() WITH collect(r) AS rs RETURN [x IN rs WHERE x.w > 1 | x.w]" to true,
    )

    @Test
    fun `a row reads its node or relationship back by a probe of the table's key, and by no plain join`() {
        statements.forEach { (cypher, relationships) ->
            val sql = compiled(cypher)
            assertTrue((if (relationships) edgeProbe else nodeProbe).containsMatchIn(sql), "$cypher:\n$sql")
            assertFalse(plain.containsMatchIn(sql), "$cypher:\n$sql")
        }
    }

    @Test
    fun `Oracle and SQLite join a row to its table as they did`() {
        val cypher = "MATCH (n:CyLoop) WITH collect(n) AS ns UNWIND ns AS m RETURN m.i"
        listOf(OracleCypherCompiler("g").compile(cypher).sql, SqliteCypherCompiler("g").compile(cypher).sql).forEach { sql ->
            assertFalse(Regex("""LATERAL \(\s*SELECT \* FROM""").containsMatchIn(sql), sql)
            assertTrue(Regex("""LEFT JOIN \S*g_node\S* \w+ ON""", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
        }
    }
}

/**
 * The plans Postgres makes for a write that reads its rows' nodes or relationships back by id, where the table's statistics
 * say it is empty while it still has pages: as an ANALYZE (or an autoanalyze) just after a `DELETE` leaves it, before the
 * rows are written again (#644). A plain join was planned to compare each row with every row of a scan materialized, and a
 * `FOREACH` over 20,000 nodes took 19 s where it takes under one. The probe is planned as a scan of the key's index.
 *
 * Each write runs on a connection of its own, as PL/pgSQL keeps a plan for its session: a pooled connection that planned the
 * statement before, with the statistics right, keeps that plan. The plans are logged by auto_explain, as notices.
 */
@PostgresTests
class PostgresKeyProbePlanTest {

    private val graph = "cy_probe"
    private val pm = PostgresTestDatabase.graphManager(graph)
    private val rows = 2000

    @AfterEach
    fun cleared() = PostgresTestDatabase.run(
        "ALTER TABLE ${graph}_node RESET (autovacuum_enabled)", "ALTER TABLE ${graph}_edge RESET (autovacuum_enabled)", "TRUNCATE ${graph}_edge, ${graph}_node",
    )

    private fun cypher(cypher: String) = pm.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(mapOf("n" to rows)))

    /**
     * [rows] nodes, each with a relationship to itself where [relationships], made, deleted and made again, with the tables
     * analyzed while they were empty: their statistics say they hold no rows, over the pages the first ones left.
     */
    private fun stale(relationships: Boolean) {
        PostgresTestDatabase.run(
            "TRUNCATE ${graph}_edge, ${graph}_node",
            "ALTER TABLE ${graph}_node SET (autovacuum_enabled = off)", "ALTER TABLE ${graph}_edge SET (autovacuum_enabled = off)",
        )
        val made = if (relationships) "UNWIND range(1, \$n) AS i CREATE (a:CyLoop {i: i})-[:CY_SELF]->(a)" else "UNWIND range(1, \$n) AS i CREATE (:CyLoop {i: i})"
        cypher(made)
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.run("ANALYZE ${graph}_node", "ANALYZE ${graph}_edge")
        cypher(made)
    }

    /** The plans of the statements [sql] ran, on a connection of its own, with [parameters] bound by position. */
    private fun plans(sql: String, parameters: Map<String, Any?>): List<String> =
        DriverManager.getConnection(PostgresTestContainer.getJdbcUrl(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword()).use { c ->
            c.createStatement().use { st ->
                listOf(
                    "LOAD 'auto_explain'", "SET auto_explain.log_min_duration = 0", "SET auto_explain.log_nested_statements = on",
                    "SET auto_explain.log_level = notice", "SET client_min_messages = notice",
                ).forEach(st::execute)
            }
            val positional = SqlParameters.positional(sql)
            c.prepareStatement(positional.statement).use { st ->
                positional.names.forEachIndexed { i, name ->
                    when (val value = parameters[name]) {
                        null -> st.setNull(i + 1, Types.VARCHAR)
                        is String -> st.setString(i + 1, value)
                        else -> st.setObject(i + 1, value)
                    }
                }
                st.execute()
                generateSequence(st.warnings) { it.nextWarning }.mapNotNull { it.message }.toList()
            }
        }

    /** The plans of those of [cypher]'s statements that join its rows to the table [table], with the statistics stale. */
    private fun joinPlans(cypher: String, table: String, relationships: Boolean, compiled: (String) -> String = { it }): List<String> {
        stale(relationships)
        val statement = PostgresCypherCompiler(graph).compile(cypher)
        val joins = plans(compiled(statement.sql), statement.parameters).filter { "jsonb_array_elements" in it && " $table " in it }
        assertTrue(joins.isNotEmpty(), "$cypher logged no join of its rows to $table")
        return joins
    }

    /** Whether [plan] materializes a scan of [table], of the whole table or of its key's whole index, to compare each row with. */
    private fun materialized(plan: String, table: String) =
        Regex("""Materialize\s*\(.*\n\s*->\s+(Seq Scan|Index (Only )?Scan using \S+) on $table """).containsMatchIn(plan)

    /** Whether [plan] reads [table] by its key's index for the row's id: a scan of the index with the id as its condition. */
    private fun probed(plan: String, table: String) =
        Regex("""Index (Only )?Scan using ${table}_pkey on $table \w+.*\n\s*Index Cond: \((\w+\.)?[ne]id = """).containsMatchIn(plan)

    /** [sql] with each probe of a key put back as the plain join it was, as Oracle's and SQLite's compilers still join. */
    private fun plain(sql: String) = Regex("""LEFT JOIN LATERAL \(\s*SELECT \* FROM (\S+) (\w+) WHERE (\2\.\w+ = .*?) OFFSET 0\s*\) \2 ON TRUE""")
        .replace(sql) { m -> "LEFT JOIN ${m.groupValues[1]} ${m.groupValues[2]} ON ${m.groupValues[3]}" }

    @Test
    fun `a write reads its rows' nodes back by the key's index, where the node table's statistics say it is empty`() {
        val cypher = "MATCH (n:CyLoop) FOREACH (x IN [1] | CREATE (n)-[r:CY_SELF]->(n) SET r.w = x)"
        val table = "${graph}_node"
        // The state is the one the probe is for: joined plainly, the rows are compared with a scan of every node materialized.
        val unprobed = joinPlans(cypher, table, relationships = false, ::plain)
        assertTrue(unprobed.any { materialized(it, table) }, "the plain join was not materialized:\n${unprobed.joinToString("\n---\n")}")
        joinPlans(cypher, table, relationships = false).forEach { plan ->
            assertFalse(materialized(plan, table), plan)
            assertTrue(probed(plan, table), plan)
        }
        assertEquals(listOf(rows.toLong()), cypher("MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) WHERE r.w = 1 RETURN count(r)"))
    }

    @Test
    fun `a write reads its rows' relationships back by the key's index, where the edge table's statistics say it is empty`() {
        val cypher = "MATCH ()-[r:CY_SELF]->() FOREACH (x IN [1] | SET r.w = x)"
        val table = "${graph}_edge"
        val unprobed = joinPlans(cypher, table, relationships = true, ::plain)
        assertTrue(unprobed.any { materialized(it, table) }, "the plain join was not materialized:\n${unprobed.joinToString("\n---\n")}")
        joinPlans(cypher, table, relationships = true).forEach { plan ->
            assertFalse(materialized(plan, table), plan)
            assertTrue(probed(plan, table), plan)
        }
        assertEquals(listOf(rows.toLong()), cypher("MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) WHERE r.w = 1 RETURN count(r)"))
    }
}
