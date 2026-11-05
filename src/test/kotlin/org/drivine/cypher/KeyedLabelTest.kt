package org.drivine.cypher

import org.drivine.backend.oracle.OracleSql
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, a node found by its key, through an id compared with a value (#580) or as the later of two labelled ends of a
 * relationship (#581), tests its labels by their text ([OracleSql.holdsLabel]), as a node set equal to another does (#578).
 * The index over labels answers `JSON_EXISTS`, and Oracle (23.26) read that index, for the node's whole label, once for each id
 * or each edge, rather than the node by its primary key.
 */
class KeyedLabelTest {

    private val parameters = mapOf("id" to 5, "ids" to listOf(1, 2))

    private fun oracle(statement: String) = OracleCypherCompiler("g").compile(statement, parameters).sql

    private fun indexed(sql: String, alias: String) = "JSON_EXISTS($alias.labels, " in sql

    /** That [sql] tests [alias]'s [labels] by their text alone, and is planned without statistics feedback (#585). */
    private fun assertKeyed(sql: String, alias: String, labels: List<String>, where: String) {
        assertFalse(indexed(sql, alias), where)
        labels.forEach { assertTrue(OracleSql.holdsLabel("$alias.labels", it) in sql, where) }
        assertEquals(1, sql.split(OracleCypherCompiler.NO_FEEDBACK).size - 1, where)
    }

    @Test
    fun `on Oracle, a node whose id is compared with a value tests its labels by their text`() {
        for ((statement, alias) in listOf(
            "MATCH (n:Item) WHERE id(n) = \$id RETURN n.k" to "n1",
            "MATCH (n:Item) WHERE \$id = id(n) RETURN n.k" to "n1",
            "MATCH (n:Item) WHERE elementId(n) = \$id RETURN n.k" to "n1",
            "MATCH (n:Item) WHERE n.k > 0 AND id(n) = 7 RETURN n.k" to "n1",
            "MATCH (n:Item) WHERE id(n) IN \$ids RETURN n.k" to "n1",
            "MATCH (n:Item) WHERE id(n) IN [1, 2] RETURN n.k" to "n1",
            "MATCH (n:Item) RETURN COUNT { MATCH (m:Item) WHERE id(m) = \$id }" to "n2",
        )) {
            val sql = oracle(statement)
            assertKeyed(sql, alias, listOf("Item"), "$statement\n$sql")
        }
        val both = oracle("MATCH (n:Item:Other) WHERE id(n) = \$id RETURN n.k")
        assertKeyed(both, "n1", listOf("Item", "Other"), both)
        // An id from an UNWIND, or another node's property: the node it finds is read by its key, the other as before.
        val unwound = oracle("UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i RETURN count(*)")
        assertKeyed(unwound, "n2", listOf("Item"), unwound)
        val property = oracle("MATCH (x:Small) MATCH (n:Item) WHERE id(n) = x.s RETURN count(*)")
        assertTrue(indexed(property, "n1"), property)
        assertKeyed(property, "n2", listOf("Item"), property)
    }

    @Test
    fun `on Oracle, an id compared under NOT, beside an OR, or as a value keeps its node's labels' test through the index`() {
        for (statement in listOf(
            "MATCH (n:Item) WHERE NOT id(n) = \$id RETURN count(n)",
            "MATCH (n:Item) WHERE id(n) = \$id OR n.k = 7 RETURN n.k",
            "MATCH (n:Item) WHERE NOT id(n) IN \$ids RETURN count(n)",
            "MATCH (n:Item) RETURN count(CASE WHEN id(n) = \$id THEN 1 END)",
            "MATCH (n:Item) WHERE id(n) > \$id RETURN count(n)",
        )) {
            val sql = oracle(statement)
            assertTrue(indexed(sql, "n1"), "$statement\n$sql")
            assertFalse("INSTR(" in sql || "_optimizer_use_feedback" in sql || "CARDINALITY(" in sql, "$statement\n$sql")
        }
    }

    @Test
    fun `on Oracle, the ids an id is in, and the UNWIND an id is compared with, are planned as a few rows`() {
        // A JSON_TABLE over a value given is estimated at 8,168 rows: the nodes were read once and hashed, not each by its key.
        for (statement in listOf("MATCH (n:Item) WHERE id(n) IN \$ids RETURN n.k", "MATCH (n) WHERE id(n) IN [1, 2] RETURN n.k")) {
            val sql = oracle(statement)
            assertTrue(Regex("""IN \(SELECT /\*\+ CARDINALITY\(k\d+ ${OracleCypherCompiler.FEW_ROWS}\) \*/ """).containsMatchIn(sql), "$statement\n$sql")
        }
        for (statement in listOf(
            "UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i RETURN count(*)",
            "UNWIND \$ids AS i MATCH (n) WHERE i = id(n) RETURN count(*)",
            "UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i RETURN DISTINCT n.k",
        )) {
            val sql = oracle(statement)
            assertTrue(Regex("""SELECT /\*\+ CARDINALITY\(u1 ${OracleCypherCompiler.FEW_ROWS}\) \*/ """).containsMatchIn(sql), "$statement\n$sql")
        }
        // A statement that writes reads its rows in a step of its own, and a DELETE its ids in a subquery: each keeps the hint.
        for (statement in listOf(
            "UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i SET n.seen = true",
            "UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i DETACH DELETE n",
        )) {
            val sql = oracle(statement)
            assertTrue(Regex("""SELECT /\*\+ CARDINALITY\(u1 ${OracleCypherCompiler.FEW_ROWS}\) """).containsMatchIn(sql), "$statement\n$sql")
        }
        // A subquery of no RETURN reads its rows in a query of its own, which holds the hint.
        for (statement in listOf(
            "MATCH (a:Small) RETURN COUNT { UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i }",
            "MATCH (a:Small) WHERE EXISTS { UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i } RETURN a.s",
        )) {
            val sql = oracle(statement)
            assertTrue(Regex("""SELECT /\*\+ CARDINALITY\(u\d+ ${OracleCypherCompiler.FEW_ROWS}\) \*/ 1\n""").containsMatchIn(sql), "$statement\n$sql")
        }
        // An UNWIND beside an id compared with something else.
        val beside = oracle("UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = \$id RETURN count(*)")
        assertFalse("CARDINALITY(u1" in beside, beside)
    }

    @Test
    fun `on Oracle, the later of two labelled ends of a relationship tests its labels by their text`() {
        for ((statement, later) in listOf(
            "MATCH (x:Small)-[:R]->(a:Big) RETURN count(*)" to "n2",
            "MATCH (a:Big)<-[:R]-(x:Small) RETURN count(*)" to "n2",
            "MATCH (x:Small) MATCH (x)-[:R]->(a:Big) RETURN count(*)" to "n2",
        )) {
            val sql = oracle(statement)
            assertTrue(indexed(sql, "n1"), "$statement\n$sql")
            assertKeyed(sql, later, listOf(if (statement.indexOf("Big") > statement.indexOf("Small")) "Big" else "Small"), "$statement\n$sql")
        }
        // The later end alone given properties is where the pattern starts best: it keeps its test, and the earlier is keyed.
        for (statement in listOf("MATCH (a:Big)<-[:R]-(x:Small {s: 7}) RETURN count(*)", "MATCH (a:Big)-[:R]->(x:Small {s: 7}) RETURN count(*)")) {
            val sql = oracle(statement)
            assertTrue(indexed(sql, "n2"), "$statement\n$sql")
            assertKeyed(sql, "n1", listOf("Big"), "$statement\n$sql")
        }
        val bothGiven = oracle("MATCH (a:Big {j: 1})<-[:R]-(x:Small {s: 7}) RETURN count(*)")
        assertTrue(indexed(bothGiven, "n1"), bothGiven)
        assertKeyed(bothGiven, "n2", listOf("Small"), bothGiven)
        // Either direction, read from both halves of a UNION ALL: both ends.
        val either = oracle("MATCH (x:Small)-[:R]-(a:Big) RETURN count(*)")
        assertKeyed(either, "n1", listOf("Small"), either)
        assertKeyed(either, "n2", listOf("Big"), either)
        // Two hops: each end after the first.
        val two = oracle("MATCH (x:Small)-[:R]->(a:Big)<-[:R]-(y:Small) RETURN count(*)")
        assertTrue(indexed(two, "n1"), two)
        assertFalse(indexed(two, "n2") || indexed(two, "n3"), two)
        // One end labelled, a walk of many relationships, or a relationship from a node to itself: as before.
        for ((statement, labelled) in listOf(
            "MATCH (x:Small)-[:R]->(a) RETURN count(*)" to listOf("n1"),
            "MATCH (x)-[:R]->(a:Big) RETURN count(*)" to listOf("n2"),
            "MATCH (x:Small)-[:R*1..2]->(a:Big) RETURN count(*)" to listOf("n1", "n2"),
            "MATCH (x:Small)-[:R]->(x) RETURN count(*)" to listOf("n1"),
        )) {
            val sql = oracle(statement)
            labelled.forEach { assertTrue(indexed(sql, it), "$statement\n$sql") }
            assertFalse("LTRIM(RTRIM(JSON_SERIALIZE(" in sql || "_optimizer_use_feedback" in sql, "$statement\n$sql")
        }
    }

    @Test
    fun `a plan reads the index over labels once for each row only below a loop's later row source`() {
        val unwound = """
            |   3 |    NESTED LOOPS                        |                  |
            |   4 |     JSONTABLE EVALUATION               |                  |
            |*  5 |     TABLE ACCESS BY INDEX ROWID BATCHED| G_NODE           |
            |*  6 |      INDEX RANGE SCAN (MULTI VALUE)    | G_NODE_LABELS    |
        """.trimIndent()
        assertTrue(OracleRelationshipLabelPlanTest.multiValueUnderLoop(unwound))
        val driving = """
            |   3 |    NESTED LOOPS                        |                  |
            |   4 |     TABLE ACCESS BY INDEX ROWID BATCHED| G_NODE           |
            |*  5 |      INDEX RANGE SCAN (MULTI VALUE)    | G_NODE_LABELS    |
            |*  6 |     INDEX RANGE SCAN                   | G_EDGE_SRC       |
        """.trimIndent()
        assertFalse(OracleRelationshipLabelPlanTest.multiValueUnderLoop(driving))
        val hashed = """
            |*  3 |    HASH JOIN                           |                  |
            |   4 |     JSONTABLE EVALUATION               |                  |
            |*  5 |     TABLE ACCESS BY INDEX ROWID BATCHED| G_NODE           |
            |*  6 |      INDEX RANGE SCAN (MULTI VALUE)    | G_NODE_LABELS    |
        """.trimIndent()
        assertFalse(OracleRelationshipLabelPlanTest.multiValueUnderLoop(hashed))
    }

    @Test
    fun `on Postgres and SQLite, a node found by its id or a relationship tests its labels as any node's, with no hint`() {
        for (statement in listOf(
            "MATCH (n:Item) WHERE id(n) = \$id RETURN n.k",
            "MATCH (n:Item) WHERE id(n) IN \$ids RETURN n.k",
            "UNWIND \$ids AS i MATCH (n:Item) WHERE id(n) = i RETURN count(*)",
            "MATCH (x:Small)-[:R]->(a:Big) RETURN count(*)",
        )) {
            val postgres = PostgresCypherCompiler("g").compile(statement, parameters).sql
            assertFalse("/*+" in postgres || "INSTR(" in postgres, "$statement\n$postgres")
            val sqlite = SqliteCypherCompiler("g").compile(statement, parameters).sql
            assertFalse("/*+" in sqlite, "$statement\n$sqlite")
        }
    }
}

/**
 * Over S `:Small` nodes, each with 4 relationships to a `:Big` node of its own, Oracle (23.26) read `x`'s label through the index
 * over labels once for each edge, and filtered it by the edge's `src`: 1.2 s at S = 2,000, growing with edges × S (#581).
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleRelationshipLabelPlanTest {

    private val graphs = mapOf(SMALL to "rellabel_s", LARGE to "rellabel_l")
    private val managers = graphs.mapValues { (_, graph) -> lazy { OracleTestDatabase.graphManager(graph) } }

    private fun pm(size: Int) = managers.getValue(size).value

    private fun query(size: Int, statement: String): List<Any?> =
        OracleTestDatabase.fixtures(pm(size)).query(QuerySpecification.withStatement(CypherStatement(statement)))

    @BeforeAll
    fun seed() {
        for ((size, graph) in graphs) {
            OracleTestDatabase.clear(pm(size))
            query(size, "UNWIND range(1, $size) AS s CREATE (x:Small {s: s}) WITH x UNWIND range(1, $PER) AS j CREATE (x)-[:R]->(:Big {j: j})")
            OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${graph.uppercase()}_NODE'); DBMS_STATS.GATHER_TABLE_STATS(USER, '${graph.uppercase()}_EDGE'); END;")
        }
        // Ends of two labels, and ends of another label, which the keyed end's test must keep and leave out.
        query(SMALL, "CREATE (:Small:Two {s: -1})-[:R]->(:Big:Two {j: -1}), (:Small:Two {s: -2})-[:R]->(:Other {j: -2}), (:Other {s: -3})-[:R]->(:Big:Two {j: -3})")
    }

    @Test
    fun `an end read by its key, of two labels or of another label, is kept or left out as its labels say`() {
        for (statement in listOf(
            "MATCH (x:Small)-[:R]->(a:Big) WHERE a.j < 0 OR x.s < 0 RETURN [x.s, a.j]",
            "MATCH (a:Big)<-[:R]-(x:Small) WHERE a.j < 0 OR x.s < 0 RETURN [x.s, a.j]",
            "MATCH (x:Small)-[:R]-(a:Big) WHERE a.j < 0 OR x.s < 0 RETURN [x.s, a.j]",
            "MATCH (x:Small:Two)-[:R]->(a:Big:Two) RETURN [x.s, a.j]",
            "MATCH (a:Big)<-[:R]-(x:Small {s: -1}) RETURN [x.s, a.j]",
        )) {
            assertTrue("INSTR(" in OracleCypherCompiler(graphs.getValue(SMALL)).compile(statement).sql, statement)
            assertEquals(listOf(listOf(-1L, -1L)), query(SMALL, statement).map { row -> (row as List<*>).map { (it as Number).toLong() } }, statement)
        }
    }

    /** The median of five runs of [statement] over the graph of [size] `:Small` nodes, in ms, after one to warm it. */
    private fun timed(size: Int, statement: String): Double {
        query(size, statement)
        return (1..5).map { val start = System.nanoTime(); query(size, statement); (System.nanoTime() - start) / 1e6 }.sorted()[2]
    }

    @Test
    fun `both ends labelled, the end reached through the relationship is read by its key, not the index over labels for each edge, and the time grows with the edges alone`() {
        for (statement in listOf(
            "MATCH (x:Small)-[:R]->(a:Big) RETURN count(*)",
            "MATCH (a:Big)<-[:R]-(x:Small) RETURN count(*)",
            "MATCH (x:Small)-[:R]-(a:Big) RETURN count(*)",
        )) {
            val compiled = OracleCypherCompiler(graphs.getValue(LARGE)).compile(statement)
            val plan = OracleTestDatabase.plan(pm(LARGE), compiled.sql, compiled.parameters, "BASIC +PREDICATE")
            println("$statement\n$plan\n")
            assertEquals(listOf((LARGE * PER).toLong()), query(LARGE, statement).map { (it as Number).toLong() }, statement)
            // Either direction is read from both halves of a UNION ALL first, and each end joined to them by a hash of its key.
            if ("]-(" !in statement) assertTrue("INDEX UNIQUE SCAN" in plan, "$statement reads no end by its key:\n$plan")
            assertFalse(multiValueUnderLoop(plan), "$statement reads the index over labels for each edge:\n$plan")
            val (small, large) = timed(SMALL, statement) to timed(LARGE, statement)
            println("$statement: ${"%.1f".format(small)} ms at $SMALL, ${"%.1f".format(large)} ms at $LARGE")
            // Four times the edges: about four times the time, where it took sixteen.
            assertTrue(large < small * 8 + 5, "$statement: ${"%.1f".format(small)} ms at $SMALL, ${"%.1f".format(large)} ms at $LARGE")
        }
    }

    companion object {
        const val SMALL = 500
        const val LARGE = 2000
        const val PER = 4

        /**
         * Whether [plan], as `DBMS_XPLAN` writes it, reads the index over labels below a nested loop's second row source or a
         * later one: once for each row of the first.
         */
        fun multiValueUnderLoop(plan: String): Boolean {
            val rows = plan.lines().mapNotNull { line -> Regex("""^\|\*?\s*\d+ \| ( *)(\S.*?)\s*\|""").find(line)?.let { it.groupValues[1].length to it.groupValues[2] } }
            for (at in rows.indices.filter { "MULTI VALUE" in rows[it].second }) {
                var child = at
                for (up in at - 1 downTo 0) {
                    if (rows[up].first >= rows[child].first) continue
                    // rows[up] holds rows[child]: a loop with a row source before rows[child] reads it for each of that one's rows.
                    if (rows[up].second.startsWith("NESTED LOOPS") && (up + 1 until child).any { rows[it].first == rows[child].first }) return true
                    child = up
                }
            }
            return false
        }
    }
}
