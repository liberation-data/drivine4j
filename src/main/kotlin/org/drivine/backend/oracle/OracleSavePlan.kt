package org.drivine.backend.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.NodeKey
import org.drivine.query.Repeat
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.drivine.query.read

/**
 * The steps a write block carries out, gathered as they are decided. A node a step saves or finds is
 * known to later steps by the number it is given here.
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

    /** A stamp for a node step, which is offered to its node. */
    fun fresh(): String = Stamps.fresh().also { stamps += it }

    /** The number of the shape a node of [labels], found by the property [key], has: one number for one shape. */
    fun shape(labels: List<String>, key: String): Int = shapes.getOrPut(NodeShape(labels, key)) { shapes.size }

    /** A number for the node the next `node` or `ref` step gives. */
    fun node(): Int = nodes++

    /** Adds a step of [kind] with [fields]. A field that is null is left out: the block reads an absent field as null. */
    fun add(kind: String, vararg fields: Pair<String, Any?>) {
        steps += (listOf("op" to kind) + fields.filter { it.second != null }).toMap()
    }

    /** The step that joins [from] to [to]; [clear] names the properties the relationship must not have. */
    fun link(from: Int, to: Int, type: String, direction: Direction, properties: Map<String, Any?>, clear: List<String> = emptyList(), always: Boolean = false) =
        add(
            "link", "from" to from, "to" to to, "type" to type, "dir" to direction.code,
            "props" to properties.takeIf { it.isNotEmpty() }, "clear" to clear.takeIf { it.isNotEmpty() }, "always" to always.takeIf { it },
        )

    fun text(returns: OracleWriteBlock.Returns): String = OracleWriteBlock(graph, shapes.keys.toList(), returns).text()

    fun bindings(): Map<String, Any?> = mapOf(OracleWriteBlock.PLAN to steps, OracleWriteBlock.MARK to mark)
}

internal val Direction.code: String
    get() = when (this) {
        Direction.OUTGOING -> "OUT"
        Direction.INCOMING -> "IN"
        Direction.UNDIRECTED -> "BOTH"
    }

/**
 * Plans a whole save for Oracle: the root, the relationships it drops, each related node and the
 * relationship to it. It decides what `SaveStatementBuilder` decides for the graph engines, from the
 * same model, and writes it as steps for the write block where that writes Cypher.
 *
 * Every related node is a step of its own. The block's text does not grow with a list, so nothing is
 * gained by saving the nodes of a field together.
 */
internal class OracleSavePlan(private val graph: OracleGraph, private val objectMapper: ObjectMapper) {

    fun build(
        obj: Any,
        checked: Boolean,
        nullPolicy: NullPolicy,
        rootWriteFields: Set<String>?,
        replaced: (RelationshipModel) -> RemovedTargets?,
        before: JsonNode?,
        after: JsonNode?,
        createsRoot: Boolean,
    ): SaveStatement = Composition(checked, nullPolicy, rootWriteFields, replaced, createsRoot).compose(obj, before, after)

    /**
     * The statement that saves [fragments] together, unchecked, each a step of one plan. It returns a
     * row for each, `index/found=stamp`, under the index the fragment is given here.
     */
    fun together(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy): Pair<String, Map<String, Any?>> {
        val composition = Composition(checked = false, nullPolicy, rootWriteFields = null, replaced = { null }, createsRoot = true)
        fragments.forEach { (index, fragment) -> composition.fragment(fragment, index) }
        return composition.plan.text(OracleWriteBlock.Returns.EACH_STAMP) to composition.plan.bindings()
    }

