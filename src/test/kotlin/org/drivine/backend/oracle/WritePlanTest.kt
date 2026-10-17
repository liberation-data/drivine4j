package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.query.RootMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The steps of a write plan that `OracleWriteBlockTest` does not lay out: a root's, an unlink's, and what a node step clears. */
class WritePlanTest {

    private val graph = OracleGraph("g")

    @Suppress("UNCHECKED_CAST")
    private fun steps(plan: WritePlan): List<Map<String, Any?>> = plan.bindings()[OracleWriteBlock.PLAN] as List<Map<String, Any?>>

    /** What a node step says beyond the fields every node step has. */
    private fun Map<String, Any?>.said(): Map<String, Any?> = this - setOf("op", "r", "s", "k", "key", "id", "labels", "fresh")

    private fun root(plan: WritePlan, root: RootStep?): Map<String, Any?> {
        plan.node(plan.shape(listOf("Claim"), "id"), "id", "c1", "c1", listOf("Claim"), NodeWrites(), root = root)
        return steps(plan).last().said()
    }

    // ----- The root -----

    @Test
    fun `a root that is checked whole and may be made says so, and one checked by its node token alone does not say whole`() {
        val whole = root(WritePlan(graph), RootStep(carried = "n:l", expect = "n:l", whole = true, mode = RootMode.MERGE))
        val token = root(WritePlan(graph), RootStep(carried = "n:l", expect = "n", whole = false, mode = RootMode.MERGE))

        assertEquals(mapOf<String, Any?>("root" to true, "carried" to "n:l", "expect" to "n:l", "whole" to true), whole)
        assertEquals(mapOf<String, Any?>("root" to true, "carried" to "n:l", "expect" to "n"), token)
    }

    @Test
    fun `an update says the root is not made, which is the one thing a root says by a false`() {
        val update = root(WritePlan(graph), RootStep(carried = null, expect = null, whole = false, mode = RootMode.FOUND))

        // The block reads an absent `create` as null and makes the node: only a false keeps a node that is gone, gone.
        assertEquals(mapOf<String, Any?>("root" to true, "create" to false), update)
    }

    @Test
    fun `a create says the root is not written when it is there, by a true`() {
        val create = root(WritePlan(graph), RootStep(carried = null, expect = null, whole = false, mode = RootMode.ABSENT))

        assertEquals(mapOf<String, Any?>("root" to true, "absent" to true), create)
    }

    @Test
    fun `a root that carries no stamp and expects none says only that it is the root, and any other node says nothing of it`() {
        assertEquals(mapOf<String, Any?>("root" to true), root(WritePlan(graph), RootStep(carried = null, expect = null, whole = false, mode = RootMode.MERGE)))
        assertEquals(emptyMap(), root(WritePlan(graph), null))
    }

    // ----- What a node step writes -----

    @Test
    fun `a node's labels are written sorted, as the store keeps them, whatever order the model gives them in`() {
        val plan = WritePlan(graph)
        plan.node(plan.shape(listOf("Zebra", "Animal"), "id"), "id", "z", "z", listOf("Zebra", "Animal", "Mammal"), NodeWrites())

        assertEquals(listOf("Animal", "Mammal", "Zebra"), steps(plan).single()["labels"])
    }

    @Test
    fun `a property set to null is set, and is not left out as an empty map is`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Claim"), "id")
        plan.node(shape, "id", "c1", "c1", listOf("Claim"), NodeWrites(set = mapOf("note" to null)))
        plan.node(shape, "id", "c2", "c2", listOf("Claim"), NodeWrites(set = emptyMap()))

