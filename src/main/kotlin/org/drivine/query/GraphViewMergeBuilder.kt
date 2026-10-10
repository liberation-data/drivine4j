package org.drivine.query

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.manager.CascadeType
import org.drivine.manager.NullPolicy
import org.drivine.mapper.toMap
import org.drivine.model.GraphViewModel
import org.drivine.model.FragmentModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.grammar.CypherGrammar
import org.drivine.session.SessionManager

/**
 * Builds Cypher MERGE statements for GraphView classes.
 *
 * Handles:
 * 1. Saving the root fragment
 * 2. Detecting relationship changes (added, removed, unchanged)
 * 3. Recursively saving nested fragments and views
 * 4. Creating/deleting relationships
 */
class GraphViewMergeBuilder(
    private val viewModel: GraphViewModel,
    private val objectMapper: ObjectMapper,
    private val sessionManager: SessionManager,
    private val grammar: CypherGrammar? = null,
    private val storedKeys: StoredPropertyKeys? = null,
    private val stamping: Stamping? = null,
    /** The only root-fragment fields this save may touch; null means every field. */
    private val rootWriteFields: Set<String>? = null,
) : GraphObjectMergeBuilder {

    /** Only the root is checked: a node reached through a relationship is written, and stamped, unchecked. */
    private val targetStamping = stamping?.unchecked()

    /**
     * Builds a list of Cypher statements to save a GraphView.
     * Returns statements in execution order.
     *
     * @param obj The GraphView object to save
     * @param cascade The cascade policy for deleted relationships
     * @return List of MergeStatements to execute in order
     */
    override fun <T : Any> buildMergeStatements(obj: T, cascade: CascadeType, nullPolicy: NullPolicy): List<MergeStatement> {
        // Get snapshot from session (if exists; an evicted view is untracked, which is a full save)
        val rootFragment = extractRootFragment(obj)
        val rootFragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val rootIdValue = sessionManager.extractIdValue(rootFragment, rootFragmentModel)?.toString()
        val snapshot = rootIdValue?.let { sessionManager.snapshotOf(obj.javaClass, it) }

        return buildMergeStatementsInternal(obj, snapshot, cascade, nullPolicy)
    }

    /**
     * Internal implementation that accepts an explicit snapshot digest and cascade policy.
     *
     * [nullPolicy] governs the **root** fragment write (the object being saved). Relationship-target and
     * nested-view fragment writes use the default [NullPolicy.IGNORE] (merge-patch) — they only ever
     * write the non-null fields present on the target, so they never clear a related node's stored data;
     * making that per-relationship-tunable is a targeted follow-up.
     */
    private fun <T : Any> buildMergeStatementsInternal(
        obj: T,
        snapshot: JsonNode?,
        cascade: CascadeType,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): List<MergeStatement> {
        val statements = mutableListOf<MergeStatement>()

        // 1. Save the root fragment
        val rootFragment = extractRootFragment(obj)
        val rootFragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val rootFragmentBuilder = FragmentMergeBuilder(rootFragmentModel, objectMapper, grammar, storedKeys, stamping)

        // Check if root fragment is dirty.
        // Prefer the enclosing view snapshot (the only place a fragment-inside-a-view's
        // previous state is recorded); fall back to session tracking, then to a full save.
        val previousRootFragment = snapshot?.get(viewModel.rootFragment.fieldName)
            ?: sessionManager.extractIdValue(rootFragment, rootFragmentModel)?.toString()
                ?.let { sessionManager.snapshotOf(rootFragment.javaClass, it) }
        val rootDirtyFields = previousRootFragment?.let { sessionManager.computeDirtyFields(rootFragment, it) }

        statements.add(rootFragmentBuilder.buildMergeStatement(rootFragment, rootDirtyFields, previousRootFragment, nullPolicy, rootWriteFields))

        // 2. Handle each relationship, diffing the current digest against the snapshot
        val current = snapshot?.let { sessionManager.digestOf(obj) }
        // A read-only field (every path is one) is loaded and never written.
        viewModel.relationships.filterNot { it.readOnly }.forEach { relModel ->
            statements.addAll(buildRelationshipStatements(obj, current, snapshot, relModel, rootFragment, rootFragmentModel, cascade))
        }

        return statements
    }

    /**
     * Builds statements for a single relationship field.
     * Detects added/removed items and generates appropriate queries.
     *
     * Under [CascadeType.DELETE_ORPHAN] the current items are authoritative, so removals are computed
     * by the database — every edge of this type to a target not in the list — rather than from the
     * snapshot. That holds whether or not the view is tracked (never loaded, evicted) and however stale
     * its snapshot is.
     */
    private fun <T : Any> buildRelationshipStatements(
        obj: T,
        current: JsonNode?,
        snapshot: JsonNode?,
        relModel: RelationshipModel,
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        cascade: CascadeType
    ): List<MergeStatement> {
        val statements = mutableListOf<MergeStatement>()

        // Extract current relationship value
        val field = obj.javaClass.getDeclaredField(relModel.fieldName)
        field.isAccessible = true
        val currentValue = field.get(obj)

        // Convert to lists for comparison
        val currentItems = if (relModel.isCollection) {
            (currentValue as? Collection<*>)?.toList() ?: emptyList()
        } else {
            listOfNotNull(currentValue)
        }

        // Detect changes (no snapshot = treat as all new)
        val (added, removed, unchanged) = if (current != null && snapshot != null) {
            detectChanges(
                currentItems,
                digestItems(current.get(relModel.fieldName), relModel),
                digestItems(snapshot.get(relModel.fieldName), relModel).filterNotNull(),
                relModel,
            )
        } else {
            Triple(currentItems.filterNotNull(), emptyList(), emptyList())
        }

        // Generate statements for removed relationships (skip for PRESERVE — append-only mode)
        when (cascade) {
            CascadeType.PRESERVE -> Unit
            CascadeType.DELETE_ORPHAN ->
                statements.add(buildOrphanReconcileStatement(rootFragment, rootFragmentModel, currentItems.filterNotNull(), relModel))
            else -> removed.forEach { removedItem ->
                statements.add(buildDeleteRelationshipStatement(rootFragment, rootFragmentModel, removedItem, relModel, cascade))
            }
        }

        // Generate statements for added relationships
        added.forEach { addedItem ->
            statements.addAll(buildAddRelationshipStatements(rootFragment, rootFragmentModel, addedItem, relModel))
        }

        // Generate statements for unchanged relationships whose target node/view properties changed.
        // The relationship itself is neither added nor removed, but the related fragment may be dirty.
        unchanged.forEach { (currentItem, snapshotItem) ->
            statements.addAll(buildUnchangedTargetStatements(currentItem, snapshotItem, relModel, cascade))
        }

        return statements
    }

    /** The items of a digested relationship field, index-aligned with the field's collection. */
    private fun digestItems(node: JsonNode?, relModel: RelationshipModel): List<JsonNode?> = when {
        node == null || node.isNull -> emptyList()
        relModel.isCollection -> node.map { item -> item.takeUnless { it.isNull } }
        else -> listOf(node)
    }

    /**
     * Builds statements to persist property changes on an unchanged relationship's target.
     *
     * The relationship link is unchanged (same target ID, and for relationship fragments the
     * relationship properties are unchanged too), but the target node — or, for a nested view,
     * any fragment within it — may have dirty properties that must still be written.
     *
     * - Fragment target: emit a dirty-field SET on the target node (nothing if nothing changed).
     * - Nested view target: recurse, diffing the current target view against its snapshot so the
     *   nested root and its own relationships are reconciled too.
     */
    private fun buildUnchangedTargetStatements(
        currentItem: Any,
        snapshotItem: JsonNode,
        relModel: RelationshipModel,
        cascade: CascadeType
    ): List<MergeStatement> {
        val currentTarget = extractTargetNode(currentItem, relModel)
        val snapshotTarget = targetDigest(snapshotItem, relModel)

        val targetClass = if (relModel.isRelationshipFragment) relModel.targetNodeType!! else relModel.elementType
        val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        return if (isView) {
            // Recurse into the nested view, diffing against the snapshot view.
            val nestedViewModel = GraphViewModel.from(targetClass)
            val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager, stamping = targetStamping)
            nestedViewBuilder.buildMergeStatementsInternal(currentTarget, snapshotTarget, cascade)
        } else {
            // Direct fragment target: write only the dirty fields, if any.
            val dirtyFields = sessionManager.computeDirtyFields(currentTarget, snapshotTarget)
            if (dirtyFields.isEmpty()) {
                emptyList()
            } else {
                // IMPORTANT: Use runtime type, not declared type, for correct labels on polymorphic types.
                val targetFragmentModel = FragmentModel.from(currentTarget::class.java)
                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper, grammar, stamping = targetStamping)
                listOf(fragmentBuilder.buildMergeStatement(currentTarget, dirtyFields, snapshotTarget))
            }
        }
    }

    /**
     * Detects which related items were added, removed, or unchanged, using ID-based comparison of the
     * current items against the snapshot's digests. Handles GraphFragments, nested GraphViews, and
     * relationship fragments.
     *
     * - added:     present in current but not in snapshot (new ID), or — for relationship
     *              fragments — present in both but with changed relationship properties (re-MERGE).
     * - removed:   the target IDs present in snapshot but not in current.
     * - unchanged: present in both with the same ID (and, for relationship fragments, unchanged
     *              relationship properties). The link is unchanged, but the target node/view may
     *              still hold dirty properties — returned as (currentItem, snapshotItem) pairs so
     *              the caller can reconcile those. See [buildUnchangedTargetStatements].
     */
    private fun detectChanges(
        current: List<Any?>,
        currentDigests: List<JsonNode?>,
        snapshot: List<JsonNode>,
        relModel: RelationshipModel
    ): Triple<List<Any>, List<String>, List<Pair<Any, JsonNode>>> {
        val target = declaredTarget(relModel)

        // Build ordered ID -> item maps for both sides (preserve declaration order).
        val currentById = LinkedHashMap<String, Int>()
        current.forEachIndexed { index, item -> if (item != null) targetId(item, relModel)?.let { currentById[it] = index } }

        val snapshotById = LinkedHashMap<String, JsonNode>()
        snapshot.forEach { item -> targetDigestId(item, relModel, target)?.let { snapshotById[it] = item } }

        val added = mutableListOf<Any>()
        val unchanged = mutableListOf<Pair<Any, JsonNode>>()

        currentById.forEach { (id, index) ->
            val currentItem = current[index]!!
            val snapshotItem = snapshotById[id]
            when {
                snapshotItem == null -> {
                    // New target ID
                    added.add(currentItem)
                }
                relModel.isRelationshipFragment -> {
                    // Same target ID - re-MERGE only if the relationship properties changed.
                    val currentProps = relModel.relationshipProperties.associateWith { currentDigests.getOrNull(index)?.get(it) }
                    val snapshotProps = relModel.relationshipProperties.associateWith { snapshotItem.get(it) }
                    if (currentProps != snapshotProps) {
                        added.add(currentItem)
                    } else {
                        unchanged.add(currentItem to snapshotItem)
                    }
                }
                else -> {
                    unchanged.add(currentItem to snapshotItem)
                }
            }
        }

        // Targets present in snapshot but not in current are removed.
        val removed = snapshotById.keys.filter { it !in currentById.keys }

        return Triple(added, removed, unchanged)
    }

    /**
     * The declared target of a relationship: whether it is a nested view, and the fragment model its
     * ID and labels come from (for a view, its root fragment). Polymorphic targets carry at least the
     * declared type's labels, so the declared model is enough to MATCH a target by ID.
     */
    private data class DeclaredTarget(val isView: Boolean, val viewModel: GraphViewModel?, val fragmentModel: FragmentModel) {
        val idField: String
            get() = requireNotNull(fragmentModel.nodeIdField) { "Relationship target ${fragmentModel.className} has no @GraphNodeId" }
    }

    private fun declaredTarget(relModel: RelationshipModel): DeclaredTarget {
        val targetClass = if (relModel.isRelationshipFragment) relModel.targetNodeType!! else relModel.elementType
        return if (targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)) {
            val nestedViewModel = GraphViewModel.from(targetClass)
            DeclaredTarget(true, nestedViewModel, FragmentModel.from(nestedViewModel.rootFragment.fragmentType))
        } else {
            DeclaredTarget(false, null, FragmentModel.from(targetClass))
        }.also {
            requireNotNull(it.fragmentModel.nodeIdField) {
                "Cannot detect changes for relationship without @GraphNodeId: ${relModel.fieldName}"
            }
        }
    }

    /** The ID of a current relationship item's target fragment (for a view, its root), as stored. */
    private fun targetId(item: Any, relModel: RelationshipModel): String? {
        val target = declaredTarget(relModel)
        val targetNode = extractTargetNode(item, relModel)
        val fragment = target.viewModel?.let { extractRootFragmentFromObject(targetNode, it) } ?: targetNode
        return sessionManager.extractIdValue(fragment, target.fragmentModel)?.toString()
    }

    /** The ID of a snapshot item's target fragment, read from its digest (IDs are kept verbatim). */
    private fun targetDigestId(item: JsonNode, relModel: RelationshipModel, target: DeclaredTarget): String? {
        val targetNode = targetDigest(item, relModel)
        val fragment = target.viewModel?.let { targetNode.get(it.rootFragment.fieldName) } ?: targetNode
        return fragment?.get(target.idField)?.takeUnless { it.isNull }?.asText()
    }

    /** The target's digest within a snapshot item: the item itself, or a relationship fragment's target. */
    private fun targetDigest(item: JsonNode, relModel: RelationshipModel): JsonNode =
        if (relModel.isRelationshipFragment) {
            requireNotNull(item.get(relModel.targetFieldName!!)) { "Target field is null" }
        } else {
            item
        }

    /**
     * Extracts the actual target node from a relationship item. For a relationship fragment this is
     * the field annotated as the target node; otherwise the item is itself the target.
     */
    private fun extractTargetNode(item: Any, relModel: RelationshipModel): Any {
        return if (relModel.isRelationshipFragment) {
            val targetField = item.javaClass.getDeclaredField(relModel.targetFieldName!!)
            targetField.isAccessible = true
            targetField.get(item) ?: throw IllegalArgumentException("Target field is null")
        } else {
            item
        }
    }

    /** The relationship `r` of [relModel] between root and target, as its field's direction reads it. */
    private fun matchEdge(relModel: RelationshipModel): String = when (relModel.direction) {
        Direction.OUTGOING -> "-[r:${relModel.type}]->"
        Direction.INCOMING -> "<-[r:${relModel.type}]-"
        Direction.UNDIRECTED -> "-[r:${relModel.type}]-"
    }

    /**
     * The relationship of [relModel] to merge between root and target, in its field's direction. An
     * undirected field is merged undirected: a relationship stored either way satisfies it, and one is
     * made, from the root, only when there is none.
     */
    private fun mergeEdge(relModel: RelationshipModel, variable: String = ""): String = when (relModel.direction) {
        Direction.INCOMING -> "<-[$variable:${relModel.type}]-"
        Direction.OUTGOING -> "-[$variable:${relModel.type}]->"
        Direction.UNDIRECTED -> "-[$variable:${relModel.type}]-"
    }

    /**
     * Builds a DELETE statement for a relationship whose target [targetId] was in the snapshot and is
     * no longer present. Behavior depends on cascade policy:
     * - NONE: Only deletes the relationship
     * - DELETE_ALL: Deletes relationship + target (DETACH DELETE; for a view, its root fragment)
     *
     * DELETE_ORPHAN does not come here: see [buildOrphanReconcileStatement].
     */
    private fun buildDeleteRelationshipStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetId: String,
        relModel: RelationshipModel,
        cascade: CascadeType
    ): MergeStatement {
        val target = declaredTarget(relModel)
        val rootProps = objectMapper.toMap(rootFragment)

        // Nodes are matched on the id's on-disk property name, which a @GraphProperty can make differ from the field's.
        val rootIdField = rootFragmentModel.nodeIdProperty ?: rootFragmentModel.nodeIdField!!
        val targetIdField = target.fragmentModel.nodeIdProperty ?: target.idField

        val rootLabels = rootFragmentModel.labels.joinToString(":")
        val targetLabels = target.fragmentModel.labels.joinToString(":")

        val query = when (cascade) {
            CascadeType.NONE -> {
                // Only delete the relationship
                """
                    MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                    MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                    MATCH (root)${matchEdge(relModel)}(target)
                    DELETE r
                    $relinked
                """.trimIndent()
            }
            CascadeType.DELETE_ALL -> {
                // DETACH DELETE the target node (for a view, its root fragment): removes ALL its
                // relationships, not just ours. This is the nuclear option - deletes even if other
                // references exist. It leaves a view's related fragments intact.
                """
                    MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                    DETACH DELETE target
                """.trimIndent()
            }
            CascadeType.DELETE_ORPHAN, CascadeType.PRESERVE -> {
                // Should never be reached — both are handled upstream
                throw IllegalStateException("$cascade cascade should not generate per-target delete statements")
            }
        }

        return MergeStatement(
            statement = query,
            bindings = mapOf(
                "rootId" to rootProps[rootFragmentModel.nodeIdField!!],
                "targetId" to targetId
            ) + mark()
        )
    }

    /**
     * Builds the DELETE_ORPHAN reconcile for one relationship field: deletes every edge of this type
     * from the root to a target not among [currentItems], then deletes each such target left with no
     * relationships at all. The current items are the authority, so no snapshot is needed.
     */
    private fun buildOrphanReconcileStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        currentItems: List<Any>,
        relModel: RelationshipModel,
    ): MergeStatement {
        val target = declaredTarget(relModel)
        val rootIdField = rootFragmentModel.nodeIdField!!
        val rootIdProperty = rootFragmentModel.nodeIdProperty ?: rootIdField
        val targetIdProperty = target.fragmentModel.nodeIdProperty ?: target.idField
        val rootLabels = rootFragmentModel.labels.joinToString(":")
        val targetLabels = target.fragmentModel.labels.joinToString(":")

        val query = """
            MATCH (root:$rootLabels {$rootIdProperty: ${'$'}rootId})${matchEdge(relModel)}(target:$targetLabels)
            WHERE NOT target.$targetIdProperty IN ${'$'}keepIds
            DELETE r
            $relinked
            WITH DISTINCT target
            WHERE NOT (target)<-[]-() AND NOT (target)-[]-()
            DETACH DELETE target
        """.trimIndent()

        return MergeStatement(
            statement = query,
            bindings = mapOf(
                "rootId" to objectMapper.toMap(rootFragment)[rootIdField],
                "keepIds" to currentItems.mapNotNull { targetId(it, relModel) },
            ) + mark()
        )
    }

    /**
     * Builds statements for adding a relationship.
     * Handles both GraphFragments and nested GraphViews.
     *
     * For fragments:
     * 1. MERGE the target fragment (with dirty tracking)
     * 2. CREATE/MERGE the relationship
     *
     * For nested views:
     * 1. Recursively save the nested view (which handles its own relationships)
     * 2. CREATE/MERGE the relationship to the nested view's root fragment
     */
    private fun buildAddRelationshipStatements(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetItem: Any,
        relModel: RelationshipModel
    ): List<MergeStatement> {
        val statements = mutableListOf<MergeStatement>()

        if (relModel.isRelationshipFragment) {
            // Handle relationship fragment: extract target node and save it
            val targetNodeField = targetItem.javaClass.getDeclaredField(relModel.targetFieldName!!)
            targetNodeField.isAccessible = true
            val targetNode = targetNodeField.get(targetItem)
                ?: throw IllegalArgumentException("Target node field '${relModel.targetFieldName}' is null in relationship fragment")

            val targetNodeClass = relModel.targetNodeType!!
            val isView = targetNodeClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

            if (isView) {
                // Handle nested GraphView - recursively build its merge statements
                val nestedViewModel = GraphViewModel.from(targetNodeClass)
                val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager, stamping = targetStamping)
                statements.addAll(nestedViewBuilder.buildMergeStatements(targetNode))

                // Now create relationship to the nested view's root fragment with relationship properties
                val targetRootFragment = extractRootFragmentFromObject(targetNode, nestedViewModel)
                val targetFragmentModel = FragmentModel.from(nestedViewModel.rootFragment.fragmentType)

                statements.add(buildRelationshipMergeStatement(
                    rootFragment, rootFragmentModel,
                    targetRootFragment, targetFragmentModel,
                    relModel,
                    relationshipFragment = targetItem
                ))
            } else {
                // Handle GraphFragment target
                // IMPORTANT: Use runtime type, not declared type, to get correct labels for polymorphic types
                val targetFragmentModel = FragmentModel.from(targetNode::class.java)

                // 1. MERGE the target fragment
                val targetId = sessionManager.extractIdValue(targetNode, targetFragmentModel)?.toString()
                val targetDirtyFields = if (targetId != null) {
                    sessionManager.getDirtyFields(targetNode, targetId)
                } else null

                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper, grammar, stamping = targetStamping)
                statements.add(fragmentBuilder.buildMergeStatement(targetNode, targetDirtyFields))

                // 2. CREATE/MERGE the relationship with properties
                statements.add(buildRelationshipMergeStatement(
                    rootFragment, rootFragmentModel,
                    targetNode, targetFragmentModel,
                    relModel,
                    relationshipFragment = targetItem
                ))
            }
        } else {
            // Direct target reference (existing behavior)
            val targetClass = relModel.elementType
            val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

            if (isView) {
                // Handle nested GraphView - recursively build its merge statements
                val nestedViewModel = GraphViewModel.from(targetClass)
                val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager, stamping = targetStamping)
                statements.addAll(nestedViewBuilder.buildMergeStatements(targetItem))

                // Now create relationship to the nested view's root fragment
                val targetRootFragment = extractRootFragmentFromObject(targetItem, nestedViewModel)
                val targetFragmentModel = FragmentModel.from(nestedViewModel.rootFragment.fragmentType)

                statements.add(buildRelationshipMergeStatement(
                    rootFragment, rootFragmentModel,
                    targetRootFragment, targetFragmentModel,
                    relModel
                ))
            } else {
                // Handle GraphFragment
                // IMPORTANT: Use runtime type, not declared type, to get correct labels for polymorphic types
                val targetFragmentModel = FragmentModel.from(targetItem::class.java)

                // 1. MERGE the target fragment
                val targetId = sessionManager.extractIdValue(targetItem, targetFragmentModel)?.toString()
                val targetDirtyFields = if (targetId != null) {
                    sessionManager.getDirtyFields(targetItem, targetId)
                } else null

                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper, grammar, stamping = targetStamping)
                statements.add(fragmentBuilder.buildMergeStatement(targetItem, targetDirtyFields))

                // 2. CREATE/MERGE the relationship
                statements.add(buildRelationshipMergeStatement(
                    rootFragment, rootFragmentModel,
                    targetItem, targetFragmentModel,
                    relModel
                ))
            }
        }

        return statements
    }

    /**
     * Builds a MERGE statement for a relationship between two fragments.
     * Uses objectMapper.toMap() to ensure proper type conversion (e.g., UUID -> String).
     * Supports relationship properties for @GraphRelationshipFragment.
     */
    private fun buildRelationshipMergeStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetFragment: Any,
        targetFragmentModel: FragmentModel,
        relModel: RelationshipModel,
        relationshipFragment: Any? = null
    ): MergeStatement {
        // Convert to map to get properly converted ID values (UUID -> String, etc.)
        val rootProps = objectMapper.toMap(rootFragment)
        val targetProps = objectMapper.toMap(targetFragment)

        val rootId = rootProps[rootFragmentModel.nodeIdField!!]
        val targetId = targetProps[targetFragmentModel.nodeIdField!!]

        // Nodes are matched on the id's on-disk property name, which a @GraphProperty can make differ from the field's.
        val rootIdField = rootFragmentModel.nodeIdProperty ?: rootFragmentModel.nodeIdField!!
        val targetIdField = targetFragmentModel.nodeIdProperty ?: targetFragmentModel.nodeIdField!!

        val rootLabels = rootFragmentModel.labels.joinToString(":")
        val targetLabels = targetFragmentModel.labels.joinToString(":")

        val bindings = mutableMapOf<String, Any?>(
            "rootId" to rootId,
            "targetId" to targetId
        )

        val query = if (relModel.isRelationshipFragment && relationshipFragment != null) {
            // Relationship fragment: set properties on the relationship
            val relProps = objectMapper.toMap(relationshipFragment)
            val relPropsString = relModel.relationshipProperties.joinToString(", ") { propName ->
                bindings["rel_$propName"] = relProps[propName]
                "$propName: \$rel_$propName"
            }

            """
                MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                MERGE (root)${mergeEdge(relModel, "r")}(target)
                SET r += {$relPropsString}
                $relinked
            """.trimIndent()
        } else {
            // Direct target reference: simple MERGE with no properties
            """
                MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                MERGE (root)${mergeEdge(relModel)}(target)
                $relinked
            """.trimIndent()
        }

        return MergeStatement(
            statement = query,
            bindings = bindings + mark()
        )
    }

    /**
     * The clause that gives `root` and `target` a new relationship token, appended to a statement that
     * writes or removes a relationship between them; empty when this builder does not stamp. This
     * manager does not look at whether the relationship was already as written.
     */
    private val relinked: String =
        if (stamping == null) "" else "SET ${Stamps.relink("root", "true", "\$$MARK")}, ${Stamps.relink("target", "true", "\$$MARK")}"

    private fun mark(): Map<String, Any?> = if (stamping == null) emptyMap() else mapOf(MARK to Stamps.fresh())

    /**
     * Extracts the root fragment from a nested GraphView object.
     */
    private fun extractRootFragmentFromObject(obj: Any, viewModel: GraphViewModel): Any {
        val field = obj.javaClass.getDeclaredField(viewModel.rootFragment.fieldName)
        field.isAccessible = true
        return field.get(obj)
            ?: throw IllegalArgumentException("Root fragment ${viewModel.rootFragment.fieldName} is null")
    }

    /**
     * Extracts the root fragment from a GraphView object.
     */
    private fun <T : Any> extractRootFragment(obj: T): Any {
        val field = obj.javaClass.getDeclaredField(viewModel.rootFragment.fieldName)
        field.isAccessible = true
        return field.get(obj)
            ?: throw IllegalArgumentException("Root fragment ${viewModel.rootFragment.fieldName} is null")
    }
}

/** The parameter a relationship statement takes a new relationship token from. */
private const val MARK = "_mark"
