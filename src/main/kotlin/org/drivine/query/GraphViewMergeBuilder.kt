package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
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

        // Check if root fragment is dirty
        val rootIdValue = sessionManager.extractIdValue(rootFragment, rootFragmentModel)?.toString()
        val rootDirtyFields = if (rootIdValue != null) {
            sessionManager.getDirtyFields(rootFragment, rootIdValue)
        } else null

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
        val (added, removed) = if (snapshotItems != null) {
            detectChanges(currentItems, snapshotItems, relModel)
        } else {
            // No snapshot = all current items are "added"
            Pair(currentItems, emptyList())
        }

        // Generate statements for removed relationships
        removed.forEach { removedItem ->
            statements.add(buildDeleteRelationshipStatement(rootFragment, rootFragmentModel, removedItem, relModel, cascade))
        }

        // Generate statements for added relationships
        added.forEach { addedItem ->
            if (addedItem != null) {
                statements.addAll(buildAddRelationshipStatements(rootFragment, rootFragmentModel, addedItem, relModel))
            }
        }

        return statements
    }

    /**
     * Detects which items were added and which were removed.
     * Uses ID-based comparison.
     * Handles both GraphFragments, nested GraphViews, and relationship fragments.
     */
    private fun detectChanges(
        current: List<Any?>,
        snapshot: List<Any?>,
        relModel: RelationshipModel
    ): Pair<List<Any>, List<Any>> {
        // For relationship fragments, we need to extract the target node for comparison
        val actualTargetClass = if (relModel.isRelationshipFragment) {
            relModel.targetNodeType!!
        } else {
            relModel.elementType
        }

        val isView = actualTargetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        val fragmentModel = if (isView) {
            // For views, use the root fragment's model for ID extraction
            val viewModel = GraphViewModel.from(actualTargetClass)
            FragmentModel.from(viewModel.rootFragment.fragmentType)
        } else {
            FragmentModel.from(actualTargetClass)
        }

        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot detect changes for relationship without @GraphNodeId: ${relModel.fieldName}")

        // Helper to extract the actual target node from an item
        fun extractTargetNode(item: Any): Any {
            return if (relModel.isRelationshipFragment) {
                // Extract target from relationship fragment
                val targetField = item.javaClass.getDeclaredField(relModel.targetFieldName!!)
                targetField.isAccessible = true
                targetField.get(item) ?: throw IllegalArgumentException("Target field is null")
            } else {
                item
            }
        }

        // Extract IDs - for views, extract from root fragment
        val currentIds = current.mapNotNull { item ->
            if (item != null) {
                val targetNode = extractTargetNode(item)
                val fragment = if (isView) {
                    val viewModel = GraphViewModel.from(actualTargetClass)
                    extractRootFragmentFromObject(targetNode, viewModel)
                } else {
                    targetNode
                }
                val id = sessionManager.extractIdValue(fragment, fragmentModel)
                Pair(id, item)
            } else null
        }.toMap()

        val snapshotIds = snapshot.mapNotNull { item ->
            if (item != null) {
                val targetNode = extractTargetNode(item)
                val fragment = if (isView) {
                    val viewModel = GraphViewModel.from(actualTargetClass)
                    extractRootFragmentFromObject(targetNode, viewModel)
                } else {
                    targetNode
                }
                sessionManager.extractIdValue(fragment, fragmentModel)
            } else null
        }.toSet()

        // For relationship fragments, we need to treat property changes as "updates"
        // which means we regenerate the MERGE statement (added) but don't remove the old one
        val added = mutableListOf<Any>()
        val removed = mutableListOf<Any>()

        if (relModel.isRelationshipFragment) {
            // For relationship fragments: compare full relationship including properties
            val currentMap = current.mapNotNull { item ->
                if (item != null) {
                    val targetNode = extractTargetNode(item)
                    val fragment = if (isView) {
                        val viewModel = GraphViewModel.from(actualTargetClass)
                        extractRootFragmentFromObject(targetNode, viewModel)
                    } else {
                        targetNode
                    }
                    val id = sessionManager.extractIdValue(fragment, fragmentModel)
                    Pair(id, item)
                } else null
            }.toMap()

            val snapshotMap = snapshot.mapNotNull { item ->
                if (item != null) {
                    val targetNode = extractTargetNode(item)
                    val fragment = if (isView) {
                        val viewModel = GraphViewModel.from(actualTargetClass)
                        extractRootFragmentFromObject(targetNode, viewModel)
                    } else {
                        targetNode
                    }
                    val id = sessionManager.extractIdValue(fragment, fragmentModel)
                    Pair(id, item)
                } else null
            }.toMap()

            // For each current item:
            // - If ID is new: add it
            // - If ID exists but relationship properties changed: add it (will update via MERGE + SET)
            currentMap.forEach { (id, currentItem) ->
                val snapshotItem = snapshotMap[id]
                if (snapshotItem == null) {
                    // New target ID
                    added.add(currentItem)
                } else {
                    // Same target ID - check if relationship properties changed
                    val currentProps = objectMapper.toMap(currentItem)
                        .filterKeys { it in relModel.relationshipProperties }
                    val snapshotProps = objectMapper.toMap(snapshotItem)
                        .filterKeys { it in relModel.relationshipProperties }

                    if (currentProps != snapshotProps) {
                        // Properties changed - re-run MERGE to update
                        added.add(currentItem)
                    }
                    // If properties are the same, do nothing (no statement needed)
                }
            }

            // Items that exist in snapshot but not in current are removed
            snapshotMap.forEach { (id, item) ->
                if (id !in currentMap.keys) {
                    removed.add(item)
                }
            }
        } else {
            // For direct references: simple ID-based comparison
            added.addAll(currentIds.filter { (id, _) -> id !in snapshotIds }.values)
            removed.addAll(snapshot.mapNotNull { item ->
                if (item != null) {
                    val targetNode = extractTargetNode(item)
                    val fragment = if (isView) {
                        val viewModel = GraphViewModel.from(actualTargetClass)
                        extractRootFragmentFromObject(targetNode, viewModel)
                    } else {
                        item
                    }
                    val id = sessionManager.extractIdValue(fragment, fragmentModel)
                    if (id !in currentIds.keys) item else null
                } else null
            })
        }

        return Pair(added, removed)
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
                        WHERE NOT EXISTS((target)<-[]-()) AND NOT EXISTS((target)-[]-())
                        DELETE target
                    """.trimIndent()
                }
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
            WHERE NOT EXISTS((target)<-[]-()) AND NOT EXISTS((target)-[]-())
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