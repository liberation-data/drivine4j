package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.Nightly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A compiled write that loops over its rows takes time in proportion to them (#588). Each row the loop kept was appended
 * to a JSON list of the rows it leaves, which copied the list, so ten times the rows took about a hundred times as long:
 * `MATCH (n) SET n.x = 1` over 40,000 nodes took 130 s on Postgres. Each write is timed over [SMALL] rows and [LARGE],
 * ten times as many, and the large run must take less than [RATIO] times the small one, or [FLOOR_MS] where that is
 * more: a ratio, so a slower CI runner, which is slower at both, passes alike.
 */
abstract class CompiledWriteLoopTimeContract {

    abstract val pm: NonTransactionalPersistenceManager

    abstract fun clear()

    /** Run after [clear], with the graph empty, before the writes are timed: see [CompiledWriteLoopTimePostgresTest]. */
    open fun emptied() {}

    private fun query(cypher: String, n: Int): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(mapOf("n" to n)))

    private fun count(cypher: String) = pm.query(QuerySpecification.withStatement(CypherStatement(cypher))).single()

    /** The writes timed, each with what it leaves for [n] rows, read back after it. */
    private val writes: List<Triple<String, String, (Int) -> List<Any?>>> = listOf(
        Triple("CREATE", "UNWIND range(1, \$n) AS i CREATE (:CyLoop {i: i})", { n -> listOf(n.toLong()) }),
        Triple("SET", "MATCH (n:CyLoop) SET n.x = n.i", { n -> listOf(n.toLong()) }),
        Triple("REMOVE", "MATCH (n:CyLoop) REMOVE n.x", { _ -> listOf(0L) }),
        Triple("MERGE", "MATCH (n:CyLoop) MERGE (n)-[:CY_SELF]->(n)", { n -> listOf(n.toLong()) }),
        Triple("CALL", "MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN n.i AS j } RETURN count(j)", { n -> listOf(n.toLong()) }),
    )

    /**
     * What each write left, read back: the nodes, those with x, the relationships, and those with y. Each self-loop is read
     * from its one node, `(n)-[r]->(n)`, which joins the node table once: `(:CyLoop)-[r]->(:CyLoop)` joins it twice, and on
     * Postgres, with the node table's statistics left stale on purpose (see [CompiledWriteLoopTimePostgresTest]), that was
     * planned as a scan of every node for each node: 470 s over 20,000 rows, untimed (#673, liberation-data/drivine#680).
     */
    private val checks = listOf(
        "MATCH (n:CyLoop) RETURN count(n)",
        "MATCH (n:CyLoop) WHERE n.x IS NOT NULL RETURN count(n)",
        "MATCH (n:CyLoop) WHERE n.x IS NOT NULL RETURN count(n)",
        "MATCH (n:CyLoop)-[r:CY_SELF]->(n) RETURN count(r)",
        "MATCH (n:CyLoop) WHERE n.y = n.i RETURN count(n)",
    )

    /** The milliseconds each write took over [n] rows. */
    private fun timed(n: Int): List<Long> {
        clear()
        emptied()
        return writes.mapIndexed { i, (name, cypher, expected) ->
            val start = System.nanoTime()
            val rows = query(cypher, n)
            val ms = (System.nanoTime() - start) / 1_000_000
            if (name == "CALL") assertEquals(expected(n), rows, "$name over $n rows")
            assertEquals(expected(n), listOf(count(checks[i])), "$name over $n rows, read back")
            ms
        }
    }

    @Test
    fun `a write over ten times the rows takes about ten times as long, not a hundred`() {
        val small = timed(SMALL)
        val large = timed(LARGE)
        val report = writes.indices.joinToString("; ") { "${writes[it].first} ${small[it]} ms over $SMALL rows, ${large[it]} ms over $LARGE" }
        println("${this::class.simpleName}: $report")
        val slow = writes.indices.filter { slow(small[it], large[it]) }
        if (slow.isNotEmpty()) fail("Grew faster than the rows: ${slow.joinToString { writes[it].first }}. $report")
    }

    internal companion object {
        const val SMALL = 2_000
        const val LARGE = 20_000

        /** Linear is about 10, and was 50 to 100 when each row copied the list. */
        const val RATIO = 30L

        /** Where the small run is short, its fixed costs, and a CI runner's jitter, are much of it: the large one gets this at least. */
        const val FLOOR_MS = 3_000L

        /** Whether a write that took [small] ms over [SMALL] rows and [large] over [LARGE] grew faster than its rows. */
        fun slow(small: Long, large: Long) = large > maxOf(RATIO * small, FLOOR_MS)
    }
}

