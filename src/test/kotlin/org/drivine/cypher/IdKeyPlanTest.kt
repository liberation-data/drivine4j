package org.drivine.cypher

import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.backend.oracle.OracleSql
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An id compared by `=` or `IN`, where the row must pass the comparison, is read by the node's or the relationship's primary key
 * (drivine4j#575): the id is JSON, compared as JSON, so each such read was a scan of the table, and an `UNWIND` of ids a scan for
 * each. The rows are those the comparison alone keeps, under `NOT` and `OR` too, where no test of the key stands.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IdKeyPlanContract {

    /** The manager of the graph's tables, which reads SQL. */
    abstract val pm: NonTransactionalPersistenceManager

    /** [pm], which reads Cypher. */
    abstract val cypher: PersistenceManager

    abstract fun compiled(cypher: String, parameters: Map<String, Any?>): CompiledCypher

    /** The plan the store makes for [compiled], as it writes it. */
    abstract fun plan(compiled: CompiledCypher): String

    /** Whether [plan] reads a row of the table [table], `node` or `edge`, by its primary key. */
    abstract fun readsByKey(plan: String, table: String): Boolean

    /** Whether [plan] reads every row of the table [table]. */
    abstract fun scans(plan: String, table: String): Boolean

    /**
     * The label the statements match their nodes by, which every node carries. Oracle reads a label from the index over
     * labels before any key, `nid = 6` written included, so its statements match no label, and show the key read.
     */
    open val item = ":Item"

    /** Gathers the store's statistics of the graph's tables, where it plans by them. */
    open fun analyze() {}

    /** Each node's id, by its `k`. */
    private lateinit var ids: Map<Long, Long>

    /** Each relationship's id, by the `k` of the node it leaves. */
    private lateinit var edgeIds: Map<Long, Long>

    @BeforeAll
    fun seed() {
        org.drivine.test.SqlCypherFixtures.clear(pm)
        query("UNWIND range(0, ${NODES - 1}) AS k CREATE (:Item {k: k})")
        ids = query("MATCH (n:Item) RETURN [n.k, id(n)]").associate { row -> (row as List<*>).let { (it[0] as Number).toLong() to (it[1] as Number).toLong() } }
        // A relationship from each of the first nodes to the next, each found by its id.
        val pairs = (0L until EDGES).map { k -> listOf(k, id(k), id(k + 1)) }
        query("UNWIND \$pairs AS p MATCH (a), (b) WHERE id(a) = p[1] AND id(b) = p[2] CREATE (a)-[:NEXT {k: p[0]}]->(b)", mapOf("pairs" to pairs))
        edgeIds = query("MATCH (a)-[r:NEXT]->() RETURN [a.k, id(r)]").associate { row -> (row as List<*>).let { (it[0] as Number).toLong() to (it[1] as Number).toLong() } }
        analyze()
    }

    private fun query(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        cypher.query(QuerySpecification.withStatement(CypherStatement(statement)).bind(parameters))

    private fun id(k: Long) = ids.getValue(k)

    /** That [statement] gives [expected], and that the store's plan reads [table] by its key and scans no row of it. */
    private fun assertKeyed(statement: String, parameters: Map<String, Any?>, expected: List<Any?>, table: String = "node") {
        val plan = plan(compiled(statement, parameters))
        println("$statement\n$plan\n")
        assertEquals(expected, query(statement, parameters), statement)
        assertTrue(readsByKey(plan, table) && !scans(plan, table), "$statement does not read the $table by its key:\n$plan")
    }

    /** That [statement] gives [expected]: where no test of the key may stand, the store reads every node, as before. */
    private fun assertUnkeyed(statement: String, parameters: Map<String, Any?>, expected: List<Any?>) {
        val plan = plan(compiled(statement, parameters))
        println("$statement\n$plan\n")
        assertEquals(expected, query(statement, parameters), statement)
        assertTrue(scans(plan, "node"), "$statement reads a node by its key, where the comparison may be false and keep the row:\n$plan")
    }

    @Test
    fun `an id equal to a parameter, either way round, reads the node by its key`() {
        assertKeyed("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to id(5)), listOf(5L))
        assertKeyed("MATCH (n$item) WHERE \$id = id(n) RETURN n.k", mapOf("id" to id(5)), listOf(5L))
        assertKeyed("MATCH (n) WHERE elementId(n) = \$id RETURN n.k", mapOf("id" to id(5)), listOf(5L))
        assertKeyed("MATCH (n$item) WHERE n.k >= 0 AND id(n) = \$id AND n.k < 100 RETURN n.k", mapOf("id" to id(5)), listOf(5L))
        // 5.0 is equal to 5, as in Cypher.
        assertKeyed("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to id(5).toDouble()), listOf(5L))
        // Written in the statement, with an exponent too.
        assertKeyed("MATCH (n$item) WHERE id(n) = ${id(5)} RETURN n.k", emptyMap(), listOf(5L))
        assertKeyed("MATCH (n$item) WHERE id(n) = ${id(5)}e0 RETURN n.k", emptyMap(), listOf(5L))
        assertKeyed("MATCH (n$item) WHERE id(n) IN [${id(3)}, ${id(4)}e0] RETURN n.k ORDER BY n.k", emptyMap(), listOf(3L, 4L))
    }

    @Test
    fun `an id that is no whole number, a text or null is equal to no node`() {
        assertKeyed("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to id(5) + 0.5), emptyList())
        assertKeyed("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to id(5).toString()), emptyList())
        assertKeyed("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to listOf(id(5))), emptyList())
        assertEquals(emptyList(), query("MATCH (n$item) WHERE id(n) = \$id RETURN n.k", mapOf("id" to null)))
    }

    @Test
    fun `each id an UNWIND gives reads its node by its key`() {
        val given = listOf(id(5).toDouble(), "x", null, id(6), listOf(id(7)), mapOf("id" to id(8)), id(9) + 0.5)
        assertKeyed("UNWIND \$ids AS i MATCH (n$item) WHERE id(n) = i RETURN n.k ORDER BY n.k", mapOf("ids" to given), listOf(5L, 6L))
        assertKeyed("UNWIND \$ids AS i MATCH (n$item) WHERE i = id(n) RETURN n.k ORDER BY n.k", mapOf("ids" to listOf(id(1), id(2))), listOf(1L, 2L))
    }

    @Test
    fun `an id in a list given or written reads each node by its key`() {
        val given = listOf(id(5).toDouble(), "x", null, id(6), listOf(id(7)), mapOf("id" to id(8)), id(9) + 0.5)
        assertKeyed("MATCH (n$item) WHERE id(n) IN \$ids RETURN n.k ORDER BY n.k", mapOf("ids" to given), listOf(5L, 6L))
        assertKeyed("MATCH (n$item) WHERE id(n) IN \$ids RETURN n.k ORDER BY n.k", mapOf("ids" to listOf(id(3), id(4))), listOf(3L, 4L))
        assertKeyed("MATCH (n$item) WHERE id(n) IN [\$a, \$b] RETURN n.k ORDER BY n.k", mapOf("a" to id(3), "b" to id(4).toDouble()), listOf(3L, 4L))
        // A null among the items written: compared item by item, as Cypher compares them.
        assertKeyed("MATCH (n$item) WHERE id(n) IN [\$a, null] RETURN n.k", mapOf("a" to id(3)), listOf(3L))
        assertKeyed("MATCH (n$item) WHERE id(n) IN \$ids RETURN n.k", mapOf("ids" to emptyList<Long>()), emptyList())
    }

    @Test
    fun `a relationship's id, and a walk from a node's, read by their keys`() {
        assertKeyed("MATCH ()-[r]->() WHERE id(r) = \$id RETURN r.k", mapOf("id" to edgeIds.getValue(3)), listOf(3L), table = "edge")
        assertKeyed("MATCH ()-[r]->() WHERE elementId(r) IN \$ids RETURN r.k ORDER BY r.k", mapOf("ids" to listOf(edgeIds.getValue(3), edgeIds.getValue(4))), listOf(3L, 4L), table = "edge")
        assertKeyed("MATCH (a$item)-[r]->(b) WHERE id(a) = \$id RETURN b.k", mapOf("id" to id(5)), listOf(6L))
        assertKeyed("MATCH (a$item) WHERE id(a) = \$id MATCH (b) WHERE id(b) = id(a) RETURN b.k", mapOf("id" to id(5)), listOf(5L))
    }

    @Test
    fun `an id compared across a relationship of either direction, read both ways round, reads its key`() {
        // Postgres and Oracle read such a relationship from both halves of a UNION ALL (#570): its eid is the halves', each read by its key.
        assertKeyed("MATCH (a$item)-[r]-(b) WHERE id(a) = \$id RETURN b.k ORDER BY b.k", mapOf("id" to id(5)), listOf(4L, 6L))
        assertKeyed("MATCH ()-[r]-() WHERE id(r) = \$id RETURN r.k", mapOf("id" to edgeIds.getValue(3)), listOf(3L, 3L), table = "edge")
        assertKeyed("MATCH ()-[r]-() WHERE id(r) IN \$ids RETURN r.k ORDER BY r.k", mapOf("ids" to listOf(edgeIds.getValue(3), edgeIds.getValue(4))), listOf(3L, 3L, 4L, 4L), table = "edge")
        assertKeyed("MATCH (a$item) WHERE id(a) = \$id MATCH (a)-[]-(b) MATCH (c) WHERE id(c) = id(b) RETURN c.k ORDER BY c.k", mapOf("id" to id(5)), listOf(4L, 6L))
    }

    @Test
    fun `under NOT, beside an OR, or as a value, the comparison stands alone, and gives what it gave`() {
        assertUnkeyed("MATCH (n$item) WHERE NOT id(n) = \$id RETURN count(n)", mapOf("id" to id(5)), listOf(NODES - 1))
        assertUnkeyed("MATCH (n$item) WHERE NOT id(n) IN \$ids RETURN count(n)", mapOf("ids" to listOf(id(5), null)), listOf(0L))
        assertUnkeyed("MATCH (n$item) WHERE NOT id(n) IN \$ids RETURN count(n)", mapOf("ids" to listOf(id(5), "x")), listOf(NODES - 1))
        assertUnkeyed("MATCH (n$item) WHERE id(n) = \$id OR n.k = 7 RETURN n.k ORDER BY n.k", mapOf("id" to id(5)), listOf(5L, 7L))
        assertUnkeyed("MATCH (n$item) WHERE (id(n) = \$id) = false RETURN count(n)", mapOf("id" to id(5)), listOf(NODES - 1))
        assertUnkeyed("MATCH (n$item) RETURN count(CASE WHEN id(n) = \$id THEN 1 END)", mapOf("id" to id(5)), listOf(1L))
        assertEquals(listOf(listOf(5L, true)), query("MATCH (n$item) WHERE n.k = 5 RETURN [n.k, id(n) = \$id]", mapOf("id" to id(5).toDouble())))
        assertEquals(listOf(listOf(5L, null)), query("MATCH (n$item) WHERE n.k = 5 RETURN [n.k, id(n) IN \$ids]", mapOf("ids" to listOf(null, "x"))))
    }

    @Test
    fun `an UNWIND of a hundred ids reads a hundred nodes, not every node for each`() {
        val given = mapOf("ids" to (0L until 100L).map { id(it * 7) })
        val statement = "UNWIND \$ids AS i MATCH (n$item) WHERE id(n) = i RETURN count(n)"
        assertEquals(listOf(100L), query(statement, given))
        val runs = (1..5).map { val start = System.nanoTime(); query(statement, given); (System.nanoTime() - start) / 1_000_000.0 }
        println("${this::class.simpleName}: UNWIND of 100 ids over $NODES nodes, mean of 5 runs: ${"%.2f".format(runs.average())} ms")
        assertKeyed(statement, given, listOf(100L))
    }

    companion object {
        const val GRAPH = "idkey"
        const val NODES = 5000L
        const val EDGES = NODES - 1
    }
}

class SqliteIdKeyPlanTest : IdKeyPlanContract() {
    override val pm by lazy { SqliteTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { SqliteTestDatabase.fixtures(pm) }

    override fun compiled(cypher: String, parameters: Map<String, Any?>) = SqliteCypherCompiler(GRAPH).compile(cypher, parameters)

    /** A line for each table or rows the plan reads, each led by the table it reads, as `SqliteJoinOrderTest` reads it. */
    override fun plan(compiled: CompiledCypher): String {
        val tables = Regex("""\b${GRAPH}_(node|edge) (\w+)""").findAll(compiled.sql).associate { it.groupValues[2] to it.groupValues[1] }
        return pm.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN ${compiled.sql}").bind(compiled.parameters))
            .map { row -> (row as List<*>).last() as String }
            .filter { it.startsWith("SCAN ") || it.startsWith("SEARCH ") }
            .joinToString("\n") { line -> "${tables[line.split(" ")[1]] ?: "rows"}: $line" }
    }

    override fun readsByKey(plan: String, table: String) = plan.lines().any { it.startsWith("$table: SEARCH ") && "INTEGER PRIMARY KEY (rowid=?)" in it }

    override fun scans(plan: String, table: String) = plan.lines().any { it.startsWith("$table: SCAN ") }

    override fun analyze() = SqliteTestDatabase.run("ANALYZE ${GRAPH}_node", "ANALYZE ${GRAPH}_edge")
}

@PostgresTests
class PostgresIdKeyPlanTest : IdKeyPlanContract() {
    override val pm by lazy { PostgresTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { PostgresTestDatabase.fixtures(pm) }

    override fun compiled(cypher: String, parameters: Map<String, Any?>) = PostgresCypherCompiler(GRAPH).compile(cypher, parameters)

    /**
     * The plan of [compiled] with no hash or merge join. Over a graph this small, Postgres reads every node once and joins
     * them to a hundred ids by a hash of the key, where it reads each node by the key over a larger one: without either join,
     * the plan reads the key where it can.
     */
    override fun plan(compiled: CompiledCypher): String {
        val connection = PostgresTestDatabase.provider.connect()
        return try {
            connection.startTransaction()
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_hashjoin = off"))
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_mergejoin = off"))
            connection.query(QuerySpecification.withStatement("EXPLAIN ${compiled.sql}").bind(compiled.parameters)).joinToString("\n")
        } finally {
            connection.release()
        }
    }

    /** Reading the row by the key, or reading none where the key is known to be no id, as for `5.5`. */
    override fun readsByKey(plan: String, table: String): Boolean {
        val id = if (table == "node") "nid" else "eid"
        return "One-Time Filter: false" in plan || Regex("""Index (Only )?Scan using ${GRAPH}_${table}_pkey on ${GRAPH}_$table \w+[^\n]*\n\s+Index Cond: \($id = """).containsMatchIn(plan)
    }

    /**
     * A scan of the table, of the index over labels (which every node here carries), or of the key's index with no condition
     * on the key, each of which reads each row as a scan does.
     */
    override fun scans(plan: String, table: String): Boolean {
        val id = if (table == "node") "nid" else "eid"
        val lines = plan.lines()
        return lines.withIndex().any { (i, line) ->
            ("Seq Scan on ${GRAPH}_$table " in line) || ("Bitmap Heap Scan on ${GRAPH}_$table " in line && lines.getOrNull(i + 1)?.contains("Recheck Cond: (labels") == true) ||
                ("Scan using ${GRAPH}_${table}_pkey on ${GRAPH}_$table " in line && lines.getOrNull(i + 1)?.contains("Index Cond: ($id = ") != true)
        }
    }

    override fun analyze() = PostgresTestDatabase.run("ANALYZE ${GRAPH}_node", "ANALYZE ${GRAPH}_edge")
}

@OracleTests
class OracleIdKeyPlanTest : IdKeyPlanContract() {
    override val item = ""
    override val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { OracleTestDatabase.fixtures(pm) }

    override fun compiled(cypher: String, parameters: Map<String, Any?>) = OracleCypherCompiler(GRAPH).compile(cypher, parameters)

    /** The plan Oracle makes for [compiled], its values written in as literals, explained and read in one block, so on one session. */
    override fun plan(compiled: CompiledCypher): String {
        val sql = compiled.parameters.entries.sortedByDescending { it.key.length }
            .fold(compiled.sql) { text, (name, value) -> text.replace("\$$name", if (value == null) "NULL" else OracleSql.literal(value.toString())) }
        val block = """
            DECLARE
                v_plan SYS_REFCURSOR;
            BEGIN
                EXECUTE IMMEDIATE ${OracleSql.text("EXPLAIN PLAN FOR $sql")};
                OPEN v_plan FOR SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +PREDICATE'));
                DBMS_SQL.RETURN_RESULT(v_plan);
            END;
        """.trimIndent()
        return pm.query(QuerySpecification.withStatement(block).transform(String::class.java)).joinToString("\n")
    }

    /**
     * The key read, by its index or as the key of a join: over a graph this small, Oracle, which takes a `JSON_TABLE` to give
     * 8,168 rows, joins the nodes to the ids by a hash of the key, once over the nodes, where it reads each node by its key
     * over a larger graph.
     */
    override fun readsByKey(plan: String, table: String): Boolean {
        val key = if (table == "node") "NID" else "EID"
        // A node read by the key a relationship's end gives is no key the statement compares. Where the statement reads no
        // more of a node than its key, Oracle drops it, and reads the relationship's end by the key compared, through its index.
        // Within a half of a relationship read both ways round (#570), the key is named with no table's.
        return plan.lines().any { line ->
            (Regex("""access\(.*"$key"""").containsMatchIn(line) && !Regex(""""\."(SRC|DST)"""").containsMatchIn(line)) ||
                (table == "node" && Regex("""access\("\w+"\."(SRC|DST)"=JSON_VALUE""").containsMatchIn(line))
        }
    }

    /** A scan of the table, or of an index, that reads no row by its key: each row is compared with each id. */
    override fun scans(plan: String, table: String) =
        (Regex("""TABLE ACCESS (STORAGE )?FULL\s*\|\s*${GRAPH.uppercase()}_${table.uppercase()}\b""").containsMatchIn(plan) ||
            Regex("""INDEX (FULL|FAST FULL|RANGE) SCAN""").containsMatchIn(plan)) && !readsByKey(plan, table)

    override fun analyze() = OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE'); DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_EDGE'); END;")
}