    private inner class Composition(
        private val checked: Boolean,
        private val nullPolicy: NullPolicy,
        private val rootWriteFields: Set<String>?,
        private val replaced: (RelationshipModel) -> RemovedTargets?,
        private val createsRoot: Boolean,
    ) {
        val plan = WritePlan(graph)
        private val stamped = mutableListOf<Any>()
        private val carried = mutableListOf<String?>()
        private val keys = mutableListOf<NodeKey?>()
        private val repeats = mutableListOf<Repeat>()
        private lateinit var root: StampWrite

        fun compose(obj: Any, before: JsonNode?, after: JsonNode?): SaveStatement {
            if (obj.javaClass.isAnnotationPresent(GraphView::class.java)) {
                val model = GraphViewModel.from(obj.javaClass)
                // A save that replaces a list overwrites the root's relationships, so they are checked too.
                val replaces = before == null && model.relationships.any { !it.readOnly && replaced(it) != null }
                view(obj, model, 0, before, after, replaces)
            } else {
                rootNode(obj, FragmentModel.from(obj.javaClass), replaces = false, before)
            }
            return SaveStatement(
                plan.text(OracleWriteBlock.Returns.STAMPS), plan.bindings(), root, stamped, carried, keys, repeats, plan.offered.toList(), createsRoot,
            )
        }

        /** One fragment of a batch: saved as the root of a save on its own is, and returned under [index]. */
        fun fragment(fragment: Any, index: Int) {
            val model = FragmentModel.from(fragment.javaClass)
            node(fragment, model, objectMapper.toMap(fragment), null, nullPolicy, null, readsStore = true, "i" to index.toString())
        }

        /** The root of [view], then each relationship field it writes. Gives the root's number. */
        private fun view(view: Any, model: GraphViewModel, depth: Int, before: JsonNode?, after: JsonNode?, replaces: Boolean = false): Int {
            val rootFragment = requireNotNull(read(view, model.rootFragment.fieldName)) { "Root fragment ${model.rootFragment.fieldName} is null" }
            val rootModel = FragmentModel.from(model.rootFragment.fragmentType)
            val rootBefore = before?.get(model.rootFragment.fieldName)
            val rootNode = if (depth == 0) {
                rootNode(rootFragment, rootModel, replaces, rootBefore)
            } else {
                relatedNode(rootFragment, rootModel, rootBefore, after?.get(model.rootFragment.fieldName))
            }

            // A read-only field (every path is one) is loaded and never written.
            val written = model.relationships.filterNot { it.readOnly }.map { relationship ->
                val target = targetOf(relationship)
                val value = read(view, relationship.fieldName)
                Triple(relationship, target, if (relationship.isCollection) (value as? Collection<*>)?.toList().orEmpty() else listOf(value))
            }
            // Every node the object holds, in any field and in any view nested in one: none of them is
            // deleted as unreferenced, though the field it is dropped from holds the only relationship
            // the store has to it yet.
            val held = if (depth == 0) heldBy(view, model) else emptyList()
            // A save leaves a relationship's property alone where the object's is null, as it leaves a
            // related node's. A save of what a change altered clears one the change set to null.
            val clears = before != null
            written.forEach { (relationship, target, items) ->
                val ids = items.map { item -> item?.let { target.idOf(it) } }
                // A node the field holds more than once is written once, as the last of them says.
                val last = ids.withIndex().filter { it.value != null }.associate { it.value to it.index }
                val itemsNow = after?.let { digestItems(it.get(relationship.fieldName), relationship) }
                val itemsBefore = before?.let { digest ->
                    digestItems(digest.get(relationship.fieldName), relationship).filterNotNull()
                        .mapNotNull { item -> target.idOf(item)?.let { it to item } }.toMap()
                }

                if (itemsBefore != null) {
                    val gone = itemsBefore.keys - ids.filterNotNull().map { it.toString() }.toSet()
                    if (gone.isNotEmpty()) removal(relationship, target, rootNode, gone.toList(), keep = false, RemovedTargets.KEEP, emptyList())
                } else if (depth == 0) {
                    replaced(relationship)?.let { removedTargets ->
                        val heldIds = held.filter { target.matches(it) }.mapNotNull { idOf(it) }.distinct()
                        // Another field that reads the same relationships holds some of them: those are its to keep.
                        val siblingIds = written.filter { (other) -> other !== relationship && other.reads(relationship) }
                            .flatMap { (_, sibling, others) -> others.filterNotNull().map { sibling.fragmentOf(it) } }
                            .filter { target.matches(it) }.mapNotNull { idOf(it) }
                        removal(relationship, target, rootNode, (ids.filterNotNull() + siblingIds).distinct(), keep = true, removedTargets, heldIds)
                    }
                }

                items.forEachIndexed { index, item ->
                    if (item == null) return@forEachIndexed
                    if (last[ids[index]]?.let { it != index } == true) {
                        repeated(target.fragmentOf(item))
                        return@forEachIndexed
                    }
                    val was = ids[index]?.let { itemsBefore?.get(it.toString()) }
                    val now = itemsNow?.getOrNull(index)
                    // As it was when loaded: the relationship and its target are left alone.
                    if (was != null && was == now) return@forEachIndexed
                    // A relationship that was loaded is not made again: if another writer has removed it
                    // since, it stays removed. Its node is written, and it too if its properties changed.
                    val link = was == null || relationship.relationshipProperties.any { was.get(it) != now?.get(it) }
                    val node = target.nodeOf(item)
                    val nodeBefore = was?.let { target.nodeOf(it) }
                    val nodeNow = now?.let { target.nodeOf(it) }
                    val saved = if (target.view != null) {
                        view(node, target.view, depth + 1, nodeBefore, nodeNow)
                    } else {
                        // The runtime type, not the declared one: a subtype has labels of its own.
                        relatedNode(node, FragmentModel.from(node.javaClass), nodeBefore, nodeNow)
                    }
                    if (link) join(relationship, item, rootNode, saved, clears)
                }
            }
            return rootNode
        }

        /** The object's own root: checked against the stamp it carries, and the node every stamp is reported from. */
        private fun rootNode(fragment: Any, model: FragmentModel, replaces: Boolean, before: JsonNode?): Int {
            val values = objectMapper.toMap(fragment)
            val expected = model.stampField?.takeIf { checked }?.let { values[it] as? String }
            val carriedStamp = model.stampField?.let { values[it] as? String }
            val node = node(
                fragment, model, values, before, nullPolicy, rootWriteFields, readsStore = true,
                "root" to true, "carried" to carriedStamp,
                // The token for the node's own data is always compared; the whole stamp, and so the token
                // for its relationships too, when the save replaces a relationship list. A stamp with no
                // node token of its own, which no save leaves, is compared whole too.
                "expect" to expected?.let { if (whole(it, replaces)) it else Stamps.nodeToken(it) },
                "whole" to expected?.let { whole(it, replaces) }?.takeIf { it },
                // An update makes no node: one that is gone stays gone, and the block gives no row.
                "create" to false.takeIf { !createsRoot },
            )
            root = StampWrite(fragment.javaClass, idOf(model, values), model.labels.joinToString(":"), keyOf(model), expected, carriedStamp)
            return node
        }

        private fun whole(expected: String, replaces: Boolean): Boolean = replaces || !Stamps.hasNodeToken(expected)

        /**
         * Records [fragment] as one a field holds beside a later one for the same node. It is not
         * written, and is handed the stamp the node is left with as the one that is written is.
         */
        private fun repeated(fragment: Any) {
            val model = FragmentModel.from(fragment.javaClass)
            val stampField = model.stampField ?: return
            val values = objectMapper.toMap(fragment)
            val id = values[model.nodeIdField] ?: return
            repeats.add(Repeat(fragment, values[stampField] as? String, NodeKey(model.labels.toSet(), id)))
        }

        /** The fragments [view] holds through its relationship fields, and through those of each view nested in it. */
        private fun heldBy(view: Any, model: GraphViewModel): List<Any> =
            model.relationships.filterNot { it.readOnly }.flatMap { relationship ->
                val target = targetOf(relationship)
                val value = read(view, relationship.fieldName)
                val items = if (relationship.isCollection) (value as? Collection<*>)?.toList().orEmpty() else listOf(value)
                items.filterNotNull().flatMap { item ->
                    val nested = target.view?.let { heldBy(target.nodeOf(item), it) }.orEmpty()
                    listOf(target.fragmentOf(item)) + nested
                }
            }

        /** The id of [fragment], as its own class declares it. */
        private fun idOf(fragment: Any): Any? =
            FragmentModel.from(fragment.javaClass).nodeIdField?.let { objectMapper.toMap(fragment)[it] }

        /**
         * A node reached through a relationship. One that was loaded, as [before], and is now [after]
         * has the fields that differ written, and one set to null cleared: a field the change did not
         * touch is not written over. One that was not loaded is written whole, a null field left alone.
         */
        private fun relatedNode(fragment: Any, model: FragmentModel, before: JsonNode?, after: JsonNode?): Int {
            val values = objectMapper.toMap(fragment)
            // The index the node's stamp is returned under, when its fragment declares one.
            val index = model.stampField?.let {
                stamped.add(fragment)
                carried.add(values[it] as? String)
                keys.add(values[model.nodeIdField]?.let { id -> NodeKey(model.labels.toSet(), id) })
                (stamped.size - 1).toString()
            }
            val altered = altered(before, after)
            return if (altered == null) {
                node(fragment, model, values, null, NullPolicy.IGNORE, null, readsStore = false, "i" to index)
            } else {
                node(fragment, model, values, before, NullPolicy.CLEAR, altered, readsStore = false, "i" to index)
            }
        }

        /** The fields that differ between a fragment's digest as loaded and now; null when it was not loaded. */
        private fun altered(before: JsonNode?, after: JsonNode?): Set<String>? {
            if (before == null || after == null) return null
            val names = (before.fieldNames().asSequence() + after.fieldNames().asSequence()).toSet()
            return names.filter { before.get(it) != after.get(it) }.toSet()
        }

        private fun keyOf(model: FragmentModel): String = model.nodeIdProperty ?: requireNotNull(model.nodeIdField) {
            "Cannot save a fragment without a @NodeId field: ${model.className}"
        }

        private fun idOf(model: FragmentModel, values: Map<String, Any?>): Any =
            values[requireNotNull(model.nodeIdField) { "Cannot save a fragment without a @NodeId field: ${model.className}" }]
                ?: throw IllegalArgumentException("Cannot save a fragment with a null id: ${model.className}")

        /**
         * The step that saves one node: what `FragmentMergeBuilder` writes as a `MERGE` and its `SET`.
         *
         * Null handling is the policy's alone: under [NullPolicy.IGNORE] a null field is left as it is,
         * under [NullPolicy.CLEAR] it is removed. [writeFields] are the only fields the step may touch.
         *
         * @param previous the node's digest as it was loaded, which says what a property bag has dropped
         * @param readsStore whether, with no [previous], the block clears what the stored node has and
         *   the object does not: the stale keys of a bag, and the labels an open label field recorded.
         *   It does for a root, and not for a node reached through a relationship
         */
        private fun node(
            fragment: Any, model: FragmentModel, values: Map<String, Any?>, previous: JsonNode?, nullPolicy: NullPolicy,
            writeFields: Set<String>?, readsStore: Boolean, vararg more: Pair<String, Any?>,
        ): Int {
            val id = idOf(model, values)
            val key = keyOf(model)
            val clears = nullPolicy == NullPolicy.CLEAR
            fun writable(field: String) = writeFields == null || field in writeFields

            val set = linkedMapOf<String, Any?>()
            val unset = mutableListOf<String>()
            model.fields.filter { !it.stamp && it.name != model.nodeIdField && writable(it.name) }.forEach { field ->
                val value = values[field.name]
                if (value != null) set[field.propertyName] = value else if (clears) unset += field.propertyName
            }

            val bags = mutableListOf<Map<String, Any?>>()
            model.propertyBags.filter { writable(it.fieldName) }.forEach { bag ->
                val current = (values[bag.fieldName] as? Map<*, *>).orEmpty().mapKeys { it.key.toString() }
                current.forEach { (entry, value) ->
                    requireStorable(value, bag.storedKey(entry))
                    if (value == null && !clears) return@forEach
                    require(!bag.flat || bag.owns(entry)) {
                        "Flat @PropertyBag field '${bag.fieldName}' on ${model.clazz.simpleName} has an entry '$entry', which is the property " +
                            "of a declared field or of another bag. Both would write the same node property; remove the entry or rename the field's property."
                    }
                    if (value != null) set[bag.storedKey(entry)] = value else unset += bag.storedKey(entry)
                }
                // Removal is a form of clearing, so under IGNORE the stale keys stay.
                if (!clears) return@forEach
                if (previous != null) {
                    previous.get(bag.fieldName)?.fieldNames()?.asSequence().orEmpty().filter { it !in current }.forEach { unset += bag.storedKey(it) }
                } else if (readsStore) {
                    bags += mapOf(
                        "prefix" to bag.storedPrefix, "flat" to bag.flat, "keep" to current.keys.map(bag::storedKey),
                        "reservedKeys" to bag.reservedKeys.toList(), "reservedPrefixes" to bag.reservedPrefixes,
                    )
                }
            }

            var add = emptyList<String>()
            var drop = emptyList<String>()
            var owned: Map<String, Any?>? = null
            model.nodeLabels?.takeIf { writable(it.fieldName) }?.let { labels ->
                val current = (values[labels.fieldName] as? Collection<*>).orEmpty().map { it.toString() }
                require(current.none { it.isBlank() }) { "@NodeLabels field '${labels.fieldName}' on ${model.clazz.simpleName} holds a blank label." }
                add = current.distinct().filter { it !in model.labels }
                val record = labels.ownedProperty
                if (record == null) {
                    if (clears) drop = labels.dropped(current)
                } else if (clears && readsStore) {
                    // An open set: what it may remove is what it recorded on the node.
                    owned = mapOf("prop" to record, "clear" to true, "base" to model.labels)
                } else if (add.isNotEmpty()) {
                    owned = mapOf("prop" to record)
                }
            }

            val node = plan.node()
            plan.add(
                "node", "r" to node, "s" to plan.shape(model.labels, key), "k" to OracleSql.key(id),
                "key" to key, "id" to id, "labels" to model.labels.sorted(), "fresh" to plan.fresh(),
                "set" to set.takeIf { it.isNotEmpty() }, "unset" to unset.takeIf { it.isNotEmpty() }, "bags" to bags.takeIf { it.isNotEmpty() },
                "add" to add.takeIf { it.isNotEmpty() }, "drop" to drop.takeIf { it.isNotEmpty() }, "owned" to owned, *more,
            )
            return node
        }

        /** A bag value is a node property: a scalar, or a list of scalars. */
        private fun requireStorable(value: Any?, storedKey: String) {
            fun scalar(v: Any?) = v is String || v is Number || v is Boolean || v is Char || v is java.util.Date ||
                v?.javaClass?.`package`?.name?.startsWith("java.time") == true
            val storable = when (value) {
                null -> true
                is Map<*, *> -> false
                is Collection<*> -> value.all(::scalar)
                is Array<*> -> value.all(::scalar)
                else -> scalar(value)
            }
            require(storable) {
                "@PropertyBag value for key '$storedKey' is not a storable node property (${value?.let { it::class.simpleName }}). A bag value must be " +
                    "a String, Number, Boolean, temporal, or a homogeneous array/list of those — not a nested map or object."
            }
        }

        /**
         * Joins the root to a node. A property of a relationship fragment that is null is left alone,
         * unless the save clears what was set to null.
         */
        private fun join(relationship: RelationshipModel, item: Any, from: Int, to: Int, clears: Boolean) {
            val values = if (relationship.isRelationshipFragment) objectMapper.toMap(item) else emptyMap()
            val names = if (relationship.isRelationshipFragment) relationship.relationshipProperties else emptyList()
            plan.link(
                from, to, relationship.type, relationship.direction,
                properties = names.filter { values[it] != null }.associateWith { values[it] },
                clear = if (clears) names.filter { values[it] == null } else emptyList(),
            )
        }

        /**
         * Removes the relationships of a field from the root to the targets whose ids are [ids], or when
         * [keep] to every target but those. A target whose id is among [heldIds], those of the nodes the
         * object holds in any of its fields, is never deleted as unreferenced: a later step joins it.
         */
        private fun removal(
            relationship: RelationshipModel, target: Target, from: Int, ids: List<Any>, keep: Boolean, removedTargets: RemovedTargets, heldIds: List<Any>,
        ) = plan.add(
            "unlink", "from" to from, "type" to relationship.type, "dir" to relationship.direction.code,
            "s" to plan.shape(target.model.labels, target.idProperty), "ids" to ids.map(OracleSql::key), "keep" to keep.takeIf { it },
            "delete" to (removedTargets == RemovedTargets.DELETE_UNREFERENCED).takeIf { it }, "held" to heldIds.map(OracleSql::key).takeIf { it.isNotEmpty() },
        )
    }