/**
 * On Postgres the edge table is vacuumed while empty first, as autovacuum leaves a store's tables once they have been
 * emptied (#609): its statistics then say it has no rows, where a table never vacuumed is taken to have ten pages.
 * The `MERGE` read its pattern by a plan PL/pgSQL made for the empty edge table, a scan of the whole table, and kept it
 * while the loop filled the table: 28.6 s over 20,000 rows, where it took 0.4 s on a table never vacuumed.
 *
 * The node table is analyzed just after it was emptied, not vacuumed, as an autoanalyze that runs just after the `DELETE`
 * leaves it: its pages of dead rows taken to hold one row (#631, drivine-python#430). The `CALL`'s per-row read of its
 * node was then planned to materialize a scan of the whole primary key index and filter it for the row's node, so it was
 * quadratic: 91 s over 20,000 rows, and 72 s to 142 s in drivine-python's CI. Materialization is now off in the loop
 * too. So that the state lasts until the `CALL`, as it did there, the node table is not autovacuumed while the test
 * runs: an autoanalyze of the rows the writes made, landing before it, put the statistics right, and the test passed
 * without the fix. The tables are truncated first, so they start as small as in a database new to the test.
 */
@Nightly
@PostgresTests
class CompiledWriteLoopTimePostgresTest : CompiledWriteLoopTimeContract() {
    override val pm = PostgresTestDatabase.graphManager("cy_loop")

    @BeforeEach
    fun truncated() = PostgresTestDatabase.run("TRUNCATE cy_loop_edge, cy_loop_node", "ALTER TABLE cy_loop_node SET (autovacuum_enabled = off)")

    @AfterEach
    fun autovacuumed() = PostgresTestDatabase.run("ALTER TABLE cy_loop_node RESET (autovacuum_enabled)")

    override fun clear() = PostgresTestDatabase.clear(pm)

    override fun emptied() = PostgresTestDatabase.run("ANALYZE cy_loop_node", "VACUUM ANALYZE cy_loop_edge")
}

/**
 * A compiled write whose loop reads a node or a relationship by its key, from a table the loop adds to, or joins a table to
 * the rows of a `CALL { … }` for one row, takes time in proportion to its rows on Postgres (#609): a `MERGE`
 * that reads its pattern, a `SET` of what the loop made, in a `MERGE`'s `ON CREATE`, a `CALL { … }` or a `FOREACH`, with
 * the tables vacuumed while empty; and a `CALL { … }` that returns a value or deletes what it made, over nodes just made,
 * which Postgres planned to hash a scan of every node for each row (17 s over 20,000 rows). Each is timed as
 * [CompiledWriteLoopTimeContract] times a write, from an empty graph vacuumed, that [nodes] makes the nodes of where it
 * reads them.
 */
@Nightly
@PostgresTests
class CompiledWriteKeyedReadTimePostgresTest {

    private val pm = PostgresTestDatabase.graphManager("cy_keyed")

    private fun query(cypher: String, n: Int): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(mapOf("n" to n)))

    /** Each write, and what it leaves, counted after it: n each time, over n rows. One that starts with `MATCH` reads [nodes]. */
    private val writes = listOf(
        Triple("MERGE ON CREATE SET", "MATCH (n:CyLoop) MERGE (n)-[r:CY_SELF]->(n) ON CREATE SET r.w = n.i", "MATCH (n:CyLoop)-[r:CY_SELF]->(n) WHERE r.w IS NOT NULL RETURN count(r)"),
        Triple("CALL MERGE", "MATCH (n:CyLoop) CALL (n) { MERGE (n)-[:CY_SELF]->(n) } RETURN count(n)", "MATCH (n:CyLoop)-[r:CY_SELF]->(n) RETURN count(r)"),
        Triple("CALL CREATE SET", "MATCH (n:CyLoop) CALL (n) { CREATE (n)-[r:CY_SELF]->(n) SET r.w = n.i } RETURN count(n)", "MATCH (n:CyLoop)-[r:CY_SELF]->(n) WHERE r.w IS NOT NULL RETURN count(r)"),
        Triple("FOREACH CREATE SET", "MATCH (n:CyLoop) FOREACH (x IN [1] | CREATE (n)-[r:CY_SELF]->(n) SET r.w = x)", "MATCH (n:CyLoop)-[r:CY_SELF]->(n) WHERE r.w = 1 RETURN count(r)"),
        Triple("CALL RETURN", "MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN n.i AS j } RETURN count(j)", "MATCH (n:CyLoop) WHERE n.y = n.i RETURN count(n)"),
        Triple("CALL DETACH DELETE", "MATCH (n:CyLoop) CALL (n) { CREATE (m:CyMade) DETACH DELETE m } RETURN count(n)", "MATCH (n:CyLoop) RETURN count(n)"),
        Triple("CALL CREATE node SET", "UNWIND range(1, \$n) AS i CALL (i) { CREATE (a:CyLoop {i: i}) SET a.y = i } RETURN count(i)", "MATCH (n:CyLoop) WHERE n.y = n.i RETURN count(n)"),
    )

    /** The nodes a write that reads them is given. */
    private val nodes = "UNWIND range(1, \$n) AS i CREATE (:CyLoop {i: i})"

    /** The milliseconds [cypher] took over [n] rows, from an empty graph vacuumed. */
    private fun timed(cypher: String, check: String, n: Int): Long {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.vacuum("cy_keyed")
        if (cypher.startsWith("MATCH")) query(nodes, n)
        val start = System.nanoTime()
        query(cypher, n)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(listOf(n.toLong()), query(check, n), "$cypher over $n rows, read back")
        return ms
    }

    @Test
    fun `a write whose loop reads a table for each row takes about ten times as long over ten times the rows`() {
        val times = writes.map { (_, cypher, check) -> timed(cypher, check, CompiledWriteLoopTimeContract.SMALL) to timed(cypher, check, CompiledWriteLoopTimeContract.LARGE) }
        val report = writes.indices.joinToString("; ") { "${writes[it].first} ${times[it].first} ms over ${CompiledWriteLoopTimeContract.SMALL} rows, ${times[it].second} ms over ${CompiledWriteLoopTimeContract.LARGE}" }
        println("${this::class.simpleName}: $report")
        val slow = writes.indices.filter { CompiledWriteLoopTimeContract.slow(times[it].first, times[it].second) }
        if (slow.isNotEmpty()) fail("Grew faster than the rows: ${slow.joinToString { writes[it].first }}. $report")
    }
}

