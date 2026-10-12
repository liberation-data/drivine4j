package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.model.GraphViewModel
import org.junit.jupiter.api.Test
import sample.stateless.ClaimEmployers
import sample.stateless.HumanClaims
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The row sources of a statement, and what its parts share as it is written: its parameters and the names it gives out. */
class OracleRowSourcesTest {

    private val graph = OracleGraph("g")
    private val scope = OracleStatementScope()
    private val rows = OracleRowSources(graph, scope)

    private fun String.squashed(): String = replace(Regex("\\s+"), " ")

    private fun relationship(view: Class<*>, field: String) = GraphViewModel.from(view).relationships.single { it.fieldName == field }

    // ----- The scope -----

    @Test
    fun `each value is bound under its own parameter, in the order it was bound, a null and a repeat among them`() {
        assertEquals("\$_p0", scope.bound("a"))
        assertEquals("\$_p1", scope.bound(null))
        assertEquals("\$_p2", scope.bound("a"))

        assertEquals(listOf("_p0" to "a", "_p1" to null, "_p2" to "a"), scope.bindings().toList())
    }

    @Test
    fun `a name is given out once, whatever prefix it is asked under, and ends as no SQL word does`() {
        val names = listOf(scope.next("t"), scope.next("t"), scope.next("x"), scope.next("t"))

        assertEquals(listOf("t0_", "t1_", "x2_", "t3_"), names)
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `binding a value gives out no name, and a name binds nothing`() {
        scope.bound(1)
        assertEquals("t0_", scope.next("t"))
        assertEquals(setOf("_p0"), scope.bindings().keys)
    }

    // ----- Nodes -----

    @Test
    fun `the nodes of some labels are asked of the index, until the statement finds its roots some narrower way`() {
        val scanned = rows.nodes(listOf("Claim", "Hot"), "n_").squashed()
        scope.narrowed = true
        val narrowed = rows.nodes(listOf("Claim", "Hot"), "n_").squashed()

        assertEquals(
            "GRAPH_TABLE (g MATCH (v IS node WHERE JSON_EXISTS(v.labels, '$[*]?(@ == \"Claim\")') AND JSON_EXISTS(v.labels, '$[*]?(@ == \"Hot\")')) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) n_",
            scanned,
        )
        assertEquals(
            "GRAPH_TABLE (g MATCH (v IS node WHERE INSTR(JSON_SERIALIZE(v.labels), '\"Claim\"') > 0 AND INSTR(JSON_SERIALIZE(v.labels), '\"Hot\"') > 0) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) n_",
            narrowed,
        )
    }

    @Test
    fun `nodes of no label are every node, with no predicate at all`() {
        assertEquals("GRAPH_TABLE (g MATCH (v IS node) COLUMNS (v.nid, v.labels, v.props, v.stamp, v.doc)) n_", rows.nodes(emptyList(), "n_").squashed())
    }

    // ----- Reached -----

    @Test
    fun `one step taken once gives the relationship with the node, and the node it started from as src`() {
        val sql = rows.reached(listOf(Step("MENTIONS", Direction.OUTGOING, listOf("Human"))), "t0_").squashed()

        assertEquals(
            "GRAPH_TABLE (g MATCH (a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Human\"') > 0)" +
                " COLUMNS (a.nid AS src, e0.eid AS eid, e0.props AS eprops, b.nid, b.labels, b.props, b.stamp, b.doc)) t0_",
            sql,
        )
    }

    @Test
    fun `a step taken up to a depth is quantified from one, and gives no relationship, for there may be several`() {
        val sql = rows.reached(listOf(Step("PART_OF", Direction.INCOMING, emptyList(), maxDepth = 3)), "t").squashed()

        assertTrue("<-[e0 IS edge WHERE e0.type = 'PART_OF']-{1,3} (b IS node)" in sql, sql)
        assertFalse("eid" in sql || "eprops" in sql, sql)
        assertTrue("COLUMNS (a.nid AS src, b.nid, " in sql, sql)
    }

    @Test
    fun `several steps name each relationship and each node between apart, and only the last node is the one reached`() {
        val sql = rows.reached(
            listOf(
                Step("MENTIONS", Direction.OUTGOING, listOf("Human")),
                Step("WORKS_AT", Direction.UNDIRECTED, emptyList()),
                Step("OWNED_BY", Direction.INCOMING, listOf("Company")),
            ),
            "t",
        ).squashed()

        assertTrue(
            ("(a IS node) -[e0 IS edge WHERE e0.type = 'MENTIONS']-> (m0 IS node WHERE INSTR(JSON_SERIALIZE(m0.labels), '\"Human\"') > 0)" +
                " -[e1 IS edge WHERE e1.type = 'WORKS_AT']- (m1 IS node)" +
                " <-[e2 IS edge WHERE e2.type = 'OWNED_BY']- (b IS node WHERE INSTR(JSON_SERIALIZE(b.labels), '\"Company\"') > 0)") in sql,
            sql,
        )
        assertFalse("eid" in sql, sql)
    }

    @Test
    fun `a type and a label are data, so a quote in either is escaped where it is written`() {
        val sql = rows.reached(listOf(Step("IT'S", Direction.OUTGOING, listOf("O'Brien \"the\" label"))), "t")

        assertTrue("e0.type = 'IT''S'" in sql, sql)
        assertTrue("'\"O''Brien \\\"the\\\" label\"'" in sql, sql)
    }

    // ----- Steps -----

    @Test
    fun `a relationship is one step to its depth, with the target's labels on the node it ends at`() {
        val step = rows.steps(relationship(HumanClaims::class.java, "claims"), listOf("Claim"), maxDepth = 4).single()

        assertEquals("MENTIONS", step.type)
        assertEquals(Direction.INCOMING, step.direction)
        assertEquals(listOf("Claim"), step.labels)
        assertEquals(4, step.maxDepth)
    }

    @Test
    fun `a path is a step for each hop taken once, the target's labels on the last and each hop's own label on the node before`() {
        val steps = rows.steps(relationship(ClaimEmployers::class.java, "employers"), listOf("Company"), maxDepth = 9)

        assertEquals(listOf("MENTIONS", "WORKS_AT"), steps.map { it.type })
        assertEquals(listOf(listOf("Human"), listOf("Company")), steps.map { it.labels })
        // A path has no depth of its own: each hop is taken once, whatever depth is asked.
        assertEquals(listOf(1, 1), steps.map { it.maxDepth })
    }

    // ----- Exists -----

    @Test
    fun `whether a node is reached joins the traversal to the parent row outside it, under the row the caller named`() {
        val sql = rows.exists("claim_", listOf(Step("MENTIONS", Direction.OUTGOING, listOf("Human"))), "people_", "JSON_VALUE(people_.props, '$.name') = \$_p0")

        assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM GRAPH_TABLE (g"), sql)
        assertTrue(sql.endsWith(") people_ WHERE people_.src = claim_.nid AND JSON_VALUE(people_.props, '$.name') = \$_p0)"), sql)
    }

    @Test
    fun `with no row named, each test of whether a node is reached names a row of its own, and with no predicate adds none`() {
        val steps = listOf(Step("MENTIONS", Direction.OUTGOING, emptyList()))
        val first = rows.exists("n_", steps, null)
        val second = rows.exists("n_", steps, null)

        assertTrue(first.endsWith(") t0_ WHERE t0_.src = n_.nid)"), first)
        assertTrue(second.endsWith(") t1_ WHERE t1_.src = n_.nid)"), second)
        assertNotEquals(first, second)
    }
}
