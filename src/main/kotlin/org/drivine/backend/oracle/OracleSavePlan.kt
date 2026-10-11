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
import org.drivine.model.PropertyBagModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.NodeKey
import org.drivine.query.Repeat
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.drivine.query.read

/**
 * Plans a whole save for Oracle: the root, the relationships it drops, each related node and the
 * relationship to it. It decides what `SaveStatementBuilder` decides for the graph engines, from the
 * same model, and writes it as steps for the write block where that writes Cypher.
 *
 * Every related node is a step of its own. The block's text does not grow with a list, so nothing is
 * gained by saving the nodes of a field together.
 */
internal class OracleSavePlan(private val graph: OracleGraph, private val objectMapper: ObjectMapper) {

    /**
     * The statement that saves [obj]. The parameters are those of `SaveStatementBuilder.build`:
     *
     * @param checked whether the root must carry the stamp the object does, or the save is refused as stale
     * @param nullPolicy whether a null field is left alone or cleared
     * @param rootWriteFields the root's fields the save may write; null for all of them
     * @param replaced what a field whose list is replaced does with the targets it drops; null for a field that is not
     * @param before and [after] the object's digest as loaded and now, for a save of what a change altered
     * @param createsRoot whether the save makes the root when it is gone; an update does not
     */
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

