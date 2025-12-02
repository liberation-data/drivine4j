package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
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
     * @return List of MergeStatements to execute in order
     */
    override fun <T : Any> buildMergeStatements(obj: T): List<MergeStatement> {
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

        return buildMergeStatementsInternal(obj, snapshot)
    }

    /**
     * Internal implementation that accepts an explicit snapshot parameter.
     */
    private fun <T : Any> buildMergeStatementsInternal(obj: T, snapshot: Any?): List<MergeStatement> {
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
            statements.addAll(buildRelationshipStatements(obj, snapshot, relModel, rootFragment, rootFragmentModel))
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
        rootFragmentModel: FragmentModel
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
            statements.add(buildDeleteRelationshipStatement(rootFragment, rootFragmentModel, removedItem, relModel))
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
     * Handles both GraphFragments and nested GraphViews.
     */
    private fun detectChanges(
        current: List<Any?>,
        snapshot: List<Any?>,
        relModel: RelationshipModel
    ): Pair<List<Any>, List<Any>> {
        // Determine if we're dealing with a fragment or view
        val targetClass = relModel.elementType
        val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        val fragmentModel = if (isView) {
            // For views, use the root fragment's model for ID extraction
            val viewModel = GraphViewModel.from(targetClass)
            FragmentModel.from(viewModel.rootFragment.fragmentType)
        } else {
            FragmentModel.from(targetClass)
        }

        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot detect changes for relationship without @GraphNodeId: ${relModel.fieldName}")

        // Extract IDs - for views, extract from root fragment
        val currentIds = current.mapNotNull { item ->
            if (item != null) {
                val fragment = if (isView) {
                    val viewModel = GraphViewModel.from(targetClass)
                    extractRootFragmentFromObject(item, viewModel)
                } else {
                    item
                }
                val id = sessionManager.extractIdValue(fragment, fragmentModel)
                Pair(id, item)
            } else null
        }.toMap()

        val snapshotIds = snapshot.mapNotNull { item ->
            if (item != null) {
                val fragment = if (isView) {
                    val viewModel = GraphViewModel.from(targetClass)
                    extractRootFragmentFromObject(item, viewModel)
                } else {
                    item
                }
                sessionManager.extractIdValue(fragment, fragmentModel)
            } else null
        }.toSet()

        val added = currentIds.filter { (id, _) -> id !in snapshotIds }.values.toList()
        val removed = snapshot.mapNotNull { item ->
            if (item != null) {
                val fragment = if (isView) {
                    val viewModel = GraphViewModel.from(targetClass)
                    extractRootFragmentFromObject(item, viewModel)
                } else {
                    item
                }
                val id = sessionManager.extractIdValue(fragment, fragmentModel)
                if (id !in currentIds.keys) item else null
            } else null
        }

        return Pair(added, removed)
    }

    /**
     * Builds a DELETE statement for removing a relationship.
     * Only deletes the relationship, not the fragment.
     * Handles both GraphFragments and nested GraphViews.
     * Uses objectMapper.toMap() to ensure proper type conversion (e.g., UUID -> String).
     */
    private fun buildDeleteRelationshipStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetItem: Any,
        relModel: RelationshipModel
    ): MergeStatement {
        // Check if target is a GraphView or GraphFragment
        val targetClass = relModel.elementType
        val isView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        val (targetFragment, targetFragmentModel) = if (isView) {
            // For nested view, extract root fragment
            val viewModel = GraphViewModel.from(targetClass)
            val fragment = extractRootFragmentFromObject(targetItem, viewModel)
            Pair(fragment, FragmentModel.from(viewModel.rootFragment.fragmentType))
        } else {
            Pair(targetItem, FragmentModel.from(targetClass))
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

        val query = """
            MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
            MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
            MATCH (root)-[r:${relModel.type}]->(target)
            DELETE r
        """.trimIndent()

        return MergeStatement(
            statement = query,
            bindings = mapOf(
                "rootId" to rootId,
                "targetId" to targetId
            )
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

        // Check if target is a GraphView or GraphFragment
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
            val targetFragmentModel = FragmentModel.from(targetClass)

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

        return statements
    }

    /**
     * Builds a MERGE statement for a relationship between two fragments.
     * Uses objectMapper.toMap() to ensure proper type conversion (e.g., UUID -> String).
     */
    private fun buildRelationshipMergeStatement(
        rootFragment: Any,
        rootFragmentModel: FragmentModel,
        targetFragment: Any,
        targetFragmentModel: FragmentModel,
        relModel: RelationshipModel
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

        val query = """
            MATCH (root:$rootLabels {$rootIdField: ${'$'}rootId})
            MATCH (target:$targetLabels {$targetIdField: ${'$'}targetId})
            MERGE (root)-[:${relModel.type}]->(target)
        """.trimIndent()

        return MergeStatement(
            statement = query,
            bindings = mapOf(
                "rootId" to rootId,
                "targetId" to targetId
            )
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