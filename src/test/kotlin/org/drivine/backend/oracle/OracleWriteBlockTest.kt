package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.model.Stamps
import org.drivine.query.SqlParameters
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The write plan's steps as the block reads them, and the block's text. */
class OracleWriteBlockTest {

    private val graph = OracleGraph("g")

    @Suppress("UNCHECKED_CAST")
    private fun steps(plan: WritePlan): List<Map<String, Any?>> = plan.bindings()[OracleWriteBlock.PLAN] as List<Map<String, Any?>>

    // Steps

    @Test
    fun `a ref step finds a node of a shape by its stored id, and gives it the next number`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        val node = plan.node(shape, "id", "ada", "ada", listOf("Human"), NodeWrites())
        val ref = plan.ref(shape, "bob")

        assertEquals(0, node)
        assertEquals(1, ref)
        assertEquals(mapOf("op" to "ref", "r" to 1, "s" to shape, "k" to "bob"), steps(plan).last())
    }

    @Test
    fun `a link step says always only when it makes one more relationship`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        val a = plan.ref(shape, "a")
        val b = plan.ref(shape, "b")
        plan.link(a, b, "KNOWS", Direction.OUTGOING, emptyMap())
        plan.link(a, b, "KNOWS", Direction.OUTGOING, mapOf("since" to 2020), always = true)

        val (merging, creating) = steps(plan).filter { it["op"] == "link" }
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "KNOWS", "dir" to "OUT"), merging)
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "KNOWS", "dir" to "OUT", "props" to mapOf("since" to 2020), "always" to true), creating)
    }

    @Test
    fun `a cut step names the other node only when there is one`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        val a = plan.ref(shape, "a")
        val b = plan.ref(shape, "b")
        plan.cut(a, b, "KNOWS", Direction.INCOMING)
        plan.cut(a, null, "KNOWS", Direction.UNDIRECTED)

        val (one, all) = steps(plan).filter { it["op"] == "cut" }
        assertEquals(mapOf("op" to "cut", "from" to 0, "to" to 1, "type" to "KNOWS", "dir" to "IN"), one)
        assertEquals(mapOf("op" to "cut", "from" to 0, "type" to "KNOWS", "dir" to "BOTH"), all)
        assertFalse("to" in all)
    }

    @Test
    fun `a shape is one number for one pair of labels and key, and the next for another`() {
        val plan = WritePlan(graph)

        assertEquals(0, plan.shape(listOf("Human"), "id"))
        assertEquals(0, plan.shape(listOf("Human"), "id"))
        assertEquals(1, plan.shape(listOf("Human"), "key"))
        assertEquals(2, plan.shape(listOf("Human", "Manager"), "id"))
        assertEquals(1, plan.shape(listOf("Human"), "key"))
    }

    @Test
    fun `the plan offers its mark and one fresh stamp for each node step, in the order the steps are added`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        plan.node(shape, "id", "a", "a", listOf("Human"), NodeWrites())
        plan.ref(shape, "b")
        plan.node(shape, "id", "c", "c", listOf("Human"), NodeWrites())

        val fresh = steps(plan).filter { it["op"] == "node" }.map { it["fresh"] }
        assertEquals(listOf(plan.mark) + fresh, plan.offered)
        assertEquals(3, plan.offered.distinct().size)
        plan.offered.forEach { assertTrue(Stamps.isTwoTokens(it), it) }
    }

    @Test
    fun `a node step lays out its writes under the names the block reads, leaving out what is empty`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        plan.node(
            shape, "id", 7L, "7", listOf("Human"),
            NodeWrites(set = mapOf("name" to "Ada"), unset = listOf("note"), addLabels = listOf("Vip"), dropLabels = listOf("Old"), owned = OwnedLabels("__labels")),
            index = "0",
        )
        plan.node(shape, "id", "b", "b", listOf("Human"), NodeWrites())

        val (full, bare) = steps(plan)
        assertEquals(
            mapOf(
                "op" to "node", "r" to 0, "s" to shape, "k" to "7", "key" to "id", "id" to 7L, "labels" to listOf("Human"), "fresh" to full["fresh"],
                "set" to mapOf("name" to "Ada"), "unset" to listOf("note"), "add" to listOf("Vip"), "drop" to listOf("Old"), "owned" to mapOf("prop" to "__labels"), "i" to "0",
            ),
            full,
        )
        assertEquals(setOf("op", "r", "s", "k", "key", "id", "labels", "fresh"), bare.keys)
    }

    @Test
    fun `a patch step names a node by its shape and stored id, with the index it is returned under, and takes no number`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        plan.patch(shape, "7", "3", set = mapOf("name" to "Ada"), unset = listOf("note"))
        plan.patch(shape, "8", "4", set = emptyMap(), unset = emptyList())
        val ref = plan.ref(shape, "b")

        val (full, bare) = steps(plan)
        assertEquals(
            mapOf("op" to "patch", "s" to shape, "k" to "7", "i" to "3", "fresh" to full["fresh"], "set" to mapOf("name" to "Ada"), "unset" to listOf("note")),
            full,
        )
        assertEquals(setOf("op", "s", "k", "i", "fresh"), bare.keys)
        assertEquals(0, ref)
        assertEquals(listOf(plan.mark, full["fresh"], bare["fresh"]), plan.offered)
    }

    // The block's text

    @Test
    fun `the block has two parameters, the plan and the mark, whatever it returns`() {
        val plan = WritePlan(graph)
        plan.node(plan.shape(listOf("Claim"), "id"), "id", "c", "c", listOf("Claim"), NodeWrites())
        plan.node(plan.shape(listOf("Human"), "a.b"), "a.b", "h", "h", listOf("Human"), NodeWrites())

        OracleWriteBlock.Returns.entries.forEach { returns ->
            assertEquals(listOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK), SqlParameters.positional(plan.text(returns)).names, returns.name)
        }
    }

    @Test
    fun `the block finds a node of each shape by one arm of its case, and its text is the same however many nodes a shape has`() {
        fun plan(count: Int) = WritePlan(graph).apply {
            val claim = shape(listOf("Claim"), "id")
            val human = shape(listOf("Human"), "id")
            repeat(count) { node(claim, "id", "c$it", "c$it", listOf("Claim"), NodeWrites()) }
            node(human, "id", "h", "h", listOf("Human"), NodeWrites())
        }
        val text = plan(1).text(OracleWriteBlock.Returns.STAMPS)

        val finding = text.substringAfter("PROCEDURE find(").substringBefore("END CASE;")
        assertEquals(listOf("0", "1"), Regex("WHEN (\\d+) THEN").findAll(finding).map { it.groupValues[1] }.toList())
        assertEquals(text, plan(50).text(OracleWriteBlock.Returns.STAMPS))
    }

    @Test
    fun `each way of returning ends the block with its own row`() {
        val plan = WritePlan(graph)
        plan.node(plan.shape(listOf("Claim"), "id"), "id", "c", "c", listOf("Claim"), NodeWrites())

        val count = plan.text(OracleWriteBlock.Returns.COUNT)
        assertTrue(count.trimEnd().endsWith("OPEN v_rows FOR SELECT v_count FROM dual;\n    DBMS_SQL.RETURN_RESULT(v_rows);\nEND;"), count.takeLast(120))
        assertContains(plan.text(OracleWriteBlock.Returns.EACH_STAMP), "JSON_TABLE(v_result")
        assertContains(plan.text(OracleWriteBlock.Returns.STAMPS), "SELECT v_result FROM dual WHERE v_result IS NOT NULL")
        assertContains(count, "SELECT v_count FROM dual")
        assertFalse("#NODES#" in plan.text(OracleWriteBlock.Returns.STAMPS), "the node table stands in for its placeholder")
        assertContains(plan.text(OracleWriteBlock.Returns.STAMPS), "FROM g_node WHERE nid = v_root")
    }

    @Test
    fun `each kind of step has one arm of the block's dispatch, to a procedure the block declares`() {
        val plan = WritePlan(graph)
        plan.shape(listOf("Claim"), "id")
        val text = plan.text(OracleWriteBlock.Returns.COUNT)

        val dispatch = text.substringAfter("CASE v_op.get_string('op')").substringBefore("END CASE;")
        val arms = Regex("WHEN '(\\w+)' THEN (\\w+)\\(v_op\\);").findAll(dispatch).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(StepKind.entries.map { it.op to it.procedure }, arms)
        assertEquals(arms.size, arms.map { it.first }.distinct().size, "no two kinds share a name")
        StepKind.entries.forEach { assertContains(text, "    PROCEDURE ${it.procedure}(op JSON_OBJECT_T) IS\n") }
    }

    @Test
    fun `a procedure is declared before the one that calls it, as PL-SQL asks`() {
        val plan = WritePlan(graph)
        plan.shape(listOf("Claim"), "id")
        // What is said in a comment is not a call.
        val text = plan.text(OracleWriteBlock.Returns.STAMPS).lines().joinToString("\n") { it.substringBefore("--") }

        val declared = Regex("(?m)^    (?:PROCEDURE|FUNCTION) (\\w+)").findAll(text).map { it.groupValues[1] to it.groups[1]!!.range.first }.toList()
        assertEquals(declared.size, declared.map { it.first }.distinct().size, "each is declared once: ${declared.map { it.first }}")
        assertTrue(declared.map { it.first }.containsAll(listOf("find", "save_node", "write_props", "clear_bags", "own_labels", "relabel", "relink_marked")), declared.toString())
        declared.forEach { (name, at) ->
            val calls = Regex("(?<![\\w.'])$name\\s*[(;]").findAll(text).map { it.range.first }.filter { it != at }.toList()
            assertTrue(calls.isNotEmpty(), "$name is declared and never called")
            assertTrue(calls.all { it > at }, "$name is called before it is declared")
        }
    }

    @Test
    fun `the stamps are gathered once every marked node has its relationship token, and not for a save that was refused`() {
        val plan = WritePlan(graph)
        plan.shape(listOf("Claim"), "id")

        listOf(OracleWriteBlock.Returns.STAMPS, OracleWriteBlock.Returns.EACH_STAMP).forEach { returns ->
            val main = plan.text(returns).substringAfter("\nBEGIN\n    BEGIN\n")
            val relinked = main.indexOf("relink_marked;")
            val gathered = main.indexOf("SELECT stamp INTO v_stamp FROM g_node")
            val refused = main.indexOf("EXCEPTION")
            assertTrue(relinked in 0 until gathered && gathered < refused, "${returns.name}: $relinked, $gathered, $refused")
        }
        val counting = plan.text(OracleWriteBlock.Returns.COUNT).substringAfter("\nBEGIN\n    BEGIN\n")
        assertFalse("INTO v_stamp" in counting, "a count reads no stamp")
    }

    @Test
    fun `a stamp that is not two tokens is read as none, by the step that saves a node and by the one that marks it`() {
        val plan = WritePlan(graph)
        plan.shape(listOf("Claim"), "id")
        val text = plan.text(OracleWriteBlock.Returns.STAMPS)

        assertContains(text, "v_two := CASE WHEN LENGTH(v_stamp) = 33 AND SUBSTR(v_stamp, 17, 1) = ':' THEN v_stamp END;")
        assertContains(text, "SET stamp = NVL(SUBSTR(CASE WHEN LENGTH(stamp) = 33 AND SUBSTR(stamp, 17, 1) = ':' THEN stamp END, 1, 17), SUBSTR(v_mark, 1, 17)) || SUBSTR(v_mark, 18)")
        // The positions are those of the model's stamp: two tokens and the colon between them.
        assertEquals(33, Stamps.fresh().length)
        assertEquals(16, Stamps.nodeToken(Stamps.fresh()).length)
    }

    // What a save hands back

    @Test
    fun `a step that only makes its node refuses one it finds, before it writes, and a node another writer made is found again`() {
        val plan = WritePlan(graph)
        plan.node(plan.shape(listOf("Human"), "id"), "id", "ada", "ada", listOf("Human"), NodeWrites())
        val text = plan.text(OracleWriteBlock.Returns.STAMPS)
        val step = text.substringAfter("find(op.get_number('s'), op.get_string('k'), v_nid, v_stamp, v_labels, v_props);\n            v_made")

        val absent = step.indexOf("op.get_boolean('absent')")
        assertTrue(absent in 0 until step.indexOf("write_props(op, v_o);"), "the refusal comes before anything is written to the node")
        assertContains(step.substringAfter("op.get_boolean('absent')").substringBefore("END IF;"), "RAISE e_stale;")
        // A uniqueness constraint that refused the insert sends the step round the loop once more, to
        // find the other writer's node and so refuse it.
        assertContains(step.substringAfter("DUP_VAL_ON_INDEX"), "IF v_dup > 1 THEN RAISE; END IF;")
    }

    @Test
    fun `a made root whose object carried a stamp without a relationship token is handed the new one, as the block and the model agree`() {
        val plan = WritePlan(graph)
        plan.node(plan.shape(listOf("Claim"), "id"), "id", "c", "c", listOf("Claim"), NodeWrites())

        assertContains(plan.text(OracleWriteBlock.Returns.STAMPS), "(v_root_made AND SUBSTR(v_carried, 18) IS NULL)")
        val now = Stamps.fresh()
        val handed = Stamps.handedBack(carried = "X:", found = "", now = now)
        assertEquals("X:" + Stamps.linksToken(now), handed)
        assertTrue(handed.endsWith(Stamps.linksToken(now)!!))
    }
}