        val (clearing, bare) = steps(plan)
        assertEquals(mapOf<String, Any?>("set" to mapOf("note" to null)), clearing.said())
        assertFalse("set" in bare)
    }

    @Test
    fun `the bags a root clears are laid out one map each, a bag with no prefix keeping its null`() {
        val plan = WritePlan(graph)
        plan.node(
            plan.shape(listOf("Claim"), "id"), "id", "c1", "c1", listOf("Claim"),
            NodeWrites(
                bags = listOf(
                    BagClearing("metadata", flat = false, keep = listOf("metadata.a"), reservedKeys = emptyList(), reservedPrefixes = emptyList()),
                    BagClearing(null, flat = true, keep = emptyList(), reservedKeys = listOf("id", "text"), reservedPrefixes = listOf("metadata.")),
                ),
            ),
        )

        assertEquals(
            listOf(
                mapOf("prefix" to "metadata", "flat" to false, "keep" to listOf("metadata.a"), "reservedKeys" to emptyList<String>(), "reservedPrefixes" to emptyList<String>()),
                mapOf("prefix" to null, "flat" to true, "keep" to emptyList<String>(), "reservedKeys" to listOf("id", "text"), "reservedPrefixes" to listOf("metadata.")),
            ),
            steps(plan).single()["bags"],
        )
    }

    @Test
    fun `owned labels that are cleared name the labels of the class to keep, and those only recorded name the property alone`() {
        assertEquals(mapOf<String, Any?>("prop" to "__labels"), OwnedLabels("__labels").toMap())
        assertEquals(mapOf<String, Any?>("prop" to "__labels"), OwnedLabels("__labels", clear = false, base = listOf("Thing")).toMap())
        assertEquals(mapOf("prop" to "__labels", "clear" to true, "base" to listOf("Thing")), OwnedLabels("__labels", clear = true, base = listOf("Thing")).toMap())
        assertEquals(mapOf("prop" to "__labels", "clear" to true, "base" to emptyList<String>()), OwnedLabels("__labels", clear = true).toMap())
    }

    // ----- Unlink -----

    @Test
    fun `an unlink that removes the relationships to the nodes it names says nothing more`() {
        val plan = WritePlan(graph)
        val claims = plan.shape(listOf("Claim"), "id")
        val humans = plan.shape(listOf("Human"), "id")
        val from = plan.ref(claims, "c1")
        plan.unlink(from, "MENTIONS", Direction.OUTGOING, humans, listOf("h1", "h2"), keep = false, delete = false, held = emptyList())

        assertEquals(mapOf("op" to "unlink", "from" to 0, "type" to "MENTIONS", "dir" to "OUT", "s" to humans, "ids" to listOf("h1", "h2")), steps(plan).last())
    }

    @Test
    fun `an unlink that keeps none still names its empty list, for with keep that is every relationship of the field`() {
        val plan = WritePlan(graph)
        val from = plan.ref(plan.shape(listOf("Claim"), "id"), "c1")
        val humans = plan.shape(listOf("Human"), "id")
        plan.unlink(from, "MENTIONS", Direction.INCOMING, humans, emptyList(), keep = true, delete = true, held = listOf("h9"))

        assertEquals(
            mapOf("op" to "unlink", "from" to 0, "type" to "MENTIONS", "dir" to "IN", "s" to humans, "ids" to emptyList<String>(), "keep" to true, "delete" to true, "held" to listOf("h9")),
            steps(plan).last(),
        )
    }

    // ----- The plan as a whole -----

    @Test
    fun `the steps are bound in the order they were added, each node numbered as it comes, a ref among them`() {
        val plan = WritePlan(graph)
        val shape = plan.shape(listOf("Human"), "id")
        val a = plan.node(shape, "id", "a", "a", listOf("Human"), NodeWrites())
        val b = plan.ref(shape, "b")
        plan.link(a, b, "KNOWS", Direction.UNDIRECTED, emptyMap(), clear = listOf("since"))
        val c = plan.node(shape, "id", "c", "c", listOf("Human"), NodeWrites())
        plan.cut(c, null, "KNOWS", Direction.OUTGOING)

        assertEquals(listOf("node", "ref", "link", "node", "cut"), steps(plan).map { it["op"] })
        assertEquals(listOf(0, 1, 2), listOf(a, b, c))
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "KNOWS", "dir" to "BOTH", "clear" to listOf("since")), steps(plan)[2])
    }

    @Test
    fun `the plan is bound with its mark, which is its own and not another plan's`() {
        val plan = WritePlan(graph)

        assertEquals(setOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK), plan.bindings().keys)
        assertEquals(plan.mark, plan.bindings()[OracleWriteBlock.MARK])
        assertNotEquals(plan.mark, WritePlan(graph).mark)
        assertTrue(steps(plan).isEmpty())
    }

    @Test
    fun `a direction is written as the block reads it`() {
        assertEquals(listOf("OUT", "IN", "BOTH"), listOf(Direction.OUTGOING, Direction.INCOMING, Direction.UNDIRECTED).map { it.code })
    }
}
