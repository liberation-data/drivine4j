package org.drivine.query

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
import org.drivine.query.grammar.CypherGrammar

/**
 * A whole save as one statement. It returns one row holding the stamps the save left, joined by commas:
 * the root's, then `index=stamp` for each of [stamped] by its index. It returns no row when [root]
 * expected a stamp the node no longer carries, and then it has written nothing.
 */
internal class SaveStatement(
    val statement: String,
    val bindings: Map<String, Any?>,
    val root: StampWrite,
    /** The fragments reached through a relationship that declare a `@NodeStamp` field, as saved. */
    val stamped: List<Any>,
)

/**
 * Builds the one statement that saves a fragment or a view: the root, the relationships it drops, each
 * related node and the relationship to it. One statement is atomic on every engine, with or without a
 * transaction, so a save is applied whole or not at all.
 *
 * The statement is a chain of parts. Each part leaves exactly one row, so the next one runs once, and
 * the nodes later parts need are carried from part to part: `_r0` is the root, `_r1` the root of a view
 * nested in it, and so on; `_ns` lists the stamped nodes reached through a relationship and `_is` the
 * index of each. Only the root part can leave no row, when the root is stale, and then no later part runs.
 *
 * The related fragments of one field are saved by one part, as the rows of an `UNWIND`, so the
 * statement's text does not grow with a list and an engine plans it once. A related view, and a
 * fragment an `UNWIND` cannot write (see [savedByUnwind]), has a part of its own.
 *
 * The nodes are carried, and not matched again by id where they are needed: FalkorDB gives no row for
 * two `MATCH` clauses that follow an aggregation in one statement.
 *
 * A relationship the save makes or removes, or whose properties it changes, replaces the relationship
 * token of the stamp at both of its ends. See [Stamps].
 */
