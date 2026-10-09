package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification

/**
 * Makes one relationship field of a view the whole list: removes each relationship the field would
 * have loaded and the object does not hold. That is a relationship of the field's type and
 * direction, from the root to a node with the labels of the field's target. A relationship of the
 * same type to a node of another kind belongs to another field and is left alone.
 */
internal class RelationshipReplacer(
    private val persistenceManager: PersistenceManager,
    private val objectMapper: ObjectMapper,
) {

    fun replace(view: Any, viewModel: GraphViewModel, relationship: RelationshipModel, removedTargets: RemovedTargets) {
        val rootModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val root = requireNotNull(read(view, viewModel.rootFragment.fieldName)) { "Root fragment ${viewModel.rootFragment.fieldName} is null" }
        val target = targetOf(relationship)
        val targetModel = target.fragmentModel
        val targetId = requireNotNull(targetModel.nodeIdProperty) { "Relationship target ${targetModel.className} has no @NodeId" }
        val rootId = requireNotNull(rootModel.nodeIdProperty) { "${rootModel.className} has no @NodeId" }

        val edge = "[r:${relationship.type}]"
        val arrow = when (relationship.direction) {
            Direction.OUTGOING -> "-$edge->"
            Direction.INCOMING -> "<-$edge-"
            Direction.UNDIRECTED -> "-$edge-"
        }
        val targetLabels = targetModel.labels.joinToString(":")
        val removal = """
            MATCH (root:${rootModel.labels.joinToString(":")} {$rootId: ${'$'}rootId})$arrow(target:$targetLabels)
            WHERE NOT target.$targetId IN ${'$'}keepIds
            DELETE r
        """.trimIndent()
        val bindings = mapOf(
            "rootId" to objectMapper.toMap(root)[rootModel.nodeIdField],
            "keepIds" to items(view, relationship).mapNotNull { target.idOf(it) },
        )

        if (removedTargets == RemovedTargets.KEEP) {
            persistenceManager.execute(QuerySpecification.withStatement(removal).bind(bindings))
            return
        }
        // Two statements: one engine cannot test a pattern on a node in the statement that deleted
        // its relationship.
        val removed = persistenceManager.query(
            QuerySpecification
                .withStatement("$removal\nRETURN DISTINCT target.$targetId")
                .bind(bindings)
                .transform(Any::class.java)
        )
        if (removed.isEmpty()) return
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (target:$targetLabels)
                    WHERE target.$targetId IN ${'$'}removed AND size([ ()-->(target) | 1 ]) = 0
                    DETACH DELETE target
                    """.trimIndent()
                )
                .bind(mapOf("removed" to removed))
        )
    }

    private fun items(view: Any, relationship: RelationshipModel): List<Any> {
        val value = read(view, relationship.fieldName)
        return if (relationship.isCollection) (value as? Collection<*>).orEmpty().filterNotNull() else listOfNotNull(value)
    }

    private fun read(obj: Any, field: String): Any? =
        obj.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(obj)

    /** The node a relationship field's items point at: its fragment model, and how to read its id from an item. */
    private inner class Target(
        val fragmentModel: FragmentModel,
        private val relationship: RelationshipModel,
        private val rootFieldOfView: String?,
    ) {
        fun idOf(item: Any): Any? {
            val node = relationship.targetFieldName?.takeIf { relationship.isRelationshipFragment }?.let { read(item, it) } ?: item
            val fragment = rootFieldOfView?.let { read(node, it) } ?: node
            return objectMapper.toMap(fragment)[fragmentModel.nodeIdField]
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
            Target(FragmentModel.from(nested.rootFragment.fragmentType), relationship, nested.rootFragment.fieldName)
        } else {
            Target(FragmentModel.from(targetClass), relationship, null)
        }
    }
}