    /** The items of a digested relationship field, index-aligned with the field's collection. */
    private fun digestItems(node: JsonNode?, relationship: RelationshipModel): List<JsonNode?> = when {
        node == null || node.isNull -> emptyList()
        relationship.isCollection -> node.map { item -> item.takeUnless { it.isNull } }
        else -> listOf(node)
    }

    /**
     * The node a relationship field's items point at: the model its id and labels come from (for a
     * nested view, its root's), and how to reach it from an item or from an item's digest.
     */
    private inner class Target(val relationship: RelationshipModel, val view: GraphViewModel?, val model: FragmentModel) {
        private val idField = requireNotNull(model.nodeIdField) { "Relationship target ${model.className} has no @NodeId" }
        val idProperty: String = model.nodeIdProperty ?: idField

        /** The fragment or view [item] points at: itself, or a relationship fragment's target. */
        fun nodeOf(item: Any): Any =
            if (relationship.isRelationshipFragment) {
                requireNotNull(read(item, relationship.targetFieldName!!)) {
                    "Target node field '${relationship.targetFieldName}' is null in relationship fragment"
                }
            } else {
                item
            }

        fun nodeOf(item: JsonNode): JsonNode? =
            if (relationship.isRelationshipFragment) item.get(relationship.targetFieldName!!) else item

        /** The fragment [item] points at: of a nested view, its root. */
        fun fragmentOf(item: Any): Any {
            val node = nodeOf(item)
            return view?.let { read(node, it.rootFragment.fieldName) } ?: node
        }

        fun idOf(item: Any): Any? = idOfFragment(fragmentOf(item))

        /** Whether [fragment] is a node this field reads: one of its class, or one that carries its labels. */
        fun matches(fragment: Any): Boolean =
            model.clazz.isInstance(fragment) || FragmentModel.from(fragment.javaClass).labels.containsAll(model.labels)

        fun idOfFragment(fragment: Any): Any? = objectMapper.toMap(fragment)[idField]

        fun idOf(item: JsonNode): String? {
            val node = nodeOf(item)
            val fragment = view?.let { node?.get(it.rootFragment.fieldName) } ?: node
            return fragment?.get(idField)?.takeUnless { it.isNull }?.asText()
        }
    }

    private fun targetOf(relationship: RelationshipModel): Target {
        val targetClass = if (relationship.isRelationshipFragment) {
            requireNotNull(relationship.targetNodeType) { "Relationship fragment '${relationship.fieldName}' has no target" }
        } else {
            relationship.elementType
        }
        return if (targetClass.isAnnotationPresent(GraphView::class.java)) {
            val nested = GraphViewModel.from(targetClass)
            Target(relationship, nested, FragmentModel.from(nested.rootFragment.fragmentType))
        } else {
            Target(relationship, null, FragmentModel.from(targetClass))
        }
    }
}

/** Whether a field of this relationship loads relationships [other] loads too: the same type, read the same way. */
private fun RelationshipModel.reads(other: RelationshipModel): Boolean =
    type == other.type && (direction == other.direction || direction == Direction.UNDIRECTED || other.direction == Direction.UNDIRECTED)
