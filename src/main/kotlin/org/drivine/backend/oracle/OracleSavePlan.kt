package org.drivine.backend.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.PropertyBagModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.SaveTraversal
import org.drivine.query.StampWrite

/**
 * Plans a whole save for Oracle: the root, the relationships it drops, each related node and the
 * relationship to it. It decides what `SaveStatementBuilder` decides for the graph engines, from the
 * same model, and writes it as steps for the write block where that writes Cypher.
 *
 * Every related node is a step of its own. The block's text does not grow with a list, so nothing is
 * gained by saving the nodes of a field together.
 */
internal class OracleSavePlan(private val graph: OracleGraph, private val objectMapper: ObjectMapper) {

    /** The statement that saves what [request] asks. */
    fun build(request: SaveRequest): SaveStatement = with(request) {
        Composition(checked, nullPolicy, rootWriteFields, replaced, createsRoot).compose(obj, before, after)
    }

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
        replaced: (RelationshipModel) -> RemovedTargets?,
        private val createsRoot: Boolean,
    ) : SaveTraversal<Int>(objectMapper, replaced) {
        /** The steps, in the order the walk decides them. */
        val plan = WritePlan(graph)

        /** The object's root as the statement names it, known once its step has been planned. */
        private lateinit var root: StampWrite

        /** The statement that saves [obj]: a view walked from its root, or a fragment as a root alone. */
        fun compose(obj: Any, before: JsonNode?, after: JsonNode?): SaveStatement {
            if (obj.javaClass.isAnnotationPresent(GraphView::class.java)) {
                val model = GraphViewModel.from(obj.javaClass)
                view(obj, model, 0, before, after, replacesLists(model, before))
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

        /** The root's number in the plan, which the steps of the view's fields join to. */
        override fun viewRoot(fragment: Any, model: FragmentModel, depth: Int, replaces: Boolean, before: JsonNode?, after: JsonNode?): Int =
            if (depth == 0) rootNode(fragment, model, replaces, before) else relatedNode(fragment, model, before, after)

        /** The ids are compared as keys: a number with the text Oracle reads a JSON number back as. */
        override fun removal(field: Field, root: Int, depth: Int, ids: List<Any>, keep: Boolean, removedTargets: RemovedTargets, heldIds: List<Any>) {
            plan.unlink(
                root, field.relationship.type, field.relationship.direction, plan.shape(field.target.model.labels, field.target.idProperty),
                ids.map(OracleSql::key), keep, delete = removedTargets == RemovedTargets.DELETE_UNREFERENCED, held = heldIds.map(OracleSql::key),
            )
        }

        /** Each item is a step of its own, and its relationship one more. */
        override fun fieldSave(field: Field, root: Int, depth: Int, clears: Boolean) = object : FieldSave {
            override fun item(related: Related) {
                val nested = field.target.view
                val saved = if (nested != null) {
                    view(related.node, nested, depth + 1, related.before, related.after)
                } else {
                    // The runtime type, not the declared one: a subtype has labels of its own.
                    relatedNode(related.node, FragmentModel.from(related.node.javaClass), related.before, related.after)
                }
                if (related.link) join(field.relationship, related.item, root, saved, clears)
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
            root = StampWrite(fragment.javaClass, idOf(model, values), model.labels.joinToString(":"), keyOf(model), expected, carriedStamp, model.labels)
            return node
        }

        /**
         * A node reached through a relationship. One that was loaded, as [before], and is now [after]
         * has the fields that differ written, and one set to null cleared: a field the change did not
         * touch is not written over. One that was not loaded is written whole, a null field left alone.
         */
        private fun relatedNode(fragment: Any, model: FragmentModel, before: JsonNode?, after: JsonNode?): Int {
            val values = objectMapper.toMap(fragment)
            // The index the node's stamp is returned under, when its fragment declares one.
            val index = model.stampField?.let { handedBack(fragment, model, values).toString() }
            val altered = altered(before, after)
            return if (altered == null) {
                node(fragment, model, values, null, NullPolicy.IGNORE, null, readsStore = false, index = index)
            } else {
                node(fragment, model, values, before, NullPolicy.CLEAR, altered, readsStore = false, index = index)
            }
        }

        /** The property a node of [model] keeps its id under. */
        private fun keyOf(model: FragmentModel): String = model.nodeIdProperty ?: requireNotNull(model.nodeIdField) {
            "Cannot save a fragment without a @NodeId field: ${model.className}"
        }

        /** The id among [values], those of a fragment of [model]. A fragment with none cannot be saved. */
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

        /** Whether the step may touch [field]. */
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

        /** What the step does to the node's labels, beside those of the fragment's class, which a node always carries. */
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
}
