package org.drivine.oracle

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.backend.GraphStatement
import org.drivine.backend.oracle.LabelledIndexes
import org.drivine.backend.oracle.OracleBackend
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSelect
import org.drivine.backend.oracle.OracleSql
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.schema.EnsureResult
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@NodeFragment(labels = ["Issue"])
data class LabelledIssue(@NodeId val id: String, val repoKey: String, val state: String)

/**
 * A filter on an indexed property of the nodes of a label, on a graph where the label is a third of
 * the nodes and other labels hold the same properties: Oracle reads it through the property's
 * labelled index, and never through the index over labels, whether the value is rare or held by
 * most of the label's nodes. The answers are those of the statement written without the index.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleLabelledIndexIntegrationTest {

    private val pm = OracleTestDatabase.graphManager(GRAPH)
    private val graph = OracleGraph(GRAPH)

    @BeforeAll
    fun seed() {
        OracleTestDatabase.clear(pm)
        // What an earlier run left.
        listOf("repoKey", "state", "number").forEach { pm.indexes.drop(RangeIndexSpec("Issue", it)) }
        OracleTestDatabase.run("DROP INDEX IF EXISTS ${graph.objectName("ix", listOf("number"))}")
        // 6,000 issues over 60 repos, 95 in 100 of them open; one in ten carries a second label.
        // 12,000 comments with the same repos and states, and 6,000 people with neither.
        OracleTestDatabase.run(
            """
            INSERT INTO ${graph.nodes} (labels, props)
            SELECT CASE WHEN MOD(level, 10) = 0 THEN JSON('["Issue","Archived"]') ELSE JSON('["Issue"]') END,
                   JSON_OBJECT('id' VALUE 'i' || level, 'repoKey' VALUE 'r' || MOD(level, 60),
                               'state' VALUE CASE WHEN MOD(level, 20) = 0 THEN 'closed' ELSE 'open' END RETURNING JSON)
            FROM dual CONNECT BY level <= 6000
            """,
            """
            INSERT INTO ${graph.nodes} (labels, props)
            SELECT JSON('["Comment"]'), JSON_OBJECT('id' VALUE 'c' || level, 'repoKey' VALUE 'r' || MOD(level, 60), 'state' VALUE 'open' RETURNING JSON)
            FROM dual CONNECT BY level <= 12000
            """,
            """
            INSERT INTO ${graph.nodes} (labels, props)
            SELECT JSON('["Person"]'), JSON_OBJECT('id' VALUE 'p' || level, 'name' VALUE 'n' || level RETURNING JSON)
            FROM dual CONNECT BY level <= 6000
            """,
        )
        assertTrue(pm.indexes.ensure(RangeIndexSpec("Issue", "repoKey")) is EnsureResult.Created)
        assertTrue(pm.indexes.ensure(RangeIndexSpec("Issue", "state")) is EnsureResult.Created)
        OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${graph.nodes.uppercase()}'); END;")
    }

    private val backend by lazy { OracleBackend(pm, Neo4jObjectMapper.instance) }

    private fun query(vararg conditions: WhereCondition) = GraphQuerySpec(Any()).apply { this.conditions += conditions }

    private fun eq(key: String, value: String) = PropertyCondition("n.$key", ComparisonOperator.EQUALS, value)

    private val labelledRepoKey = graph.objectName("lx", listOf("Issue", "repoKey")).uppercase()
    private val labelledState = graph.objectName("lx", listOf("Issue", "state")).uppercase()
    private val overLabels = "${GRAPH}_node_labels".uppercase()

    /** The ids [statement] loads. */
    private fun ids(statement: GraphStatement): Set<String> =
        pm.query(QuerySpecification.withStatement(statement.statement).bind(statement.bindings).transform(Map::class.java)).map { it["id"] as String }.toSet()

    /** What [statement] counts. */
    private fun counted(statement: GraphStatement): Long =
        pm.query(QuerySpecification.withStatement(statement.statement).bind(statement.bindings).transform(Number::class.java)).single().toLong()

    /** The ids the statement written without labelled indexes loads, for [conditions]. */
    private fun unindexed(vararg conditions: WhereCondition): Set<String> {
        val select = OracleSelect(graph, LabelledIssue::class.java, labelled = LabelledIndexes.NONE)
        return ids(select.load(listOfNotNull(select.predicate(conditions.toList(), indexed = true))))
    }

    /**
     * The plan Oracle makes for [statement], its values written in as literals so that the plan is
     * the one for them. Explained and read in one block, so on one session.
     */
    private fun plan(statement: GraphStatement): String {
        val sql = statement.bindings.entries.sortedByDescending { it.key.length }
            .fold(statement.statement) { text, (name, value) -> text.replace("\$$name", OracleSql.literal(value.toString())) }
        val block = """
            DECLARE
                v_plan SYS_REFCURSOR;
            BEGIN
                EXECUTE IMMEDIATE ${OracleSql.text("EXPLAIN PLAN FOR $sql")};
                OPEN v_plan FOR SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +ROWS'));
                DBMS_SQL.RETURN_RESULT(v_plan);
            END;
        """.trimIndent()
        return pm.query(QuerySpecification.withStatement(block).transform(String::class.java)).joinToString("\n")
    }

    @Test
    fun `a selective filter reads the labelled index of its property, and not the index over labels`() {
        val conditions = arrayOf(eq("repoKey", "r7"), eq("state", "open"))
        val load = backend.loadAll(LabelledIssue::class.java, query(*conditions)).statement

        val plan = plan(load)
        assertTrue(labelledRepoKey in plan || labelledState in plan, plan)
        assertFalse(overLabels in plan, plan)

        val expected = (1..6000).filter { it % 60 == 7 && it % 20 != 0 }.map { "i$it" }.toSet()
        assertEquals(expected, ids(load))
        assertEquals(expected, unindexed(*conditions))
    }

    @Test
    fun `a filter on a value most of the label's nodes hold does not go through the index over labels either, and gives the same answer`() {
        val load = backend.loadAll(LabelledIssue::class.java, query(eq("state", "open"))).statement

        val plan = plan(load)
        assertFalse(overLabels in plan, plan)

        val loaded = ids(load)
        assertEquals(5700, loaded.size)
        assertEquals(unindexed(eq("state", "open")), loaded)
        assertEquals(300, ids(backend.loadAll(LabelledIssue::class.java, query(eq("state", "closed"))).statement).size)
    }

    @Test
    fun `a count by an indexed property is answered from the labelled index alone`() {
        val count = backend.count(LabelledIssue::class.java, query(eq("repoKey", "r7")))

        val plan = plan(count)
        assertTrue(labelledRepoKey in plan, plan)
        assertFalse(overLabels in plan, plan)
        assertFalse("TABLE ACCESS" in plan, "no node is read:\n$plan")
        assertEquals(100, counted(count))
        assertEquals(5700, counted(backend.count(LabelledIssue::class.java, query(eq("state", "open")))))
    }

    @Test
    fun `nodes of another label with the same value are not among the answers`() {
        // Every comment of repo r7 is open; none is an issue.
        val loaded = ids(backend.loadAll(LabelledIssue::class.java, query(eq("repoKey", "r7"))).statement)
        assertEquals(100, loaded.size)
        assertTrue(loaded.all { it.startsWith("i") })
    }

    @Test
    fun `a range index recorded before there were labelled indexes is made again, with its labelled index, and dropped with it`() {
        val spec = RangeIndexSpec("Issue", "number")
        val shared = graph.objectName("ix", listOf("number"))
        val labelled = graph.objectName("lx", listOf("Issue", "number"))
        // As an earlier version made it: the index over the property, and a record with no details.
        OracleTestDatabase.run(
            "CREATE INDEX $shared ON ${graph.nodes} (JSON_VALUE(props, '$.number'))",
            "INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details) VALUES ('RANGE_INDEX', 'Issue', '[\"number\"]', 'Issue_number_range', '$shared', '{}')",
        )
        assertNull(pm.indexes.find(spec), "listed as not there, for its labelled index is not")

        assertTrue(pm.indexes.ensure(spec) is EnsureResult.Created)
        assertNotNull(pm.indexes.find(spec))
        assertTrue(indexed(labelled))
        assertTrue(pm.indexes.ensure(spec) is EnsureResult.AlreadyMatching)

        assertTrue(pm.indexes.drop(spec))
        assertFalse(indexed(labelled))
        assertFalse(indexed(shared))
    }

    private fun indexed(name: String): Boolean =
        pm.query(QuerySpecification.withStatement("SELECT COUNT(*) FROM user_indexes WHERE index_name = '${name.uppercase()}'").transform(Number::class.java)).single().toInt() == 1

    private companion object {
        const val GRAPH = "labelled"
    }
}
