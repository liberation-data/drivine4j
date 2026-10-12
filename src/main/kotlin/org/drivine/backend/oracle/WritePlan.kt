package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.model.Stamps

/** How a block finds a node: the labels it carries and the property its id is stored under. */
internal data class NodeShape(val labels: List<String>, val key: String)

/**
 * What a `node` step writes to a node, beyond its properties: see [WritePlan.node].
 *
 * @param set the properties to write, by the names they are stored under, and [unset] those to remove
 * @param addLabels the labels to give the node, and [dropLabels] those to take from it
 * @param bags the property bags whose stale stored keys the block clears, for a root saved under `CLEAR` without a digest
 * @param owned how the labels an open `@NodeLabels` field holds are recorded on the node, when it has one
 */
internal class NodeWrites(
    val set: Map<String, Any?> = emptyMap(),
    val unset: List<String> = emptyList(),
    val bags: List<BagClearing> = emptyList(),
    val addLabels: List<String> = emptyList(),
    val dropLabels: List<String> = emptyList(),
    val owned: OwnedLabels? = null,
)

/** A property bag whose stored keys the block clears when the object no longer holds them: see `OracleWriteBlock`'s `owns`. */
internal class BagClearing(val prefix: String?, val flat: Boolean, val keep: List<String>, val reservedKeys: List<String>, val reservedPrefixes: List<String>) {
    fun toMap(): Map<String, Any?> = mapOf("prefix" to prefix, "flat" to flat, "keep" to keep, "reservedKeys" to reservedKeys, "reservedPrefixes" to reservedPrefixes)
}

/**
 * The labels an open `@NodeLabels` field owns, recorded on the node under [property]. With [clear],
 * the block drops those recorded and no longer held, keeping the [base] labels of the class.
 */
internal class OwnedLabels(val property: String, val clear: Boolean = false, val base: List<String> = emptyList()) {
    fun toMap(): Map<String, Any?> = if (clear) mapOf("prop" to property, "clear" to true, "base" to base) else mapOf("prop" to property)
}

/**
 * What a `node` step says about the save's root, and about no other node.
 *
 * @param carried the stamp the object carries, which the stamp handed back is composed from
 * @param expect the stamp, or its node token, the node must carry for the save to go ahead
 * @param whole whether [expect] is the whole stamp rather than its node token
 * @param creates whether the step makes the node when it is not there; an update does not
 */
internal class RootStep(val carried: String?, val expect: String?, val whole: Boolean, val creates: Boolean)

/**
 * The steps a write block carries out, gathered as they are decided. A node a step saves or finds is
 * known to later steps by the number it is given here. Each kind of step is a method, and the names
 * the block reads a step's fields by are spelt here and nowhere else.
 */
internal class WritePlan(private val graph: OracleGraph) {
    private val steps = mutableListOf<Map<String, Any?>>()
    private val shapes = linkedMapOf<NodeShape, Int>()
    private var nodes = 0

    /** The stamp the plan's steps take the relationship token from. */
    val mark: String = Stamps.fresh()

    private val stamps = mutableListOf(mark)

    /** The stamps the plan offers the nodes it changes: [mark], and the one each node step carries. */
    val offered: List<String> get() = stamps

    /** The number of the shape a node of [labels], found by the property [key], has: one number for one shape. */
    fun shape(labels: List<String>, key: String): Int = shapes.getOrPut(NodeShape(labels, key)) { shapes.size }

    /**
     * The step that saves the node of [shape] whose id is [id], stored under [key] as the text [storedId]:
     * found and locked, or made with [labels]. Gives the number later steps know the node by.
     *
     * @param index the index the node's stamp is returned under, when it is
     * @param root what the step says about the save's root; null for any other node
     */
    fun node(shape: Int, key: String, id: Any, storedId: String, labels: List<String>, writes: NodeWrites, index: String? = null, root: RootStep? = null): Int {
        val node = nodes++
        add(
            "node", "r" to node, "s" to shape, "k" to storedId, "key" to key, "id" to id, "labels" to labels.sorted(), "fresh" to fresh(),
            "set" to writes.set.takeIf { it.isNotEmpty() }, "unset" to writes.unset.takeIf { it.isNotEmpty() },
            "bags" to writes.bags.map { it.toMap() }.takeIf { it.isNotEmpty() },
            "add" to writes.addLabels.takeIf { it.isNotEmpty() }, "drop" to writes.dropLabels.takeIf { it.isNotEmpty() }, "owned" to writes.owned?.toMap(),
            "i" to index,
            "root" to root?.let { true }, "carried" to root?.carried, "expect" to root?.expect,
            "whole" to root?.whole?.takeIf { it }, "create" to root?.creates?.takeIf { !it },
        )
        return node
    }

    /** The step that finds the node of [shape] whose stored id is [storedId], which must be there. Gives the number later steps know it by. */
    fun ref(shape: Int, storedId: String): Int {
        val node = nodes++
        add("ref", "r" to node, "s" to shape, "k" to storedId)
        return node
    }

    /**
     * The step that joins [from] to [to] with a relationship of [type] in [direction], as it says and
     * once, unless [always] makes one more. [clear] names the properties the relationship must not have.
     */
    fun link(from: Int, to: Int, type: String, direction: Direction, properties: Map<String, Any?>, clear: List<String> = emptyList(), always: Boolean = false) =
        add(
            "link", "from" to from, "to" to to, "type" to type, "dir" to direction.code,
            "props" to properties.takeIf { it.isNotEmpty() }, "clear" to clear.takeIf { it.isNotEmpty() }, "always" to always.takeIf { it },
        )

    /**
     * The step that removes the relationships of [type] in [direction] from [from] to the nodes of
     * [shape] whose stored ids are [ids], or with [keep] to every one but those. With [delete], a node
     * left with no relationship on the field's side goes too, unless its id is among [held].
     */
    fun unlink(from: Int, type: String, direction: Direction, shape: Int, ids: List<String>, keep: Boolean, delete: Boolean, held: List<String>) =
        add(
            "unlink", "from" to from, "type" to type, "dir" to direction.code, "s" to shape, "ids" to ids,
            "keep" to keep.takeIf { it }, "delete" to delete.takeIf { it }, "held" to held.takeIf { it.isNotEmpty() },
        )

    /** The step that removes the relationships of [type] in [direction] from [from] to [to], or with no [to] to every node. */
    fun cut(from: Int, to: Int?, type: String, direction: Direction) =
        add("cut", "from" to from, "to" to to, "type" to type, "dir" to direction.code)

    /** The block that carries the steps out and gives back what [returns] says. */
    fun text(returns: OracleWriteBlock.Returns): String = OracleWriteBlock(graph, shapes.keys.toList(), returns).text()

    /** The values of the block's parameters: the steps as JSON, and the mark. */
    fun bindings(): Map<String, Any?> = mapOf(OracleWriteBlock.PLAN to steps, OracleWriteBlock.MARK to mark)

    /** A stamp for a node step, which is offered to its node. */
    private fun fresh(): String = Stamps.fresh().also { stamps += it }

    /** Adds a step of [kind] with [fields]. A field that is null is left out: the block reads an absent field as null. */
    private fun add(kind: String, vararg fields: Pair<String, Any?>) {
        steps += (listOf("op" to kind) + fields.filter { it.second != null }).toMap()
    }
}

/** The direction as a step writes it. */
internal val Direction.code: String
    get() = when (this) {
        Direction.OUTGOING -> "OUT"
        Direction.INCOMING -> "IN"
        Direction.UNDIRECTED -> "BOTH"
    }