@Nightly
@OracleTests
class CompiledWriteLoopTimeOracleTest : CompiledWriteLoopTimeContract() {
    override val pm = OracleTestDatabase.graphManager("cy_loop")

    override fun clear() = OracleTestDatabase.clear(pm)
}

@Nightly
class CompiledWriteLoopTimeSqliteTest : CompiledWriteLoopTimeContract() {
    override val pm = SqliteTestDatabase.graphManager("cy_loop")

    override fun clear() = SqliteTestDatabase.clear(pm)
}

/**
 * On Oracle, a query that reads the rows of a `CALL { … }` or a `FOREACH` written for each row alone is planned for one row
 * of them, `CARDINALITY(r 1)` in its own query's hint: Oracle estimated 8,168 rows of the `JSON_TABLE`, and read every node
 * by a hash join for each row (#588). A query of the statement's rows, before or after the loop, is planned as it was.
 */
class OracleOneRowPlanTest {

    private fun compiled(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql

    private fun hinted(sql: String) = Regex("""SELECT /\*\+ [^*]*CARDINALITY\((r\d+) 1\)[^*]*\*/""").findAll(sql).map { it.groupValues[1] }.toList()

    @Test
    fun `a CALL's projection of each row reads it as one row, in the query that reads it`() {
        val sql = compiled("MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN n.i AS j } RETURN count(j)")
        val projection = Regex("""SELECT /\*\+ CARDINALITY\((r\d+) 1\) \*/ JSON\(JSON_QUERY\(JSON_SERIALIZE\(JSON_TRANSFORM\(n\d+\.props, KEEP '\$\.i'\)[^\n]*\n\s*r\d+\.item_no AS o1\n\s*FROM JSON_TABLE\(cy_rows, '\$\[\*]' COLUMNS \(item_no FOR ORDINALITY, item JSON PATH '\$'\)\) (r\d+)\n""")
        val found = projection.find(sql)
        assertTrue(found != null && found.groupValues[1] == found.groupValues[2], sql)
        // The loop over the call's rows, and the query of all the statement's rows after it, read them as before.
        assertTrue("FOR cy_record IN (SELECT r2.item FROM JSON_TABLE(cy_saved1," in sql, sql)
        assertTrue(Regex("""OPEN cy_cursor FOR\n\s*SELECT p\d+\.c1 AS result""").containsMatchIn(sql), sql)
        assertEquals(hinted(sql).size, hinted(sql).toSet().size, sql)
    }

    @Test
    fun `a text before the rows read, with a bracket, a quote or SELECT in it, is read past to the query's own SELECT`() {
        val plain = hinted(compiled("MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN toString(n.i) AS j } RETURN j"))
        val sql = compiled("MATCH (n:CyLoop) CALL (n) { SET n.y = n.i RETURN toString(n.i) + ') SELECT \\'(' AS j } RETURN j")
        assertTrue(") SELECT ''(" in sql, sql)
        assertEquals(plain.size, hinted(sql).size, sql)
    }

    @Test
    fun `a FOREACH's rows read alone are one row, and a statement with neither is planned as it was`() {
        assertTrue(hinted(compiled("UNWIND [1, 2] AS k FOREACH (x IN [k] | CREATE (:CyWrite {f: x})) RETURN k")).isNotEmpty())
        for (cypher in listOf("UNWIND range(1, 3) AS i CREATE (:CyLoop {i: i})", "MATCH (n:CyLoop) SET n.x = n.i RETURN n.x", "MATCH (n:CyLoop) RETURN n.i")) {
            val sql = compiled(cypher)
            assertTrue("CARDINALITY" !in sql, "$cypher: $sql")
        }
    }
}