internal class SaveStatementBuilder(
    private val objectMapper: ObjectMapper,
    private val grammar: CypherGrammar?,
    private val storedKeys: StoredPropertyKeys?,
) {

    /**
     * @param checked whether the root is saved only if it still carries the stamp [obj] holds
     * @param rootWriteFields the only root fields to write; null for all of them
     * @param replaced what becomes of the targets a relationship field of the root no longer holds,
     *   for a field whose list is the whole list; null for a field that only adds
     * @param before the digest of [obj] as it was loaded, with [after] its digest now. When given, the
     *   statement writes what differs: it drops the relationships that are gone and leaves alone the
     *   related nodes that are as they were
     */
    fun build(
        obj: Any,
        checked: Boolean,
        nullPolicy: NullPolicy,
        rootWriteFields: Set<String>? = null,
        replaced: (RelationshipModel) -> RemovedTargets? = { null },
        before: JsonNode? = null,
        after: JsonNode? = null,
    ): SaveStatement = Composition(checked, nullPolicy, rootWriteFields, replaced).compose(obj, before, after)

    /** The related fragments of one field that one `UNWIND` part saves: one class, joined or not. */
    private data class Group(val model: FragmentModel, val link: Boolean)

    private inner class Composition(
        private val checked: Boolean,
        private val nullPolicy: NullPolicy,
        private val rootWriteFields: Set<String>?,
        private val replaced: (RelationshipModel) -> RemovedTargets?,
    ) {
        private val text = StringBuilder()
        private val bindings = mutableMapOf<String, Any?>(MARK_PARAM to Stamps.fresh())
        private val stamped = mutableListOf<Any>()
        private var parts = 0
        private lateinit var root: StampWrite

        fun compose(obj: Any, before: JsonNode?, after: JsonNode?): SaveStatement {
            if (obj.javaClass.isAnnotationPresent(GraphView::class.java)) {
                val model = GraphViewModel.from(obj.javaClass)
                // A save that replaces a list overwrites the root's relationships, so they are checked too.
                val replaces = before == null && model.relationships.any { !it.readOnly && replaced(it) != null }
                view(obj, model, 0, before, after, replaces)
            } else {
                rootPart(obj, FragmentModel.from(obj.javaClass), 0, replaces = false)
            }
            // One string and not a list: every engine's driver hands a string back the same way.
            line("RETURN reduce(s = _r0.$STAMP, k IN range(0, size(_ns) - 1) | s + ',' + _is[k] + '=' + (_ns[k]).$STAMP) AS ${Stamps.STAMP_COLUMN}")
            return SaveStatement(text.toString(), bindings, root, stamped)
        }

        /** The root of the view at [depth], then each relationship field it writes. Leaves `_r<depth>` carried. */
        private fun view(view: Any, model: GraphViewModel, depth: Int, before: JsonNode?, after: JsonNode?, replaces: Boolean = false) {
            val rootFragment = requireNotNull(read(view, model.rootFragment.fieldName)) {
                "Root fragment ${model.rootFragment.fieldName} is null"
            }
            rootPart(rootFragment, FragmentModel.from(model.rootFragment.fragmentType), depth, replaces)

            // A read-only field (every path is one) is loaded and never written.
            model.relationships.filterNot { it.readOnly }.forEach { relationship ->
                val target = targetOf(relationship)
                val value = read(view, relationship.fieldName)
                val items = if (relationship.isCollection) (value as? Collection<*>)?.toList().orEmpty() else listOf(value)
                val ids = items.map { item -> item?.let { target.idOf(it) } }
                val itemsNow = after?.let { digestItems(it.get(relationship.fieldName), relationship) }
                val itemsBefore = before?.let { digest ->
                    digestItems(digest.get(relationship.fieldName), relationship).filterNotNull()
                        .mapNotNull { item -> target.idOf(item)?.let { it to item } }.toMap()
                }

                if (itemsBefore != null) {
                    val gone = itemsBefore.keys - ids.filterNotNull().map { it.toString() }.toSet()
                    if (gone.isNotEmpty()) removalPart(relationship, target, depth, gone.toList(), keep = false, RemovedTargets.KEEP)
                } else if (depth == 0) {
                    replaced(relationship)?.let { removalPart(relationship, target, depth, ids.filterNotNull(), keep = true, it) }
                }

                val groups = linkedMapOf<Group, MutableList<Map<String, Any?>>>()
                items.forEachIndexed { index, item ->
                    if (item == null) return@forEachIndexed
                    val was = ids[index]?.let { itemsBefore?.get(it.toString()) }
                    val now = itemsNow?.getOrNull(index)
                    // As it was when loaded: the relationship and its target are left alone.
                    if (was != null && was == now) return@forEachIndexed
                    // A relationship that was loaded is not made again: if another writer has removed it
                    // since, it stays removed. Its node is written, and it too if its properties changed.
                    val link = was == null || relationship.relationshipProperties.any { was.get(it) != now?.get(it) }
                    val node = target.nodeOf(item)
                    // The runtime type, not the declared one: a subtype has labels of its own.
                    val nodeModel = if (target.view == null) FragmentModel.from(node.javaClass) else null
                    if (nodeModel != null && nodeModel.savedByUnwind(grammar)) {
                        groups.getOrPut(Group(nodeModel, link)) { mutableListOf() }.add(row(relationship, item, node, nodeModel))
                    } else {
                        relatedPart(relationship, target, item, depth, link, was?.let { target.nodeOf(it) }, now?.let { target.nodeOf(it) })
                    }
                }
                groups.forEach { (group, rows) -> groupPart(relationship, group, rows, depth) }
            }
        }

        /** Saves a root: the object's own at depth 0, a nested view's below it. */
        private fun rootPart(fragment: Any, model: FragmentModel, depth: Int, replaces: Boolean) {
            if (depth == 0) {
                val statement = FragmentMergeBuilder(model, objectMapper, grammar, storedKeys, Stamping(checked, relationships = replaces))
                    .buildMergeStatement(fragment, null, null, nullPolicy, rootWriteFields)
                add(statement)
                root = requireNotNull(statement.stamp)
                line("WITH n AS _r0, [] AS _ns, [] AS _is")
            } else {
                add(
                    FragmentMergeBuilder(model, objectMapper, grammar, stamping = Stamping(false), carry = carry(depth - 1) + ", ")
                        .buildMergeStatement(fragment, null)
                )
                line("WITH ${roots(depth - 1)}, n AS _r$depth, ${collected(fragment, model)}")
            }
        }

        /** The `UNWIND` row that saves [node], the fragment [item] points at, and the relationship to it. */
        private fun row(relationship: RelationshipModel, item: Any, node: Any, model: FragmentModel): Map<String, Any?> {
            val values = objectMapper.toMap(node)
            val id = values[model.nodeIdField]
                ?: throw IllegalArgumentException("Cannot build MERGE for fragment with null ID: ${model.className}")
            val properties = if (relationship.isRelationshipFragment) {
                val all = objectMapper.toMap(item)
                relationship.relationshipProperties.associateWith { all[it] }
            } else {
                emptyMap()
            }
            // The index the node's stamp is returned under; empty when its fragment declares no stamp.
            val index = if (model.stampField == null) "" else stamped.size.toString().also { stamped.add(node) }
            return mapOf("id" to id, "i" to index, "props" to model.unwindProps(values, NullPolicy.IGNORE), "rel" to properties)
        }

        /**
         * Saves the fragments of one class that a field holds, as [rows], and when [Group.link] joins
         * each to the root at [depth].
         */
        private fun groupPart(relationship: RelationshipModel, group: Group, rows: List<Map<String, Any?>>, depth: Int) {
            val part = parts++
            val model = group.model
            val rootVariable = "_r$depth"
            val carried = carry(depth)
            val handsBack = model.stampField != null
            bindings["p${part}_rows"] = rows
            line("UNWIND \$p${part}_rows AS row")
            line("MERGE (n:${model.labels.joinToString(":")} {${model.nodeIdProperty ?: model.nodeIdField}: row.id})")
            line("WITH $carried, row, n, $ROW_CHANGES_NODE AS _changed")
            line("SET n += row.props, ${Stamps.restamp("n", "_changed", MARK)}")
            if (!group.link) {
                if (handsBack) {
                    line("WITH ${roots(depth)}, _ns, _is, collect(n) AS _n, collect(row.i) AS _j")
                    line("WITH ${roots(depth)}, _ns + _n AS _ns, _is + _j AS _is")
                } else {
                    line("WITH $carried, count(n) AS _saved")
                    line("WITH $carried")
                }
                return
            }

            val type = relationship.type
            val names = relationship.relationshipProperties
            val same = listOf("x IS NOT NULL") + names.map { name ->
                "CASE WHEN row.rel.$name IS NULL THEN x.$name IS NULL ELSE coalesce(x.$name = row.rel.$name, false) END"
            }
            // The row's values are carried by name: a row is a map, and not every engine groups by one.
            val values = names.mapIndexed { index, name -> "row.rel.$name AS _q$index" }
            val held = (listOf("n", "_i") + names.indices.map { "_q$it" }).joinToString(", ")
            line("WITH $carried, row, n")
            line("OPTIONAL MATCH ($rootVariable)${edge(relationship, "x")}(n)")
            line(
                "WITH $carried, n, row.i AS _i, ${(values + "count(x) AS _had").joinToString(", ")}, " +
                    "sum(CASE WHEN ${same.joinToString(" AND ")} THEN 1 ELSE 0 END) AS _same"
            )
            if (relationship.direction == Direction.UNDIRECTED) {
                // A relationship is stored with a direction, and either one satisfies the field: it is
                // made, from the root, only when there is none.
                line("FOREACH (_ IN CASE WHEN _had = 0 THEN [1] ELSE [] END | CREATE ($rootVariable)-[:$type]->(n))")
                if (names.isNotEmpty()) {
                    line("WITH $carried, $held, _same")
                    line("MATCH ($rootVariable)-[r:$type]-(n)")
                }
            } else {
                line("MERGE ($rootVariable)${edge(relationship, "r")}(n)")
            }
            val sets = names.mapIndexed { index, name -> "r.$name = _q$index" } + Stamps.relink("n", "_same = 0", MARK)
            line("SET ${sets.joinToString(", ")}")
            val made = "sum(CASE WHEN _same = 0 THEN 1 ELSE 0 END) AS _made"
            if (handsBack) {
                line("WITH ${roots(depth)}, _ns, _is, collect(n) AS _n, collect(_i) AS _j, $made")
                line("SET ${Stamps.relink(rootVariable, "_made > 0", MARK)}")
                line("WITH ${roots(depth)}, _ns + _n AS _ns, _is + _j AS _is")
            } else {
                line("WITH $carried, $made")
                line("SET ${Stamps.relink(rootVariable, "_made > 0", MARK)}")
                line("WITH $carried")
            }
        }

        /** Saves the node [item] points at, then when [link] the relationship to it from the root at [depth]. */
        private fun relatedPart(relationship: RelationshipModel, target: Target, item: Any, depth: Int, link: Boolean, before: JsonNode?, after: JsonNode?) {
            val node = target.nodeOf(item)
            val nested = target.view
            if (nested != null) {
                view(node, nested, depth + 1, before, after)
                if (link) relationshipPart(relationship, item, depth, "_r${depth + 1}") else line("WITH ${carry(depth)}")
            } else {
                val model = FragmentModel.from(node.javaClass)
                add(
                    FragmentMergeBuilder(model, objectMapper, grammar, stamping = Stamping(false), carry = carry(depth) + ", ")
                        .buildMergeStatement(node, null)
                )
                if (link) {
                    line("WITH ${roots(depth)}, n, ${collected(node, model)}")
                    relationshipPart(relationship, item, depth, "n")
                } else {
                    line("WITH ${roots(depth)}, ${collected(node, model)}")
                }
            }
        }

        /**
         * Joins the root at [depth] to [targetVariable]. Both get a new relationship token if the
         * relationship was not there, or was there with other properties.
         */
        private fun relationshipPart(relationship: RelationshipModel, item: Any, depth: Int, targetVariable: String) {
            val part = parts++
            val rootVariable = "_r$depth"
            val scope = "${carry(depth)}, $targetVariable"
            val type = relationship.type
            val properties = if (relationship.isRelationshipFragment) {
                val values = objectMapper.toMap(item)
                relationship.relationshipProperties.map { name ->
                    val parameter = "p${part}_rel_$name"
                    bindings[parameter] = values[name]
                    Triple(name, parameter, values[name])
                }
            } else {
                emptyList()
            }
            val same = listOf("x IS NOT NULL") + properties.map { (name, parameter, value) ->
                if (value == null) "x.$name IS NULL" else "coalesce(x.$name = \$$parameter, false)"
            }
            val undirected = relationship.direction == Direction.UNDIRECTED

            line("OPTIONAL MATCH ($rootVariable)${edge(relationship, "x")}($targetVariable)")
            line("WITH $scope, count(x) AS _had, sum(CASE WHEN ${same.joinToString(" AND ")} THEN 1 ELSE 0 END) AS _same")
            if (undirected) {
                // A relationship is stored with a direction, and either one satisfies the field: it is
                // made, from the root, only when there is none.
                line("FOREACH (_ IN CASE WHEN _had = 0 THEN [1] ELSE [] END | CREATE ($rootVariable)-[:$type]->($targetVariable))")
                if (properties.isNotEmpty()) {
                    line("WITH $scope, _same")
                    line("MATCH ($rootVariable)-[r:$type]-($targetVariable)")
                }
            } else {
                line("MERGE ($rootVariable)${edge(relationship, "r")}($targetVariable)")
            }
            val sets = listOfNotNull(
                properties.takeIf { it.isNotEmpty() }?.let { all -> "r += {${all.joinToString(", ") { (name, parameter) -> "$name: \$$parameter" }}}" },
                Stamps.relink(rootVariable, "_same = 0", MARK),
                Stamps.relink(targetVariable, "_same = 0", MARK),
            )
            line("SET ${sets.joinToString(", ")}")
            // An undirected field can match a relationship each way, and so two rows.
            line("WITH DISTINCT ${carry(depth)}")
        }

        /**
         * Removes the relationships of a field from the root at [depth] to the targets whose ids are
         * [ids], or when [keep] to every target but those. The root, and each target that loses a
         * relationship, gets a new relationship token.
         */
        private fun removalPart(relationship: RelationshipModel, target: Target, depth: Int, ids: List<Any>, keep: Boolean, removedTargets: RemovedTargets) {
            val part = parts++
            val rootVariable = "_r$depth"
            val carried = carry(depth)
            bindings["p${part}_ids"] = ids
            line("OPTIONAL MATCH ($rootVariable)${edge(relationship, "r")}(target:${target.model.labels.joinToString(":")})")
            line("WHERE ${if (keep) "NOT " else ""}target.${target.idProperty} IN \$p${part}_ids")
            line("WITH $carried, target, collect(r) AS _rs")
            if (removedTargets == RemovedTargets.DELETE_UNREFERENCED) {
                // Counted before anything is deleted: one engine cannot test a pattern on a node whose
                // relationship the statement has deleted. What refers to a target is a relationship on
                // the side this field reaches it from.
                val references = when (relationship.direction) {
                    Direction.OUTGOING -> "()-->(target)"
                    Direction.INCOMING -> "(target)-->()"
                    Direction.UNDIRECTED -> "(target)--()"
                }
                line("WITH $carried, target, _rs, CASE WHEN target IS NULL THEN 0 ELSE size([ $references | 1 ]) END AS _refs")
            }
            line("FOREACH (x IN _rs | DELETE x)")
            line("FOREACH (t IN CASE WHEN target IS NOT NULL AND size(_rs) > 0 THEN [target] ELSE [] END | SET ${Stamps.relink("t", "true", MARK)})")
            if (removedTargets == RemovedTargets.DELETE_UNREFERENCED) {
                line("FOREACH (t IN CASE WHEN target IS NOT NULL AND _refs = size(_rs) THEN [target] ELSE [] END | DETACH DELETE t)")
            }
            line("WITH $carried, sum(size(_rs)) AS _removed")
            line("SET ${Stamps.relink(rootVariable, "_removed > 0", MARK)}")
            line("WITH $carried")
        }

        /** `_ns` and `_is`, with the node `n` added when its fragment declares a stamp to hand back. */
        private fun collected(fragment: Any, model: FragmentModel): String =
            if (model.stampField == null) {
                "_ns, _is"
            } else {
                stamped.add(fragment)
                "_ns + [n] AS _ns, _is + ['${stamped.size - 1}'] AS _is"
            }

        /** Appends a statement of its own as a part, its parameters renamed so no two parts share one. */
        private fun add(statement: MergeStatement) {
            val prefix = "p${parts++}_"
            var renamed = statement.statement
            statement.bindings.keys.sortedByDescending { it.length }.forEach { name ->
                renamed = renamed.replace(Regex("\\$" + Regex.escape(name) + "(?![A-Za-z0-9_])"), Regex.escapeReplacement("\$$prefix$name"))
            }
            statement.bindings.forEach { (name, value) -> bindings[prefix + name] = value }
            line(renamed)
        }

        private fun line(clause: String) {
            if (text.isNotEmpty()) text.append('\n')
            text.append(clause)
        }

        private fun roots(depth: Int): String = (0..depth).joinToString(", ") { "_r$it" }

        /** What is carried while the items of the view at [depth] are saved. */
        private fun carry(depth: Int): String = "${roots(depth)}, _ns, _is"
    }

    /** The relationship of [relationship] as `variable`, between root and target, as its field's direction reads it. */
    private fun edge(relationship: RelationshipModel, variable: String): String = when (relationship.direction) {
        Direction.OUTGOING -> "-[$variable:${relationship.type}]->"
        Direction.INCOMING -> "<-[$variable:${relationship.type}]-"
        Direction.UNDIRECTED -> "-[$variable:${relationship.type}]-"
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

        fun idOf(item: Any): Any? {
            val node = nodeOf(item)
            val fragment = view?.let { read(node, it.rootFragment.fieldName) } ?: node
            return objectMapper.toMap(fragment)[idField]
        }

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

    private companion object {
        const val STAMP = Stamps.QUOTED

        /** The stamp a statement offers every node whose relationships it changes, and every related fragment it changes. */
        const val MARK_PARAM = "_mark"
        const val MARK = "\$$MARK_PARAM"
    }
}

/** Reads the field [name] of [obj], wherever in its class hierarchy it is declared. */
internal fun read(obj: Any, name: String): Any? {
    var type: Class<*>? = obj.javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let { return it.apply { isAccessible = true }.get(obj) }
        type = type.superclass
    }
    throw NoSuchFieldException("${obj.javaClass.name} has no field '$name'")
}
