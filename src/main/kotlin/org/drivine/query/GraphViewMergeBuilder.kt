package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.manager.CascadeType
import org.drivine.mapper.toMap
import org.drivine.model.GraphViewModel
import org.drivine.model.FragmentModel
import org.drivine.model.RelationshipModel
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
    private val sessionManager: SessionManager
) : GraphObjectMergeBuilder {

    /**
     * Builds a list of Cypher statements to save a GraphView.
     * Returns statements in execution order.
     *
     * @param obj The GraphView object to save
     * @param cascade The cascade policy for deleted relationships
     * @return List of MergeStatements to execute in order
     */
    override fun <T : Any> buildMergeStatements(obj: T, cascade: CascadeType): List<MergeStatement> {
        // Get snapshot from session (if exists)
        val rootFragment = extractRootFragment(obj)
        val rootFragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val rootIdValue = sessionManager.extractIdValue(rootFragment, rootFragmentModel)?.toString()

        val snapshot = if (rootIdValue != null && sessionManager.isTracked(obj.javaClass, rootIdValue)) {
            @Suppress("UNCHECKED_CAST")
            sessionManager.getSnapshot(obj.javaClass as Class<T>, rootIdValue)
        } else {
            null
        }

        return buildMergeStatementsInternal(obj, snapshot, cascade)
    }

    /**
     * Internal implementation that accepts an explicit snapshot parameter and cascade policy.
     */
    private fun <T : Any> buildMergeStatementsInternal(obj: T, snapshot: Any?, cascade: CascadeType): List<MergeStatement> {
        val statements = mutableListOf<MergeStatement>()

        // 1. Save the root fragment
        val rootFragment = extractRootFragment(obj)
        val rootFragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val rootFragmentBuilder = FragmentMergeBuilder(rootFragmentModel, objectMapper)

        // Check if root fragment is dirty.
        // Prefer the enclosing view snapshot (the only place a fragment-inside-a-view's
        // previous state is recorded); fall back to session tracking, then to a full save.
        val rootDirtyFields = if (snapshot != null) {
            sessionManager.computeDirtyFields(rootFragment, extractRootFragment(snapshot))
        } else {
            val rootIdValue = sessionManager.extractIdValue(rootFragment, rootFragmentModel)?.toString()
            if (rootIdValue != null) sessionManager.getDirtyFields(rootFragment, rootIdValue) else null
        }

        statements.add(rootFragmentBuilder.buildMergeStatement(rootFragment, rootDirtyFields))

        // 2. Handle each relationship
        viewModel.relationships.forEach { relModel ->
            statements.addAll(buildRelationshipStatements(obj, snapshot, relModel, rootFragment, rootFragmentModel, cascade))
        }

        return statements
    }

    /**
     * Builds statements for a single relationship field.
     * Detects added/removed items and generates appropriate queries.
     */
    private fun <T : Any> buildRelationshipStatements(
        obj: T,
        snapshot: Any?,
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

        // Extract snapshot relationship value (if exists)
        val snapshotValue = if (snapshot != null) {
            val snapshotField = snapshot.javaClass.getDeclaredField(relModel.fieldName)
            snapshotField.isAccessible = true
            snapshotField.get(snapshot)
        } else null

        // Convert to lists for comparison
        val currentItems = if (relModel.isCollection) {
            (currentValue as? Collection<*>)?.toList() ?: emptyList()
        } else {
            listOfNotNull(currentValue)
        }

        val snapshotItems = if (snapshotValue != null) {
            if (relModel.isCollection) {
                (snapshotValue as? Collection<*>)?.toList() ?: emptyList()
            } else {
                listOfNotNull(snapshotValue)
            }
        } else {
            null // No snapshot = treat as all new
        }

        // Detect changes
        val (added, removed, unchanged) = if (snapshotItems != null) {
            detectChanges(currentItems, snapshotItems, relModel)
        } else {
            // No snapshot = all current items are "added"
            Triple(currentItems.filterNotNull(), emptyList<Any>(), emptyList<Pair<Any, Any>>())
        }

        // Generate statements for removed relationships (skip for PRESERVE — append-only mode)
        if (cascade != CascadeType.PRESERVE) {
            removed.forEach { removedItem ->
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
        snapshotItem: Any,
        relModel: RelationshipModel,
        cascade: CascadeType
    ): List<MergeStatement> {
        val currentTarget = extractTargetNode(currentItem, relModel)
        val snapshotTarget = extractTargetNode(snapshotItem, relModel)

        val targetClass = if (relModel.isRelationshipFragment) relModel.targetNodeType!! else relModel.elementType
        val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        return if (isView) {
            // Recurse into the nested view, diffing against the snapshot view.
            val nestedViewModel = GraphViewModel.from(targetClass)
            val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager)
            nestedViewBuilder.buildMergeStatementsInternal(currentTarget, snapshotTarget, cascade)
        } else {
            // Direct fragment target: write only the dirty fields, if any.
            val dirtyFields = sessionManager.computeDirtyFields(currentTarget, snapshotTarget)
            if (dirtyFields.isEmpty()) {
                emptyList()
            } else {
                // IMPORTANT: Use runtime type, not declared type, for correct labels on polymorphic types.
                val targetFragmentModel = FragmentModel.from(currentTarget::class.java)
                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper)
                listOf(fragmentBuilder.buildMergeStatement(currentTarget, dirtyFields))
            }
        }
    }

    /**
     * Detects which related items were added, removed, or unchanged, using ID-based comparison.
     * Handles GraphFragments, nested GraphViews, and relationship fragments.
     *
     * - added:     present in current but not in snapshot (new ID), or — for relationship
     *              fragments — present in both but with changed relationship properties (re-MERGE).
     * - removed:   present in snapshot but not in current.
     * - unchanged: present in both with the same ID (and, for relationship fragments, unchanged
     *              relationship properties). The link is unchanged, but the target node/view may
     *              still hold dirty properties — returned as (currentItem, snapshotItem) pairs so
     *              the caller can reconcile those. See [buildUnchangedTargetStatements].
     */
    private fun detectChanges(
        current: List<Any?>,
        snapshot: List<Any?>,
        relModel: RelationshipModel
    ): Triple<List<Any>, List<Any>, List<Pair<Any, Any>>> {
        // For relationship fragments, we need to extract the target node for comparison
        val actualTargetClass = if (relModel.isRelationshipFragment) {
            relModel.targetNodeType!!
        } else {
            relModel.elementType
        }

        val isView = actualTargetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        val fragmentModel = if (isView) {
            // For views, use the root fragment's model for ID extraction
            FragmentModel.from(GraphViewModel.from(actualTargetClass).rootFragment.fragmentType)
        } else {
            FragmentModel.from(actualTargetClass)
        }

        fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot detect changes for relationship without @GraphNodeId: ${relModel.fieldName}")

        // Extract the ID for an item - for views, extract from the root fragment.
        fun idOf(item: Any): Any? {
            val targetNode = extractTargetNode(item, relModel)
            val fragment = if (isView) {
                extractRootFragmentFromObject(targetNode, GraphViewModel.from(actualTargetClass))
            } else {
                targetNode
            }
            return sessionManager.extractIdValue(fragment, fragmentModel)
        }

        // Build ordered ID -> item maps for both sides (preserve declaration order).
        val currentById = LinkedHashMap<Any?, Any>()
        current.forEach { item -> if (item != null) idOf(item)?.let { currentById[it] = item } }

        val snapshotById = LinkedHashMap<Any?, Any>()
        snapshot.forEach { item -> if (item != null) idOf(item)?.let { snapshotById[it] = item } }

        val added = mutableListOf<Any>()
        val unchanged = mutableListOf<Pair<Any, Any>>()

        currentById.forEach { (id, currentItem) ->
            val snapshotItem = snapshotById[id]
            when {
                snapshotItem == null -> {
                    // New target ID
                    added.add(currentItem)
                }
                relModel.isRelationshipFragment -> {
                    // Same target ID - re-MERGE only if the relationship properties changed.
                    val currentProps = objectMapper.toMap(currentItem)
                        .filterKeys { it in relModel.relationshipProperties }
                    val snapshotProps = objectMapper.toMap(snapshotItem)
                        .filterKeys { it in relModel.relationshipProperties }
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

        // Items present in snapshot but not in current are removed.
        val removed = snapshotById.filterKeys { it !in currentById.keys }.values.toList()

        return Triple(added, removed, unchanged)
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

    /**
     * Builds a DELETE statement for removing a relationship.
     * Behavior depends on cascade policy:
     * - NONE: Only deletes the relationship
     * - DELETE_ALL: Deletes relationship + target (DETACH DELETE for fragments, recursive for views)
     * - DELETE_ORPHAN: TODO
     *
     * Handles both GraphFragments and nested GraphViews.
     * Uses objectMapper.toMap() to ensure proper type conversion (e.g., UUID -> String).
     */
    private fun buildDeleteRelationshipStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetItem: Any,
        relModel: RelationshipModel,
        cascade: CascadeType
    ): MergeStatement {
        // For relationship fragments, extract the actual target node
        val actualTargetItem = if (relModel.isRelationshipFragment) {
            val targetField = targetItem.javaClass.getDeclaredField(relModel.targetFieldName!!)
            targetField.isAccessible = true
            targetField.get(targetItem) ?: throw IllegalArgumentException("Target field is null")
        } else {
            targetItem
        }

        // Check if target is a GraphView or GraphFragment
        val targetClass = if (relModel.isRelationshipFragment) {
            relModel.targetNodeType!!
        } else {
            relModel.elementType
        }
        val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        val (targetFragment, targetFragmentModel) = if (isView) {
            // For nested view, extract root fragment
            val viewModel = GraphViewModel.from(targetClass)
            val fragment = extractRootFragmentFromObject(actualTargetItem, viewModel)
            Pair(fragment, FragmentModel.from(viewModel.rootFragment.fragmentType))
        } else {
            // IMPORTANT: Use runtime type, not declared type, to get correct labels for polymorphic types
            Pair(actualTargetItem, FragmentModel.from(actualTargetItem::class.java))
        }

        // Convert to map to get properly converted ID values (UUID -> String, etc.)
        val rootProps = objectMapper.toMap(rootFragment)
        val targetProps = objectMapper.toMap(targetFragment)

        val rootIdField = rootFragmentModel.nodeIdField!!
        val targetIdField = targetFragmentModel.nodeIdField!!

        val rootId = rootProps[rootIdField]
        val targetId = targetProps[targetIdField]

        val rootLabels = rootFragmentModel.labels.joinToString(":")
        val targetLabels = targetFragmentModel.labels.joinToString(":")

        val query = when (cascade) {
            CascadeType.NONE -> {
                // Only delete the relationship
                """
                    MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                    MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                    MATCH (root)-[r:${relModel.type}]->(target)
                    DELETE r
                """.trimIndent()
            }
            CascadeType.DELETE_ALL -> {
                if (isView) {
                    // For views: recursively delete all fragments and relationships
                    // We build this by traversing the view model and deleting everything
                    buildCascadeDeleteForView(targetItem, targetClass)
                } else {
                    // For fragments: DETACH DELETE the node (removes ALL relationships, not just ours)
                    // This is the nuclear option - deletes even if other references exist
                    """
                        MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                        DETACH DELETE target
                    """.trimIndent()
                }
            }
            CascadeType.DELETE_ORPHAN -> {
                if (isView) {
                    // For views: delete root fragment only if orphaned after our relationship is removed
                    buildOrphanDeleteForView(rootLabels, rootIdField, targetLabels, targetIdField, relModel.type)
                } else {
                    // For fragments: two-step operation
                    // 1. Delete our relationship
                    // 2. Check if target is now orphaned (no other relationships)
                    // 3. If orphaned, delete target
                    """
                        MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                        MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                        MATCH (root)-[r:${relModel.type}]->(target)
                        DELETE r
                        WITH target
                        WHERE NOT (target)<-[]-() AND NOT (target)-[]-()
                        DELETE target
                    """.trimIndent()
                }
            }
            CascadeType.PRESERVE -> {
                // Should never be reached — PRESERVE removals are filtered upstream
                throw IllegalStateException("PRESERVE cascade should not generate delete statements")
            }
        }

        return MergeStatement(
            statement = query,
            bindings = mapOf(
                "rootId" to rootId,
                "targetId" to targetId
            )
        )
    }

    /**
     * Builds a cascade delete query for a GraphView (DELETE_ALL).
     * Recursively deletes all fragments in the view and their relationships.
     */
    private fun buildCascadeDeleteForView(viewObj: Any, viewClass: Class<*>): String {
        val viewModel = GraphViewModel.from(viewClass)
        val rootFragment = extractRootFragmentFromObject(viewObj, viewModel)
        val rootFragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)

        val rootProps = objectMapper.toMap(rootFragment)
        val rootIdField = rootFragmentModel.nodeIdField!!
        val rootLabels = rootFragmentModel.labels.joinToString(":")

        // For now, simple approach: DETACH DELETE the root fragment
        // This deletes all relationships but leaves related fragments intact
        // TODO: For true recursive delete, we'd need to traverse all relationships
        return """
            MATCH (root:$rootLabels {$rootIdField: ${'$'}targetId})
            DETACH DELETE root
        """.trimIndent()
    }

    /**
     * Builds an orphan delete query for a GraphView (DELETE_ORPHAN).
     * Deletes the root fragment only if it has no other relationships after ours is removed.
     */
    private fun buildOrphanDeleteForView(
        rootLabels: String,
        rootIdField: String,
        targetLabels: String,
        targetIdField: String,
        relType: String
    ): String {
        // Two-step: delete relationship, then delete target if orphaned
        return """
            MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
            MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
            MATCH (root)-[r:$relType]->(target)
            DELETE r
            WITH target
            WHERE NOT (target)<-[]-() AND NOT (target)-[]-()
            DETACH DELETE target
        """.trimIndent()
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
                val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager)
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

                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper)
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
                val nestedViewBuilder = GraphViewMergeBuilder(nestedViewModel, objectMapper, sessionManager)
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

                val fragmentBuilder = FragmentMergeBuilder(targetFragmentModel, objectMapper)
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

        val rootIdField = rootFragmentModel.nodeIdField!!
        val targetIdField = targetFragmentModel.nodeIdField!!

        val rootId = rootProps[rootIdField]
        val targetId = targetProps[targetIdField]

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
                MERGE (root)-[r:${relModel.type}]->(target)
                SET r += {$relPropsString}
            """.trimIndent()
        } else {
            // Direct target reference: simple MERGE with no properties
            """
                MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
                MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
                MERGE (root)-[:${relModel.type}]->(target)
            """.trimIndent()
        }

        return MergeStatement(
            statement = query,
            bindings = bindings
        )
    }

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