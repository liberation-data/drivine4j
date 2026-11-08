package org.drivine.cypher

import org.drivine.backend.oracle.OracleSql
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.test.Nightly
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A node equal to a node is their keys compared, through the store's own comparison of two keys. On Oracle that is `+ 0`
 * on one side, both ways round, so either is read by its key: compared column to column, Oracle 23 dropped the later
 * node's join and tested its label in a form that misses a node with more than one label, so `MATCH (p)-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q` gave no rows (#576).
 */
class NodeEqualityTest {

    private val later = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name"
    private val earlier = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name"

    @Test
    fun `on Oracle, a node equal to a node compares the keys with + 0 on one side, both ways round`() {
        val sql = OracleCypherCompiler("g").compile(later).sql
        assertTrue("(n4.nid = n2.nid + 0 AND n2.nid = n4.nid + 0)" in sql, sql)
        val other = OracleCypherCompiler("g").compile(earlier).sql
        assertTrue("(n2.nid = n4.nid + 0 AND n4.nid = n2.nid + 0)" in other, other)
    }

    @Test
    fun `on Oracle, a node compared to a node by NOT =, IN, a list, CASE or in a subquery compares the keys with + 0 too`() {
        val q = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q)"
        for (statement in listOf(
            "$q MATCH (c:Person) WHERE c <> q RETURN c.name",
            "$q MATCH (c:Person) WHERE c IN [q] RETURN c.name",
            "$q MATCH (c:Person) WHERE [c] = [q] RETURN c.name",
            "$q MATCH (c:Person) WHERE CASE c WHEN q THEN true ELSE false END RETURN c.name",
            "$q OPTIONAL MATCH (c:Person) WHERE c = q RETURN c.name",
            "$q WHERE EXISTS { MATCH (c:Person) WHERE c = q } RETURN q.name",
        )) {
            val sql = OracleCypherCompiler("g").compile(statement).sql
            assertTrue(Regex("""n\d+\.nid = n\d+\.nid \+ 0""").containsMatchIn(sql), "$statement\n$sql")
            assertFalse(Regex("""n\d+\.nid = n\d+\.nid(?! \+ 0)""").containsMatchIn(sql), "$statement\n$sql")
        }
    }

    @Test
    fun `on Postgres and SQLite, a node equal to a node compares the keys as they are`() {
        for (sql in listOf(PostgresCypherCompiler("g").compile(later).sql, SqliteCypherCompiler("g").compile(later).sql)) {
            assertTrue("n4.nid = n2.nid" in sql && "+ 0" !in sql, sql)
        }
    }

    /** The two nodes [sql] compares by their keys, both ways round, the earlier first, as their aliases are numbered. */
    private fun compared(sql: String): Pair<String, String> {
        val m = assertNotNull(Regex("""\((n(\d+))\.nid = (n(\d+))\.nid \+ 0 AND (n\d+)\.nid = (n\d+)\.nid \+ 0\)""").find(sql), "No node equal to a node in:\n$sql")
        return listOf(m.groupValues[1] to m.groupValues[2].toInt(), m.groupValues[3] to m.groupValues[4].toInt()).sortedBy { it.second }.let { it[0].first to it[1].first }
    }

    /** That on Oracle [statement] tests the labels of the later node it compares, [labels], by their text, and those of the earlier through the index. */
    private fun assertKeyedLabels(statement: String, labels: List<String> = listOf("Big"), earlierScanned: Boolean = true) {
        val sql = OracleCypherCompiler("g").compile(statement, mapOf("id" to 5)).sql
        val (a, b) = compared(sql)
        val where = "$statement\n$sql"
        assertTrue("($a.nid = $b.nid + 0 AND $b.nid = $a.nid + 0)" in sql || "($b.nid = $a.nid + 0 AND $a.nid = $b.nid + 0)" in sql, where)
        if (earlierScanned) assertTrue("JSON_EXISTS($a.labels, " in sql, where)
        assertFalse("JSON_EXISTS($b.labels, " in sql, where)
        labels.forEach { assertTrue(OracleSql.holdsLabel("$b.labels", it) in sql, where) }
        assertPlannedWithoutFeedback(sql, where)
    }

    /** That [sql] is planned without statistics feedback: once, in the hints of its outermost query (#585). */
    private fun assertPlannedWithoutFeedback(sql: String, where: String = sql) {
        assertEquals(1, sql.split(OracleCypherCompiler.NO_FEEDBACK).size - 1, where)
        assertTrue(Regex("""^SELECT /\*\+ [^*]*${Regex.escape(OracleCypherCompiler.NO_FEEDBACK)}[^*]* \*/""").containsMatchIn(sql), where)
    }

    /** That on Oracle [statement], which matches a label on each of [labelled] nodes, tests each through the index over labels, as before. */
    private fun assertScannedLabels(statement: String, labelled: Int = 2) {
        val sql = OracleCypherCompiler("g").compile(statement).sql
        assertEquals(labelled, Regex("""JSON_EXISTS\(\w+\.labels, '\$\[\*]\?\(@ == "Big"\)'\)""").findAll(sql).count(), "$statement\n$sql")
        assertFalse("INSTR(" in sql, "$statement\n$sql")
        assertFalse("_optimizer_use_feedback" in sql, "$statement\n$sql")
    }

    @Test
    fun `on Oracle, a node whose key is set equal to an earlier node's tests its labels so the index can't answer them, and is read by its key`() {
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE b = a RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE id(b) = id(a) RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE id(a) = id(b) RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big), (b:Big) WHERE a = b RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) WHERE a.k = 1 MATCH (b:Big) WHERE a = b RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE a = b AND b.k > 0 RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) MATCH (b:Big) WHERE b.k > 0 AND (a = b AND b.k < 9) RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) OPTIONAL MATCH (b:Big) WHERE a = b RETURN count(b)")
        assertKeyedLabels("MATCH (a:Big) WHERE EXISTS { MATCH (b:Big) WHERE a = b } RETURN count(*)")
        assertKeyedLabels("MATCH (a:Big) RETURN COUNT { MATCH (b:Big) WHERE b = a }")
        assertKeyedLabels("MATCH (a:Big) RETURN [(x)-[:R]->(b:Big) WHERE b = a | b.k]")
        assertKeyedLabels("MATCH (a:Big:Other) MATCH (b:Big:Other) WHERE a = b RETURN count(*)", listOf("Big", "Other"))
        // The earlier node found by its properties, a relationship it ends, or its id, with no label.
        assertKeyedLabels("MATCH (a {k: 1}) MATCH (b:Big) WHERE a = b RETURN b", earlierScanned = false)
        assertKeyedLabels("MATCH (x)-[:R]->(a) MATCH (b:Big) WHERE a = b RETURN count(*)", earlierScanned = false)
        assertKeyedLabels("MATCH (a) WHERE id(a) = \$id MATCH (b:Big) WHERE a = b RETURN b", earlierScanned = false)
        assertKeyedLabels("MATCH (a) WHERE \$id = id(a) MATCH (b:Big) WHERE a = b RETURN b", earlierScanned = false)
        assertKeyedLabels("MATCH (a), (b:Big) WHERE id(a) IN [1, 2] AND id(b) = id(a) RETURN b", earlierScanned = false)
        // The node found by its properties bound after the node with the label: that node, the earlier, is read by its key.
        val later = OracleCypherCompiler("g").compile("MATCH (b:Big) MATCH (a {k: 1}) WHERE a = b RETURN b").sql
        assertTrue(OracleSql.holdsLabel("n1.labels", "Big") in later && "JSON_EXISTS(n1.labels" !in later, later)
        // Three nodes set equal in a chain, either order: each after the first is read by its key, the first through the index.
        for (test in listOf("a = b AND b = c", "c = b AND b = a")) {
            val chain = OracleCypherCompiler("g").compile("MATCH (a:Big) MATCH (b:Big) MATCH (c:Big) WHERE $test RETURN count(*)").sql
            assertTrue("JSON_EXISTS(n1.labels" in chain && "JSON_EXISTS(n2.labels" !in chain && "JSON_EXISTS(n3.labels" !in chain, "$test\n$chain")
            listOf("n2", "n3").forEach { assertTrue(OracleSql.holdsLabel("$it.labels", "Big") in chain, "$test\n$chain") }
        }
    }

    @Test
    fun `on Oracle, a node equal to a node under NOT, beside an OR, as a value, or to a node found by nothing else, keeps its labels' test through the index`() {
        assertScannedLabels("MATCH (a:Big) MATCH (b:Big) WHERE NOT a = b RETURN count(*)")
        assertScannedLabels("MATCH (a:Big) MATCH (b:Big) WHERE a = b OR b.k = 1 RETURN count(*)")
        assertScannedLabels("MATCH (a:Big) MATCH (b:Big) WHERE (a = b) = true RETURN count(*)")
        assertScannedLabels("MATCH (a:Big) MATCH (b:Big) WHERE CASE WHEN a = b THEN true END RETURN count(*)")
        assertScannedLabels("MATCH (a:Big), (b:Big) RETURN count(CASE WHEN a = b THEN 1 END)")
        // Found by nothing but the key: the index over labels is the way in, to b, and the earlier node is read by its key.
        assertScannedLabels("MATCH (a) MATCH (b:Big) WHERE a = b RETURN count(*)", 1)
        // An empty map of properties finds a node by nothing, as no map does (#583).
        assertScannedLabels("MATCH (a {}) MATCH (b:Big) WHERE a = b RETURN count(*)", 1)
        assertScannedLabels("MATCH (a) WHERE EXISTS { MATCH (b:Big) WHERE a = b } RETURN count(*)", 1)
        // Passed through a WITH, the earlier node is a column of that query, whose finding the condition doesn't see.
        assertScannedLabels("MATCH (a:Big) WITH a MATCH (b:Big) WHERE a = b RETURN count(*)")
        // An aggregate's argument compiled first only to see whether it binds a value: what it finds by a label, in the names it
        // takes, doesn't make the node an argument compiled before it takes under the same name found by more than its key. Each
        // EXISTS is written twice, for its count to be of the values that aren't null.
        assertScannedLabels("MATCH (z) RETURN count(EXISTS { MATCH (u) MATCH (v:Big) WHERE u = v }) AS c, count(EXISTS { MATCH (p:Big) }) AS d", 4)
        // A node equal to itself is the only node there is.
        assertScannedLabels("MATCH (a:Big) WHERE a = a RETURN count(*)", 1)
    }

    @Test
    fun `on Postgres and SQLite, a node set equal to a node tests its labels as any node's`() {
        val statement = "MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)"
        val postgres = PostgresCypherCompiler("g").compile(statement).sql
        assertTrue("n1.labels @> '[\"Big\"]'" in postgres && "n2.labels @> '[\"Big\"]'" in postgres, postgres)
        val sqlite = SqliteCypherCompiler("g").compile(statement).sql
        assertTrue(SqliteSql.holdsLabel("n1.labels", "Big") in sqlite && SqliteSql.holdsLabel("n2.labels", "Big") in sqlite, sqlite)
        listOf(postgres, sqlite).forEach { assertFalse("_optimizer_use_feedback" in it, it) }
    }

    @Test
    fun `on Oracle, only a query that reads a node by its key this way is planned without statistics feedback`() {
        // With a test of the labels as keyedLabels writes it, beside the JSON table transformation's hint, and on its own.
        val both = OracleCypherCompiler("g").compile("MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)").sql
        assertTrue(both.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM ${OracleCypherCompiler.NO_FEEDBACK} */ "), both)
        // Read by its key in one query of a UNION, or in an EXISTS subquery: the statement's outermost query holds the hint, once.
        for (statement in listOf(
            "MATCH (n:Small) RETURN count(*) AS c UNION ALL MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*) AS c",
            "MATCH (n:Small) RETURN count(*) AS c UNION MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*) AS c",
            "MATCH (a:Big) WHERE EXISTS { MATCH (b:Big) WHERE a = b } RETURN count(*)",
        )) {
            val sql = OracleCypherCompiler("g").compile(statement).sql
            assertTrue("INSTR(" in sql, "$statement\n$sql")
            assertPlannedWithoutFeedback(sql, "$statement\n$sql")
        }
        // A label in the labels' text form that keyedLabels didn't write, as a node's second label is tested.
        listOf(
            "MATCH (n:Big) RETURN count(*)",
            "MATCH (n:Big:Other) RETURN count(*)",
            "MATCH (a:Big) MATCH (b:Big) RETURN count(*)",
            "MATCH (x:Small)-[:R]->(a) RETURN count(*)",
            "MATCH (n) WHERE id(n) = \$id RETURN n",
            "UNWIND \$ids AS i MATCH (n) WHERE id(n) = i RETURN count(*)",
        ).forEach { statement ->
            val sql = OracleCypherCompiler("g").compile(statement, mapOf("id" to 5, "ids" to listOf(1, 2))).sql
            assertFalse("_optimizer_use_feedback" in sql, "$statement\n$sql")
        }
        // A statement that writes is a procedure: the query of its step that reads b by its key holds the hint, once.
        val write = OracleCypherCompiler("g").compile("MATCH (a:Big) MATCH (b:Big) WHERE a = b SET b.seen = true").sql
        assertEquals(1, write.split(OracleCypherCompiler.NO_FEEDBACK).size - 1, write)
        assertTrue(Regex("""SELECT /\*\+ [^*]*${Regex.escape(OracleCypherCompiler.NO_FEEDBACK)}""").containsMatchIn(write), write)
    }

    @Test
    fun `a relationship equal to a relationship still compares the keys as they are, on Oracle too`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (a)-[r]->(b) MATCH (a)-[s]->(b) WHERE s = r RETURN count(*)").sql
        assertTrue(Regex("""e\d+\.eid = e\d+\.eid\b(?! \+ 0)""").containsMatchIn(sql), sql)
    }
}

