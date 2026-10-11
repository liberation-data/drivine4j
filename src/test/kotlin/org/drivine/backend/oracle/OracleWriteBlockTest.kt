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

    // What a save hands back

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
