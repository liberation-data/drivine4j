package org.drivine.query

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel

/**
 * The walk of one save over the object it saves: the root, the relationships each field drops, each
 * related node and whether it is joined. What a save writes is decided here, once, from the model and
 * from the object's digests, and is the same on every store. How it is written is the subclass's:
 * `SaveStatementBuilder` writes the parts of a Cypher statement, `OracleSavePlan` the steps of a write
 * block.
 *
 * The walk is in the object's order, a view nested in a field walked where its item is, and each of
 * [viewRoot], [removal] and [FieldSave.item] is called as the walk reaches it: a subclass may number
 * what it writes as it is called.
 *
 * @param R what a subclass knows a saved root by, to join the nodes of its fields to it
 * @param replaced what becomes of the targets a relationship field of the object's root no longer
 *   holds, for a field whose list is the whole list; null for a field that only adds
 */
internal abstract class SaveTraversal<R>(
    protected val objectMapper: ObjectMapper,
    private val replaced: (RelationshipModel) -> RemovedTargets?,
) {
    /** The fragments reached through a relationship that declare a `@NodeStamp` field, as saved. */
    protected val stamped = mutableListOf<Any>()

    /** For each of [stamped], the stamp it carried; null when it carried none. */
    protected val carried = mutableListOf<String?>()

    /** For each of [stamped], the node it is saved to; null when its id is. */
    protected val keys = mutableListOf<NodeKey?>()

    /** The stamped fragments a field holds beside a later one for the same node, which is the one that is written. */
    protected val repeats = mutableListOf<Repeat>()

    /**
     * Saves the root of the view at [depth]: the object's own at 0, checked as the save is and with
     * [replaces] its relationships checked too; below that a nested view's, saved as any related node
     * is. [before] and [after] are its digests as it was loaded and as it is now.
     */
    protected abstract fun viewRoot(fragment: Any, model: FragmentModel, depth: Int, replaces: Boolean, before: JsonNode?, after: JsonNode?): R

    /**
     * Removes the relationships of [field] from [root] to the targets whose ids are [ids], or when
     * [keep] to every target but those. A target whose id is among [heldIds], those of the nodes the
     * object holds in any of its fields, is never deleted as unreferenced: it is joined later in the walk.
     */
    protected abstract fun removal(field: Field, root: R, depth: Int, ids: List<Any>, keep: Boolean, removedTargets: RemovedTargets, heldIds: List<Any>)

    /**
     * What saves the items of [field], held by the view at [depth] whose root is [root].
     *
     * @param clears whether a relationship property set to null is cleared: a save of what a change altered does
     */
    protected abstract fun fieldSave(field: Field, root: R, depth: Int, clears: Boolean): FieldSave

    /** Saves the items of one field: each as the walk reaches it, in the field's order, then [done]. */
    protected interface FieldSave {
        fun item(related: Related)

        fun done() {}
    }

    /**
     * An item of a field that the save writes: the [node] it points at, and whether the relationship
     * to it is written too. [before] and [after] are the node's digests as loaded and now; [before] is
     * null for a node the object was not loaded with.
     */
    protected class Related(val item: Any, val node: Any, val link: Boolean, val before: JsonNode?, val after: JsonNode?)

    /** A relationship field of a view as the object holds it: the field, where its items point, and the items. */
    protected inner class Field(val relationship: RelationshipModel, val target: Target, val items: List<Any?>) {
        val ids: List<Any?> by lazy { items.map { item -> item?.let { target.idOf(it) } } }
    }

    /** Whether a save of the view replaces a relationship list of its root: it overwrites the root's relationships, so they are checked too. */
    protected fun replacesLists(model: GraphViewModel, before: JsonNode?): Boolean =
        before == null && model.relationships.any { !it.readOnly && replaced(it) != null }

    /** The root of [view], then each relationship field it writes. Gives what [viewRoot] gave for the root. */
    protected fun view(view: Any, model: GraphViewModel, depth: Int, before: JsonNode?, after: JsonNode?, replaces: Boolean = false): R {
        val rootField = model.rootFragment.fieldName
        val rootFragment = requireNotNull(read(view, rootField)) { "Root fragment $rootField is null" }
        val root = viewRoot(
            rootFragment, FragmentModel.from(model.rootFragment.fragmentType), depth, replaces, before?.get(rootField), after?.get(rootField),
        )

        val written = fieldsOf(view, model)
        // Every node the object holds, in any field and in any view nested in one: none of them is
        // deleted as unreferenced, though the field it is dropped from holds the only relationship
        // the store has to it yet.
        val held = if (depth == 0) heldBy(view, model) else emptyList()
        written.forEach { field ->
            val itemsBefore = before?.let { digest ->
                digestItems(digest.get(field.relationship.fieldName), field.relationship).filterNotNull()
                    .mapNotNull { item -> field.target.idOf(item)?.let { it to item } }.toMap()
            }
            val itemsNow = after?.let { digestItems(it.get(field.relationship.fieldName), field.relationship) }
            removals(field, root, depth, itemsBefore, held, written)
            // A save leaves a relationship's property alone where the object's is null, as it leaves a
            // related node's. A save of what a change altered clears one the change set to null.
            items(field, root, depth, itemsBefore, itemsNow, clears = before != null)
        }
        return root
    }

    /**
     * The relationships of [field] the save removes: to the targets the object was loaded with and
     * no longer holds, as [itemsBefore] says; or, for a root field whose list is replaced, to every
     * target but those the object holds, in this field or another that reads the same relationships.
     */
    private fun removals(field: Field, root: R, depth: Int, itemsBefore: Map<String, JsonNode>?, held: List<Any>, written: List<Field>) {
        val target = field.target
        if (itemsBefore != null) {
            val gone = itemsBefore.keys - field.ids.filterNotNull().map { it.toString() }.toSet()
            // As the id is stored, a number as a number: a string does not equal one.
            val goneIds = gone.mapNotNull { target.storedIdOf(itemsBefore.getValue(it)) }
            if (goneIds.isNotEmpty()) removal(field, root, depth, goneIds, keep = false, RemovedTargets.KEEP, emptyList())
        } else if (depth == 0) {
            replaced(field.relationship)?.let { removedTargets ->
                val heldIds = held.filter { target.matches(it) }.mapNotNull { idOf(it) }.distinct()
                // Another field that reads the same relationships holds some of them: those are its to keep.
                val siblingIds = written.filter { other -> other !== field && other.relationship.reads(field.relationship) }
                    .flatMap { sibling -> sibling.items.filterNotNull().map { sibling.target.fragmentOf(it) } }
                    .filter { target.matches(it) }.mapNotNull { idOf(it) }
                removal(field, root, depth, (field.ids.filterNotNull() + siblingIds).distinct(), keep = true, removedTargets, heldIds)
            }
        }
    }

    /**
     * The nodes [field] holds, each saved, and joined to the root where the relationship is new or
     * changed. A node the field holds more than once is written once, as the last of them says.
     */
    private fun items(field: Field, root: R, depth: Int, itemsBefore: Map<String, JsonNode>?, itemsNow: List<JsonNode?>?, clears: Boolean) {
        val target = field.target
        val last = field.ids.withIndex().filter { it.value != null }.associate { it.value to it.index }
        val save = fieldSave(field, root, depth, clears)
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
            save.item(Related(item, target.nodeOf(item), link, was?.let { target.nodeOf(it) }, now?.let { target.nodeOf(it) }))
        }
        save.done()
    }

    /** The relationship fields of [view] that a save writes. A read-only field (every path is one) is loaded and never written. */
    private fun fieldsOf(view: Any, model: GraphViewModel): List<Field> =
        model.relationships.filterNot { it.readOnly }.map { relationship ->
            val value = read(view, relationship.fieldName)
            Field(relationship, targetOf(relationship), if (relationship.isCollection) (value as? Collection<*>)?.toList().orEmpty() else listOf(value))
        }

    /** The fragments [view] holds through its relationship fields, and through those of each view nested in it. */
    private fun heldBy(view: Any, model: GraphViewModel): List<Any> =
        fieldsOf(view, model).flatMap { field ->
            val target = field.target
            field.items.filterNotNull().flatMap { item ->
                val nested = target.view?.let { heldBy(target.nodeOf(item), it) }.orEmpty()
                listOf(target.fragmentOf(item)) + nested
            }
        }

    /** Records [fragment], whose fields are [values], as a node whose stamp is handed back; gives its index. */
    protected fun handedBack(fragment: Any, model: FragmentModel, values: Map<String, Any?>): Int {
        stamped.add(fragment)
        carried.add(values[model.stampField] as? String)
        keys.add(values[model.nodeIdField]?.let { NodeKey(model.labels.toSet(), it) })
        return stamped.size - 1
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

    /** The fields that differ between a fragment's digest as loaded and now; null when it was not loaded. */
    protected fun altered(before: JsonNode?, after: JsonNode?): Set<String>? {
        if (before == null || after == null) return null
        val names = (before.fieldNames().asSequence() + after.fieldNames().asSequence()).toSet()
        return names.filter { before.get(it) != after.get(it) }.toSet()
    }

    /** The id of [fragment], as its own class declares it. */
    private fun idOf(fragment: Any): Any? =
        FragmentModel.from(fragment.javaClass).nodeIdField?.let { objectMapper.toMap(fragment)[it] }

    /** The items of a digested relationship field, index-aligned with the field's collection. */
    private fun digestItems(node: JsonNode?, relationship: RelationshipModel): List<JsonNode?> = when {
        node == null || node.isNull -> emptyList()
        relationship.isCollection -> node.map { item -> item.takeUnless { it.isNull } }
        else -> listOf(node)
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

    /**
     * The node a relationship field's items point at: the model its id and labels come from (for a
     * nested view, its root's), and how to reach it from an item or from an item's digest.
     */
    protected inner class Target(val relationship: RelationshipModel, val view: GraphViewModel?, val model: FragmentModel) {
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
        fun idOf(item: Any): Any? = objectMapper.toMap(fragmentOf(item))[idField]

        /** Whether [fragment] is a node this field reads: one of its class, or one that carries its labels. */
        fun matches(fragment: Any): Boolean =
            model.clazz.isInstance(fragment) || FragmentModel.from(fragment.javaClass).labels.containsAll(model.labels)

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
}

/** Whether a field of this relationship loads relationships [other] loads too: the same type, read the same way. */
private fun RelationshipModel.reads(other: RelationshipModel): Boolean =
    type == other.type && (direction == other.direction || direction == Direction.UNDIRECTED || other.direction == Direction.UNDIRECTED)

/** Reads the field [name] of [obj], wherever in its class hierarchy it is declared. */
internal fun read(obj: Any, name: String): Any? {
    var type: Class<*>? = obj.javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true }.get(obj) }
        type = type.superclass
    }
    throw NoSuchFieldException("${obj.javaClass.name} has no field '$name'")
}