    /** One save's plan as it is put together: the steps, and what the statement is handed back with them. */
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
            node(fragment, model, objectMapper.toMap(fragment), null, nullPolicy, null, readsStore = true, index = index.toString())
        }

        /** A relationship field of a view as the object holds it: the field, where its items point, and the items. */
        private inner class Field(val relationship: RelationshipModel, val target: Target, val items: List<Any?>) {
            val ids: List<Any?> = items.map { item -> item?.let { target.idOf(it) } }
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
                val value = read(view, relationship.fieldName)
                Field(relationship, targetOf(relationship), if (relationship.isCollection) (value as? Collection<*>)?.toList().orEmpty() else listOf(value))
            }
            // Every node the object holds, in any field and in any view nested in one: none of them is
            // deleted as unreferenced, though the field it is dropped from holds the only relationship
            // the store has to it yet.
            val held = if (depth == 0) heldBy(view, model) else emptyList()
            written.forEach { field ->
                val itemsBefore = before?.let { digest ->
                    digestItems(digest.get(field.relationship.fieldName), field.relationship).filterNotNull()
                        .mapNotNull { item -> field.target.idOf(item)?.let { it to item } }.toMap()
                }
                fieldRemovals(field, rootNode, itemsBefore, depth, held, written)
                fieldItems(field, rootNode, depth, itemsBefore, after?.let { digestItems(it.get(field.relationship.fieldName), field.relationship) }, clears = before != null)
            }
            return rootNode
        }

        /**
         * The relationships of [field] the save removes: to the targets the object was loaded with and
         * no longer holds, as [itemsBefore] says; or, for a root field whose list is replaced, to every
         * target but those the object holds, in this field or another that reads the same relationships.
         */
        private fun fieldRemovals(field: Field, rootNode: Int, itemsBefore: Map<String, JsonNode>?, depth: Int, held: List<Any>, written: List<Field>) {
            val target = field.target
            if (itemsBefore != null) {
                val gone = itemsBefore.keys - field.ids.filterNotNull().map { it.toString() }.toSet()
                // As stored: a number is compared with the text Oracle reads a JSON number back as.
                val goneIds = gone.mapNotNull { target.storedIdOf(itemsBefore.getValue(it)) }
                if (goneIds.isNotEmpty()) removal(field.relationship, target, rootNode, goneIds, keep = false, RemovedTargets.KEEP, emptyList())
            } else if (depth == 0) {
                replaced(field.relationship)?.let { removedTargets ->
                    val heldIds = held.filter { target.matches(it) }.mapNotNull { idOf(it) }.distinct()
                    // Another field that reads the same relationships holds some of them: those are its to keep.
                    val siblingIds = written.filter { other -> other !== field && other.relationship.reads(field.relationship) }
                        .flatMap { sibling -> sibling.items.filterNotNull().map { sibling.target.fragmentOf(it) } }
                        .filter { target.matches(it) }.mapNotNull { idOf(it) }
                    removal(field.relationship, target, rootNode, (field.ids.filterNotNull() + siblingIds).distinct(), keep = true, removedTargets, heldIds)
                }
            }
        }

        /**
         * The nodes [field] holds, each saved and joined to the root where the relationship is new or
         * changed. A node the field holds more than once is written once, as the last of them says.
         *
         * @param clears whether a relationship property set to null is cleared: a save of what a change altered does
         */
        private fun fieldItems(field: Field, rootNode: Int, depth: Int, itemsBefore: Map<String, JsonNode>?, itemsNow: List<JsonNode?>?, clears: Boolean) {
            val target = field.target
            val last = field.ids.withIndex().filter { it.value != null }.associate { it.value to it.index }
            field.items.forEachIndexed { index, item ->
                if (item == null) return@forEachIndexed
                if (last[field.ids[index]]?.let { it != index } == true) {
                    repeated(target.fragmentOf(item))
                    return@forEachIndexed
                }
                val was = field.ids[index]?.let { itemsBefore?.get(it.toString()) }
                val now = itemsNow?.getOrNull(index)
                // As it was when loaded: the relationship and its target are left alone.
                if (was != null && was == now) return@forEachIndexed
                // A relationship that was loaded is not made again: if another writer has removed it
                // since, it stays removed. Its node is written, and it too if its properties changed.
                val link = was == null || field.relationship.relationshipProperties.any { was.get(it) != now?.get(it) }
                val node = target.nodeOf(item)
                val nodeBefore = was?.let { target.nodeOf(it) }
                val nodeNow = now?.let { target.nodeOf(it) }
                val saved = if (target.view != null) {
                    view(node, target.view, depth + 1, nodeBefore, nodeNow)
                } else {
                    // The runtime type, not the declared one: a subtype has labels of its own.
                    relatedNode(node, FragmentModel.from(node.javaClass), nodeBefore, nodeNow)
                }
                if (link) join(field.relationship, item, rootNode, saved, clears)
            }
        }

        /** The object's own root: checked against the stamp it carries, and the node every stamp is reported from. */
        private fun rootNode(fragment: Any, model: FragmentModel, replaces: Boolean, before: JsonNode?): Int {
            val values = objectMapper.toMap(fragment)
            val expected = model.stampField?.takeIf { checked }?.let { values[it] as? String }
            val carriedStamp = model.stampField?.let { values[it] as? String }
            // The token for the node's own data is always compared; the whole stamp, and so the token
            // for its relationships too, when the save replaces a relationship list. A stamp with no
            // node token of its own, which no save leaves, is compared whole too.
            val whole = expected != null && (replaces || !Stamps.hasNodeToken(expected))
            val node = node(
                fragment, model, values, before, nullPolicy, rootWriteFields, readsStore = true,
                // An update makes no node: one that is gone stays gone, and the block gives no row.
                root = RootStep(carriedStamp, expected?.let { if (whole) it else Stamps.nodeToken(it) }, whole, createsRoot),
            )
            root = StampWrite(fragment.javaClass, idOf(model, values), model.labels.joinToString(":"), keyOf(model), expected, carriedStamp)
            return node
        }

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
                node(fragment, model, values, null, NullPolicy.IGNORE, null, readsStore = false, index = index)
            } else {
                node(fragment, model, values, before, NullPolicy.CLEAR, altered, readsStore = false, index = index)
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
            writeFields: Set<String>?, readsStore: Boolean, index: String? = null, root: RootStep? = null,
        ): Int {
            val id = idOf(model, values)
            val key = keyOf(model)
            val writing = NodeWriting(model, values, clears = nullPolicy == NullPolicy.CLEAR, writeFields, previous, readsStore)
            val labels = writing.labels()
            val writes = NodeWrites(
                set = writing.set, unset = writing.unset, bags = writing.bags,
                addLabels = labels.add, dropLabels = labels.drop, owned = labels.owned,
            )
            return plan.node(plan.shape(model.labels, key), key, id, OracleSql.key(id), model.labels, writes, index, root)
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
        ) = plan.unlink(
            from, relationship.type, relationship.direction, plan.shape(target.model.labels, target.idProperty),
            ids.map(OracleSql::key), keep, delete = removedTargets == RemovedTargets.DELETE_UNREFERENCED, held = heldIds.map(OracleSql::key),
        )
    }

    /**
     * What one `node` step writes, worked out from the object's [values] field by field: the declared
     * fields as [set] and [unset], each property bag, and the labels.
     *
     * @param clears whether a null is cleared rather than left alone
     * @param writeFields the only fields the step may touch; null for all of them
     * @param previous the node's digest as loaded, which says what a bag has dropped
     * @param readsStore whether the block reads what the stored node has, to clear what the object does not
     */
    private class NodeWriting(
        private val model: FragmentModel,
        private val values: Map<String, Any?>,
        private val clears: Boolean,
        private val writeFields: Set<String>?,
        private val previous: JsonNode?,
        private val readsStore: Boolean,
    ) {
        val set = linkedMapOf<String, Any?>()
        val unset = mutableListOf<String>()
        val bags = mutableListOf<BagClearing>()

        init {
            model.fields.filter { !it.stamp && it.name != model.nodeIdField && writable(it.name) }.forEach { field ->
                val value = values[field.name]
                if (value != null) set[field.propertyName] = value else if (clears) unset += field.propertyName
            }
            model.propertyBags.filter { writable(it.fieldName) }.forEach(::bag)
        }

        private fun writable(field: String) = writeFields == null || field in writeFields

        /** The entries of [bag] as properties, and when the save clears, the stale keys it drops. */
        private fun bag(bag: PropertyBagModel) {
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
            if (!clears) return
            if (previous != null) {
                previous.get(bag.fieldName)?.fieldNames()?.asSequence().orEmpty().filter { it !in current }.forEach { unset += bag.storedKey(it) }
            } else if (readsStore) {
                bags += BagClearing(bag.storedPrefix, bag.flat, current.keys.map(bag::storedKey), bag.reservedKeys.toList(), bag.reservedPrefixes)
            }
        }

        /** The labels a `@NodeLabels` field adds and drops, and how an open one's are recorded. */
        class Labels(val add: List<String> = emptyList(), val drop: List<String> = emptyList(), val owned: OwnedLabels? = null)

        fun labels(): Labels {
            val labels = model.nodeLabels?.takeIf { writable(it.fieldName) } ?: return Labels()
            val current = (values[labels.fieldName] as? Collection<*>).orEmpty().map { it.toString() }
            require(current.none { it.isBlank() }) { "@NodeLabels field '${labels.fieldName}' on ${model.clazz.simpleName} holds a blank label." }
            val add = current.distinct().filter { it !in model.labels }
            val record = labels.ownedProperty
            return when {
                record == null -> Labels(add, if (clears) labels.dropped(current) else emptyList())
                // An open set: what it may remove is what it recorded on the node.
                clears && readsStore -> Labels(add, owned = OwnedLabels(record, clear = true, base = model.labels))
                add.isNotEmpty() -> Labels(add, owned = OwnedLabels(record))
                else -> Labels(add)
            }
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

        /** The digest of the node [item] points at: itself, or a relationship fragment's target. */
        fun nodeOf(item: JsonNode): JsonNode? =
            if (relationship.isRelationshipFragment) item.get(relationship.targetFieldName!!) else item

        /** The fragment [item] points at: of a nested view, its root. */
        fun fragmentOf(item: Any): Any {
            val node = nodeOf(item)
            return view?.let { read(node, it.rootFragment.fieldName) } ?: node
        }

        /** The id of the fragment [item] points at. */
        fun idOf(item: Any): Any? = idOfFragment(fragmentOf(item))

        /** Whether [fragment] is a node this field reads: one of its class, or one that carries its labels. */
        fun matches(fragment: Any): Boolean =
            model.clazz.isInstance(fragment) || FragmentModel.from(fragment.javaClass).labels.containsAll(model.labels)

        /** The id of [fragment], as the field the model names holds it. */
        fun idOfFragment(fragment: Any): Any? = objectMapper.toMap(fragment)[idField]

        /** The digest of the fragment [item] points at: of a nested view, its root's. */
        private fun fragmentOf(item: JsonNode): JsonNode? {
            val node = nodeOf(item)
            return view?.let { node?.get(it.rootFragment.fieldName) } ?: node
        }

        /** The id of a digested item as it is stored: a number as a number, anything else as text. */
        fun storedIdOf(item: JsonNode): Any? {
            val id = fragmentOf(item)?.get(idField)?.takeUnless { it.isNull } ?: return null
            return if (id.isNumber) id.numberValue() else id.asText()
        }

        /** The id of a digested item as text, which the items of a field are told apart by. */
        fun idOf(item: JsonNode): String? = fragmentOf(item)?.get(idField)?.takeUnless { it.isNull }?.asText()
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
