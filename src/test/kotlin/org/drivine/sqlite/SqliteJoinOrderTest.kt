package org.drivine.sqlite

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compiled Cypher joins its sources so that SQLite may reorder them (drivine4j#571): a `CROSS JOIN` SQLite keeps in
 * the order it is written, so a step's two nodes were scanned pair by pair before its relationship was read.
 */
class SqliteJoinOrderTest {

    private val manager = pm

    private fun compiled(cypher: String) = SqliteCypherCompiler(GRAPH).compile(cypher, emptyMap())

    /**
     * What SQLite's plan for [cypher] reads, a line for each table or rows it reads, in its order, each led by the
     * table it reads: `node: SCAN n1`, `edge: SEARCH e3 …`, or `rows: …` for any other rows.
     */
    private fun plan(cypher: String): List<String> {
        val compiled = compiled(cypher)
        val tables = Regex("""\b${GRAPH}_(node|edge) (\w+)""").findAll(compiled.sql).associate { it.groupValues[2] to it.groupValues[1] }
        return manager.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN ${compiled.sql}").bind(compiled.parameters))
            .map { row -> (row as List<*>).last() as String }
            .filter { it.startsWith("SCAN ") || it.startsWith("SEARCH ") }
            .map { line -> "${tables[line.split(" ")[1]] ?: "rows"}: $line" }
    }

    private fun count(cypher: String): Long {
        val compiled = compiled(cypher)
        return manager.query(QuerySpecification.withStatement(compiled.sql).bind(compiled.parameters).transform(Long::class.java)).single()
    }

    private fun readsNode(line: String) = line.startsWith("node: ")

    private fun readsEdge(line: String) = line.startsWith("edge: ")

    private fun scansNode(line: String) = line.startsWith("node: SCAN ")

    /** That [plan] does not scan the node table twice in a row, each whole, before it reads the edge table. */
    private fun assertNoNodePairs(cypher: String, plan: List<String>) {
        val beforeEdge = plan.takeWhile { !readsEdge(it) }
        val pairs = beforeEdge.zipWithNext().any { (a, b) -> scansNode(a) && scansNode(b) }
        assertTrue(!pairs, "$cypher scans every pair of nodes before it reads a relationship:\n${plan.joinToString("\n")}")
        assertTrue(plan.any(::readsEdge), "$cypher reads no relationship:\n${plan.joinToString("\n")}")
    }

    @Test
    fun `a directed step reads its relationship before its second node, and scans no pair of nodes`() {
        val cypher = "MATCH (n)-[r]->(m) RETURN count(*) AS c"
        val plan = plan(cypher)
        assertNoNodePairs(cypher, plan)
        assertTrue(plan.takeWhile { !readsEdge(it) }.count(::readsNode) <= 1, "both nodes are read before the relationship:\n${plan.joinToString("\n")}")
        assertEquals(EDGES, count(cypher))
    }

    @Test
    fun `a walk of one or two steps scans no pair of nodes, and gives its rows quickly`() {
        val cypher = "MATCH (n)-[*1..2]->(m) RETURN count(*) AS c"
        assertNoNodePairs(cypher, plan(cypher))
        // As compiled with CROSS JOIN this ran for more than a minute over this graph; now it takes well under a second.
        val walked = assertTimeout(Duration.ofSeconds(20)) { count(cypher) }
        assertEquals(EDGES + EDGES * 2, walked, "each node has two relationships out, each to a node with two more")
    }

    @Test
    fun `an undirected step and walk scan no pair of nodes, and give each relationship each way round`() {
        // SQLite keeps the OR across the two directions, which it probes by index, where Postgres and Oracle read both ways round (#570).
        val step = "MATCH (n)-[r]-(m) RETURN count(*) AS c"
        assertNoNodePairs(step, plan(step))
        assertEquals(EDGES * 2, count(step), "no relationship goes from a node to itself")
        val walk = "MATCH (n)-[*1..2]-(m) RETURN count(*) AS c"
        assertNoNodePairs(walk, plan(walk))
        val walked = assertTimeout(Duration.ofSeconds(20)) { count(walk) }
        assertEquals(EDGES * 2 + EDGES * 2 * 3, walked, "each node has four relationships, so three more from each node a step reaches")
    }

    @Test
    fun `a json_each that reads an earlier source's column is still read after it`() {
        val cypher = "MATCH (n)-[r]->(m) UNWIND n.tags AS t WITH t WHERE t = 'even' RETURN count(*) AS c"
        assertEquals(EDGES / 2, count(cypher))
        val lists = "MATCH (n) WHERE n.k < 10 UNWIND n.tags AS t UNWIND [t, t] AS u RETURN count(*) AS c"
        assertEquals(10L * 2, count(lists))
        val optional = "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) RETURN count(r) AS c"
        assertEquals(10L * 2, count(optional))
        val called = "MATCH (n) WHERE n.k < 10 CALL { WITH n MATCH (n)-[r]->(m) RETURN m } RETURN count(m) AS c"
        assertEquals(10L * 2, count(called))
    }

    @Test
    fun `a source after an OPTIONAL MATCH keeps its rows, its nulls and its list, and nothing is joined with CROSS JOIN`() {
        val counts = mapOf(
            // A MATCH after an OPTIONAL MATCH that finds nothing: the null row is kept, and a later source may be read before it.
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) WHERE m.k > 1000 MATCH (x) WHERE x.k = n.k RETURN count(*) AS c" to 10L,
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) MATCH (m)-[s]->(o) RETURN count(*) AS c" to 10L * 2 * 2,
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) WHERE m.k > 1000 MATCH (m)-[s]->(o) RETURN count(*) AS c" to 0L,
            // A json_each whose argument reads a node an OPTIONAL MATCH bound, null or not.
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) UNWIND m.tags AS t RETURN count(*) AS c" to 10L * 2,
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) WHERE m.k > 1000 UNWIND m.tags AS t RETURN count(*) AS c" to 0L,
            "MATCH (n) WHERE n.k < 10 OPTIONAL MATCH (n)-[r]->(m) WHERE m.k > 1000 UNWIND coalesce(m.tags, ['none']) AS t RETURN count(*) AS c" to 10L,
            "MATCH (n) WHERE n.k < 10 CALL { WITH n MATCH (n)-[r]->(m) RETURN m } RETURN count(m) AS c" to 10L * 2,
        )
        counts.forEach { (cypher, expected) ->
            assertEquals(expected, count(cypher), cypher)
            assertTrue("CROSS JOIN" !in compiled(cypher).sql, "$cypher is joined with CROSS JOIN:\n${compiled(cypher).sql}")
        }
    }

    @Test
    fun `Postgres and Oracle still join a step's sources with CROSS JOIN, which they reorder`() {
        val cypher = "MATCH (n)-[r]->(m) RETURN count(*) AS c"
        listOf(PostgresCypherCompiler(GRAPH), OracleCypherCompiler(GRAPH)).forEach { compiler ->
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue("CROSS JOIN" in sql && " ON TRUE" !in sql, "${compiler::class.simpleName}:\n$sql")
        }
    }

    companion object {
        private const val GRAPH = "joinorder"
        private const val NODES = 2000L
        private const val EDGES = NODES * 2

        private val pm by lazy { SqliteTestDatabase.graphManager(GRAPH) }

        /**
         * [NODES] nodes, numbered `k` from 0, each with two relationships out, to the nodes numbered `(7k + 1) % NODES` and
         * `(13k + 5) % NODES`: a graph of fixed shape, of no loop.
         */
        @JvmStatic
        @BeforeAll
        fun seed() {
            SqliteTestDatabase.drop(GRAPH)
            org.drivine.backend.sqlite.SqliteGraph(GRAPH).ensure(pm)
            SqliteTestDatabase.run(
                """
                INSERT INTO ${GRAPH}_node (labels, props)
                WITH RECURSIVE k(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM k WHERE i < ${NODES - 1})
                SELECT '["Item"]', json_object('k', i, 'tags', json_array(CASE WHEN i % 2 = 0 THEN 'even' ELSE 'odd' END)) FROM k
                """,
                """
                INSERT INTO ${GRAPH}_edge (src, dst, type, props)
                SELECT a.nid, b.nid, 'NEXT', '{}' FROM ${GRAPH}_node a, (SELECT 7 AS times, 1 AS plus UNION ALL SELECT 13, 5) s
                JOIN ${GRAPH}_node b ON b.nid = a.nid - (a.props ->> '$.k') + ((a.props ->> '$.k') * s.times + s.plus) % $NODES
                """,
            )
        }
    }
}