/**
 * On Oracle, a node with a label equal to a node matched before it is still joined, and so found when it has more than one
 * label (#576). The plan's outline names each join Oracle eliminates.
 */
@Nightly
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleNodeEqualityPlanTest {

    private val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    private val cypher by lazy { OracleTestDatabase.fixtures(pm) }

    @BeforeAll
    fun seed() {
        OracleTestDatabase.clear(pm)
        // Two labels on each node: Oracle's filter of the labels, in the multivalue index's form, missed such a node.
        query("CREATE (k:Person:CyEq {name: 'Keanu'}), (c:Person:CyEq {name: 'Carrie'}), (l:Person:CyEq {name: 'Laurence'}), (k)-[:KNOWS]->(c), (l)-[:KNOWS]->(k)")
    }

    private fun query(statement: String): List<Any?> = cypher.query(QuerySpecification.withStatement(CypherStatement(statement)))

    /** The plan Oracle makes for [compiled] with its outline, its values written in as literals, explained and read in one block. */
    private fun plan(compiled: CompiledCypher) = OracleTestDatabase.plan(pm, compiled.sql, compiled.parameters, "BASIC +OUTLINE")

    /** That [statement] gives [expected], and that Oracle keeps the join of `c`, the later of the two nodes compared. */
    private fun assertJoined(statement: String, expected: List<Any?>) {
        val compiled = OracleCypherCompiler(GRAPH).compile(statement)
        val compared = assertNotNull(Regex("""\b(n(\d+))\.nid = (n(\d+))\.nid \+ 0""").find(compiled.sql), "No node equal to a node in:\n${compiled.sql}")
        val c = listOf(compared.groupValues[1] to compared.groupValues[2], compared.groupValues[3] to compared.groupValues[4])
            .maxBy { it.second.toInt() }.first.uppercase()
        val plan = plan(compiled)
        println("$statement\n$plan\n")
        assertTrue("Outline Data" in plan, plan)
        assertFalse(Regex("""ELIMINATE_JOIN\([^)]*"$c"""").containsMatchIn(plan), "$statement eliminates c's join:\n$plan")
        assertEquals(expected, query(statement), statement)
    }

    @Test
    fun `a node with two labels, equal to a node matched before it, is still joined and found`() {
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})<-[:KNOWS]-(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Laurence"))
        assertJoined("MATCH (p:Person {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q), (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE id(c) = id(q) RETURN c.name", listOf("Carrie"))
    }

    /** A statement that writes is a procedure, and Oracle takes the step that reads c by its key with its hint (#585). */
    @Test
    fun `a node read by its key in a statement that writes is found, and written`() {
        val statement = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q SET c.seen = true RETURN c.name"
        val sql = OracleCypherCompiler(GRAPH).compile(statement).sql
        assertTrue(OracleCypherCompiler.NO_FEEDBACK in sql, sql)
        try {
            assertEquals(listOf("Carrie"), query(statement), statement)
            assertEquals(listOf("Carrie"), query("MATCH (c:Person) WHERE c.seen = true RETURN c.name"))
        } finally {
            query("MATCH (c:Person) REMOVE c.seen")
        }
    }

    companion object {
        const val GRAPH = "nodeeq"
    }
}

/**
 * On Oracle, a node with a label whose key is set equal to a node found before it is read by its primary key, once for each
 * row of that node (#578). With its label tested through the index over labels, Oracle read that index again for each row,
 * and filtered it on the key: `MATCH (a:Big) MATCH (b:Big) WHERE a = b` took 1.5 s over 4,000 nodes, growing with their
 * square. The plan's outline names each join Oracle eliminates, which #576 keeps it from doing to the later node.
 */
@Nightly
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleKeyedLabelPlanTest {

    private val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    private val cypher by lazy { OracleTestDatabase.fixtures(pm) }

    /** A node's `k`, for its `i`: on half the nodes, 0 to 4. */
    private fun k(i: Int): Int? = if (i % 2 == 0) i % 5 else null

    @BeforeAll
    fun seed() {
        OracleTestDatabase.clear(pm)
        query("UNWIND range(1, $NODES) AS i CREATE (:Big:Other {i: i, k: CASE WHEN i % 2 = 0 THEN i % 5 END})")
        query("UNWIND range(1, $SMALL) AS i CREATE (:Small {i: i})")
        // A relationship into each Big node of odd i, from a Small node.
        query("MATCH (b:Big) WHERE b.i % 2 = 1 MATCH (s:Small) WHERE s.i = b.i % $SMALL + 1 CREATE (s)-[:R]->(b)")
        OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE'); DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_EDGE'); END;")
    }

    private fun query(statement: String): List<Any?> = cypher.query(QuerySpecification.withStatement(CypherStatement(statement)))

    /** That [statement] counts [expected], and that Oracle reads `b`, the later of the two nodes compared, by its primary key, and keeps its join. */
    private fun assertReadByKey(statement: String, expected: Long) {
        val compiled = OracleCypherCompiler(GRAPH).compile(statement)
        val b = laterCompared(compiled.sql)
        val plan = OracleTestDatabase.plan(pm, compiled.sql, compiled.parameters, "BASIC +PREDICATE +OUTLINE")
        println("$statement\n$plan\n")
        assertTrue("INDEX UNIQUE SCAN" in plan && Regex("""access\("$b"\."NID"=""").containsMatchIn(plan), "$statement does not read b by its key:\n$plan")
        assertTrue("Outline Data" in plan, plan)
        assertFalse(Regex("""ELIMINATE_JOIN\([^)]*"$b"""").containsMatchIn(plan), "$statement eliminates b's join:\n$plan")
        assertEquals(listOf(expected), query(statement).map { (it as Number).toLong() }, statement)
    }

    @Test
    fun `a node with a label equal to a node found by its label, properties or a relationship is read by its key`() {
        val all = NODES.toLong()
        assertReadByKey("MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)", all)
        assertReadByKey("MATCH (a:Big) MATCH (b:Big) WHERE b = a RETURN count(*)", all)
        assertReadByKey("MATCH (a:Big), (b:Big) WHERE a = b RETURN count(*)", all)
        assertReadByKey("MATCH (a:Big) WHERE a.k = 1 MATCH (b:Big) WHERE a = b RETURN count(*)", (1..NODES).count { k(it) == 1 }.toLong())
        assertReadByKey("MATCH (a:Big) MATCH (b:Big) WHERE a = b AND b.k > 0 RETURN count(*)", (1..NODES).count { (k(it) ?: 0) > 0 }.toLong())
        assertReadByKey("MATCH (a:Big:Other) MATCH (b:Big:Other) WHERE a = b RETURN count(*)", all)
        assertReadByKey("MATCH (x:Small)-[:R]->(a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)", all / 2)
        assertReadByKey("MATCH (a:Big) MATCH (b:Big) WHERE id(b) = id(a) RETURN count(*)", all)
        assertReadByKey("MATCH (a:Big) WHERE EXISTS { MATCH (b:Big) WHERE a = b } RETURN count(*)", all)
    }

    /**
     * Statistics feedback planned the statement again from its first run's counts: the index over labels is estimated at one
     * row, and the run found 3,000. The second and third runs of a new cursor read every node for `b` and the index once for
     * each, as before #578, and the fourth went back to reading b by its key (#585). So each run of one cursor, on one
     * session, is checked by the plan of the child cursor that ran it.
     */
    @Test
    fun `a node read by its key is read so on every run of a new cursor, not planned again from what the first run found`() {
        val connection = OracleTestDatabase.connections(pm).connect()
        try {
            val sid = connection.query(QuerySpecification.withStatement("SELECT SYS_CONTEXT('USERENV', 'SID') FROM DUAL").transform(String::class.java)).single()
            val all = NODES.toLong()
            for ((statement, expected) in listOf(
                "MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)" to all,
                "MATCH (a:Big) MATCH (b:Big) WHERE id(b) = id(a) RETURN count(*)" to all,
                "MATCH (a:Big:Other) MATCH (b:Big:Other) WHERE a = b RETURN count(*)" to all,
                "MATCH (x:Small)-[:R]->(a:Big) MATCH (b:Big) WHERE a = b RETURN count(*)" to all / 2,
            )) {
                val b = laterCompared(OracleCypherCompiler(GRAPH).compile(statement).sql)
                // A new cursor: statistics gathered again invalidate the one an earlier test, or statement, ran.
                OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE', no_invalidate => FALSE); END;")
                for (run in 1..RUNS) {
                    val started = System.nanoTime()
                    val count = connection.query(QuerySpecification.withStatement(CypherStatement(statement))).map { (it as Number).toLong() }
                    val ms = (System.nanoTime() - started) / 1_000_000.0
                    val plan = OracleTestDatabase.lastPlan(sid, "BASIC +PREDICATE +NOTE")
                    println("$statement, run $run: ${"%.1f".format(ms)} ms\n$plan\n")
                    val where = "$statement, run $run of one cursor:\n$plan"
                    assertTrue("INDEX UNIQUE SCAN" in plan && Regex("""access\("$b"\."NID"=""").containsMatchIn(plan), "Does not read b by its key: $where")
                    assertFalse("statistics feedback used" in plan, "Planned again from statistics feedback: $where")
                    assertEquals(listOf(expected), count, statement)
                }
            }
        } finally {
            connection.release()
        }
    }

    /** The alias of the later of the two nodes [sql] compares by their keys, as Oracle names it in a plan. */
    private fun laterCompared(sql: String): String {
        val compared = assertNotNull(Regex("""\b(n(\d+))\.nid = (n(\d+))\.nid \+ 0""").find(sql), "No node equal to a node in:\n$sql")
        return listOf(compared.groupValues[1] to compared.groupValues[2], compared.groupValues[3] to compared.groupValues[4])
            .maxBy { it.second.toInt() }.first.uppercase()
    }

    companion object {
        const val GRAPH = "keyedlabel"
        /** Runs of one cursor: statistics feedback planned the second and third again, and the fourth as the first. */
        const val RUNS = 5
        const val NODES = 3000
        const val SMALL = 750
    }
}
