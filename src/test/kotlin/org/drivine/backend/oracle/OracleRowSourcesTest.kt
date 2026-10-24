package org.drivine.backend.oracle

import org.drivine.backend.sql.Step
import org.drivine.annotation.Direction
import org.drivine.model.GraphViewModel
import org.junit.jupiter.api.Test
import sample.stateless.ClaimEmployers
import sample.stateless.HumanClaims
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a row is called what it stands for, with an underscore no SQL word ends in, and the next of that name is numbered`() {
        val names = listOf(scope.row("people"), scope.row("people"), scope.row("user"), scope.row("people"))

        assertEquals(listOf("people_", "people2_", "user_", "people3_"), names)
    }

    @Test
    fun `no two rows share a name, though a field is named as another's second row would be, or differs only in case`() {
        val names = listOf(scope.row("a"), scope.row("a2"), scope.row("a"), scope.row("A"))

        // `a2_` is taken by the field of that name, so the second `a` is the third; Oracle reads `A_` as `a_`.
        assertEquals(listOf("a_", "a2_", "a3_", "A4_"), names)
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size)
    }

    @Test
    fun `a name Oracle could not write bare is made one it can, and a long one is cut to fit`() {
        assertEquals("my_field_", scope.row("my field"))
        assertEquals("r_hidden_", scope.row("_hidden"))
        assertEquals("r1st_", scope.row("1st"))
        assertTrue(scope.row("x".repeat(500)).length <= 128)
    }

    @Test
    fun `binding a value gives out no name, and a name binds nothing`() {
        scope.bound(1)
        assertEquals("t_", scope.row("t"))
        assertEquals(setOf("_p0"), scope.bindings().keys)
    }

    // ----- Nodes -----

    @Test
    fun `a scan asks the index for the nodes of its first label, until the statement finds its roots some narrower way`() {
        val scanned = rows.nodes(listOf("Claim", "Hot"), "n_").whole().squashed()
        scope.narrowed = true
        val narrowed = rows.nodes(listOf("Claim", "Hot"), "n_").whole().squashed()

        assertEquals(
            "GRAPH_TABLE (g MATCH (n_ IS node WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")') AND INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Hot\",\"') > 0) COLUMNS (n_.nid, n_.labels, n_.props, n_.stamp, n_.doc)) n_",
            scanned,
        )
        assertEquals(
            "GRAPH_TABLE (g MATCH (n_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Claim\",\"') > 0 AND INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Hot\",\"') > 0) COLUMNS (n_.nid, n_.labels, n_.props, n_.stamp, n_.doc)) n_",
            narrowed,
        )
    }

    @Test
    fun `nodes of no label are every node, with no predicate at all`() {
        assertEquals("GRAPH_TABLE (g MATCH (n_ IS node) COLUMNS (n_.nid, n_.labels, n_.props, n_.stamp, n_.doc)) n_", rows.nodes(emptyList(), "n_").whole().squashed())
    }

    @Test
    fun `a node of the pattern has the name of its row, so a statement reads of the node its MATCH names`() {
        val sql = rows.nodes(listOf("Claim"), "claim_").reading(listOf("claim_.doc"))

        assertEquals("GRAPH_TABLE (g\n    MATCH (claim_ IS node WHERE JSON_EXISTS(claim_.labels, '$[*]?(@ == \"Claim\")'))\n    COLUMNS (claim_.doc)) claim_", sql)
    }

    // ----- Reached -----

    @Test
    fun `one step taken once gives the relationship with the node, and the node it started from as src`() {
        val sql = rows.reached("claim_", listOf(Step("MENTIONS", Direction.OUTGOING, listOf("Human"))), "people_").whole().squashed()

        assertEquals(
            "GRAPH_TABLE (g MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(people_.labels), ']'), '[') || ',\"', ',\"Human\",\"') > 0)" +
                " COLUMNS (claim_.nid AS src, e.eid, e.props AS eprops, people_.nid, people_.labels, people_.props, people_.stamp, people_.doc)) people_",
            sql,
        )
    }

    @Test
    fun `a step taken up to a depth is quantified from one, and gives no relationship, for there may be several`() {
        val sql = rows.reached("n_", listOf(Step("PART_OF", Direction.INCOMING, emptyList(), maxDepth = 3)), "t_").whole().squashed()

        assertTrue("(n_ IS node) <-[e IS edge WHERE e.type = 'PART_OF']-{1,3} (t_ IS node)" in sql, sql)
        assertFalse("eid" in sql || "eprops" in sql, sql)
        assertTrue("COLUMNS (n_.nid AS src, t_.nid, " in sql, sql)
    }

    @Test
    fun `several steps name each relationship and each node between apart, a line to a step, and only the last node is the one reached`() {
        val sql = rows.reached(
            "claim_",
            listOf(
                Step("MENTIONS", Direction.OUTGOING, listOf("Human")),
                Step("WORKS_AT", Direction.UNDIRECTED, emptyList()),
                Step("OWNED_BY", Direction.INCOMING, listOf("Company")),
            ),
            "t_",
        ).whole()

        assertTrue(
            ("    MATCH (claim_ IS node)\n" +
                "        -[e1 IS edge WHERE e1.type = 'MENTIONS']-> (m1 IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(m1.labels), ']'), '[') || ',\"', ',\"Human\",\"') > 0)\n" +
                "        -[e2 IS edge WHERE e2.type = 'WORKS_AT']- (m2 IS node)\n" +
                "        <-[e3 IS edge WHERE e3.type = 'OWNED_BY']- (t_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(t_.labels), ']'), '[') || ',\"', ',\"Company\",\"') > 0)\n" +
                "    COLUMNS (claim_.nid AS src, t_.nid, ") in sql,
            sql,
        )
        assertFalse("eid" in sql, sql)
    }

    @Test
    fun `a type and a label are data, so a quote in either is escaped where it is written`() {
        val sql = rows.reached("n_", listOf(Step("IT'S", Direction.OUTGOING, listOf("O'Brien \"the\" label"))), "t_").whole()

        assertTrue("e.type = 'IT''S'" in sql, sql)
        assertTrue("',\"O''Brien \\\"the\\\" label\",\"'" in sql, sql)
    }

    @Test
    fun `a traversal gives only the columns the query reads of it`() {
        val reached = rows.reached("claim_", listOf(Step("MENTIONS", Direction.OUTGOING, emptyList())), "people_")

        assertTrue(reached.reading(listOf("people_.doc", "people_.src = claim_.nid")).endsWith("COLUMNS (claim_.nid AS src, people_.doc)) people_"))
        assertTrue(reached.reading(listOf("JSON_TRANSFORM(people_.eprops, SET '$.t' = people_.doc)")).endsWith("COLUMNS (e.props AS eprops, people_.doc)) people_"))
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

    // ----- Related, and whether any is -----

    @Test
    fun `the nodes related to a row are those the traversal reaches from it, joined to the row outside the traversal`() {
        val query = rows.related("people_.doc", "claim_", listOf(Step("MENTIONS", Direction.OUTGOING, listOf("Human"))), "people_", tail = listOf("ORDER BY people_.eid")).text()

        assertEquals(
            """
            SELECT people_.doc
            FROM GRAPH_TABLE (g
                MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(people_.labels), ']'), '[') || ',"', ',"Human","') > 0)
                COLUMNS (claim_.nid AS src, e.eid, people_.doc)) people_
            WHERE people_.src = claim_.nid
            ORDER BY people_.eid
            """.trimIndent(),
            query,
        )
    }

    @Test
    fun `whether a node is reached is asked of the related nodes the pattern finds to meet the test, beside their labels`() {
        // In the pattern and not on its rows: see OracleRowSources.exists for what the other does to Oracle.
        val sql = rows.exists(
            "claim_", listOf(Step("MENTIONS", Direction.OUTGOING, listOf("Human"))), "people_",
            where = listOf("JSON_VALUE(people_.props, '$.name') = \$_p0", "JSON_VALUE(people_.props, '$.id') = \$_p1"),
        )

        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM GRAPH_TABLE (g
                    MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(people_.labels), ']'), '[') || ',"', ',"Human","') > 0 AND JSON_VALUE(people_.props, '$.name') = ${'$'}_p0 AND JSON_VALUE(people_.props, '$.id') = ${'$'}_p1)
                    COLUMNS (claim_.nid AS src)) people_
                WHERE people_.src = claim_.nid)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `a predicate the pattern cannot hold is asked of the rows it gives, and the row carries what it reads`() {
        val sql = rows.exists(
            "claim_", listOf(Step("MENTIONS", Direction.OUTGOING, emptyList())), "people_",
            where = listOf("JSON_VALUE(people_.props, '$.name') = \$_p0"), predicates = listOf("EXISTS (SELECT 1 FROM other o WHERE o.id = people_.nid)"),
        )

        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM GRAPH_TABLE (g
                    MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node WHERE JSON_VALUE(people_.props, '$.name') = ${'$'}_p0)
                    COLUMNS (claim_.nid AS src, people_.nid)) people_
                WHERE people_.src = claim_.nid
                  AND EXISTS (SELECT 1 FROM other o WHERE o.id = people_.nid))
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `a test of the node a path ends at is on that node, and on none it passes through`() {
        val sql = rows.reached(
            "n_", listOf(Step("WORKS_AT", Direction.OUTGOING, listOf("Company")), Step("IN", Direction.OUTGOING, emptyList())), "t_",
            where = listOf("JSON_VALUE(t_.props, '$.name') = \$_p0"),
        ).whole()

        assertTrue("(m1 IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(m1.labels), ']'), '[') || ',\"', ',\"Company\",\"') > 0)" in sql, sql)
        assertTrue("(t_ IS node WHERE JSON_VALUE(t_.props, '$.name') = \$_p0)" in sql, sql)
    }

    @Test
    fun `with no predicate, whether a node is reached reads nothing of it but where it was reached from`() {
        val sql = rows.exists("n_", listOf(Step("MENTIONS", Direction.OUTGOING, emptyList())), "t_")

        assertTrue(sql.endsWith("COLUMNS (n_.nid AS src)) t_\n    WHERE t_.src = n_.nid)"), sql)
    }
}
