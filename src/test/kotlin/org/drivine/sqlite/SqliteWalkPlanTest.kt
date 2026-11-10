package org.drivine.sqlite

import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A compiled walk's steps read the relationship table through its index on SQLite (drivine-cypher-spec#156): a step
 * tests its relationship's type as a range, so SQLite reads it through `<graph>_edge_src (src, type)`, or `_edge_dst`
 * walking backward. SQLite keys an automatic index only on `=` and `IS`; a step that tested its type with `=` was read
 * through an automatic index over every relationship of the type, which SQLite built again for each step. This guards
 * the module's reliance on that: should a later SQLite key an automatic index on a range too, a line here says
 * `AUTOMATIC`.
 */
class SqliteWalkPlanTest {

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

    /** The lengths of the paths [cypher] gives, in order. */
    private fun lengths(cypher: String): List<Long> {
        val compiled = compiled(cypher)
        return manager.query(QuerySpecification.withStatement(compiled.sql).bind(compiled.parameters).transform(Long::class.java)).sorted()
    }

    /** The lines of [plan] that read the relationship table, after asserting that none reads through an automatic index. */
    private fun edgeSteps(cypher: String, plan: List<String>): List<String> {
        val shown = plan.joinToString("\n")
        assertTrue(plan.none { "AUTOMATIC" in it }, "$cypher reads through an automatic index:\n$shown")
        return plan.filter { it.startsWith("edge: ") }.also { assertTrue(it.isNotEmpty(), "$cypher reads no relationship:\n$shown") }
    }

    @Test
    fun `a forward walk reads each step through the index on a relationship's source and type`() {
        val cypher = "MATCH p = (:CyWrite {i: 0})-[:NEXT*]->(b) RETURN length(p)"
        val steps = edgeSteps(cypher, plan(cypher))
        steps.forEach { assertTrue(it.endsWith("USING INDEX ${GRAPH}_edge_src (src=? AND type>? AND type<?)"), "$cypher:\n$it") }
        assertEquals((1L..HOPS).toList(), lengths(cypher))
    }

    @Test
    fun `a backward walk reads each step through the index on a relationship's target and type`() {
        val cypher = "MATCH p = (:CyWrite {i: $HOPS})<-[:NEXT*]-(b) RETURN length(p)"
        val steps = edgeSteps(cypher, plan(cypher))
        steps.forEach { assertTrue(it.endsWith("USING INDEX ${GRAPH}_edge_dst (dst=? AND type>? AND type<?)"), "$cypher:\n$it") }
        assertEquals((1L..HOPS).toList(), lengths(cypher))
    }

    @Test
    fun `an undirected walk reads each step through both indexes`() {
        // SQLite reads the OR of the two directions as one search on each index, keyed on the node alone.
        val cypher = "MATCH p = (:CyWrite {i: ${HOPS / 2}})-[:NEXT*1..3]-(b) RETURN length(p)"
        val steps = edgeSteps(cypher, plan(cypher))
        assertTrue(steps.any { "USING INDEX ${GRAPH}_edge_src (src=?" in it }, "$cypher:\n${steps.joinToString("\n")}")
        assertTrue(steps.any { "USING INDEX ${GRAPH}_edge_dst (dst=?" in it }, "$cypher:\n${steps.joinToString("\n")}")
        assertTrue(steps.all { "USING INDEX ${GRAPH}_edge_src " in it || "USING INDEX ${GRAPH}_edge_dst " in it }, "$cypher:\n${steps.joinToString("\n")}")
        assertEquals(listOf(1L, 1L, 2L, 2L, 3L, 3L), lengths(cypher), "a node mid-chain reaches two nodes at each length")
    }

    companion object {
        private const val GRAPH = "walkplan"

        /** The hops of the chain the walks walk. */
        private const val HOPS = 200L

        /** The relationships of the same type elsewhere, which an automatic index over every relationship of the type would read too. */
        private const val OTHERS = 1000L

        private val pm by lazy { SqliteTestDatabase.graphManager(GRAPH) }

        /** A chain of [HOPS] `NEXT` relationships between `CyWrite` nodes numbered `i` from 0, and one of [OTHERS] more between `Filler` nodes. */
        @JvmStatic
        @BeforeAll
        fun seed() {
            SqliteTestDatabase.drop(GRAPH)
            org.drivine.backend.sqlite.SqliteGraph(GRAPH).ensure(pm)
            SqliteTestDatabase.run(
                """
                INSERT INTO ${GRAPH}_node (labels, props)
                WITH RECURSIVE k(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM k WHERE i < $HOPS)
                SELECT '["CyWrite"]', json_object('i', i) FROM k
                """,
                """
                INSERT INTO ${GRAPH}_node (labels, props)
                WITH RECURSIVE k(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM k WHERE i < $OTHERS)
                SELECT '["Filler"]', json_object('i', i) FROM k
                """,
                // Each node to the next one made, of the same label.
                """
                INSERT INTO ${GRAPH}_edge (src, dst, type, props)
                SELECT a.nid, b.nid, 'NEXT', '{}' FROM ${GRAPH}_node a JOIN ${GRAPH}_node b ON b.nid = a.nid + 1 AND b.labels = a.labels
                """,
            )
        }
    }
}
