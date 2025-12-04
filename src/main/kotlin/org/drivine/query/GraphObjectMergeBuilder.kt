package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.GraphView
import org.drivine.manager.CascadeType
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.session.SessionManager

/**
 * Base interface for building MERGE statements for graph objects (Fragments and Views).
 */
interface GraphObjectMergeBuilder {
    /**
     * Builds a list of MERGE statements to save a graph object.
     * Returns statements in execution order.
     *
     * @param obj The object to save
     * @param cascade The cascade policy for deleted relationships
     * @return List of MergeStatements to execute in order
     */
    fun <T : Any> buildMergeStatements(obj: T, cascade: CascadeType = CascadeType.NONE): List<MergeStatement>

    companion object {
        /**
         * Creates the appropriate merge builder for a graph object class.
         * Detects whether it's a GraphFragment or GraphView and returns the correct builder.
         */
        fun forClass(
            graphClass: Class<*>,
            objectMapper: ObjectMapper,
            sessionManager: SessionManager
        ): GraphObjectMergeBuilder {
            return if (graphClass.isAnnotationPresent(GraphView::class.java)) {
                val viewModel = GraphViewModel.from(graphClass)
                GraphViewMergeBuilder(viewModel, objectMapper, sessionManager)
            } else if (graphClass.isAnnotationPresent(NodeFragment::class.java)) {
                val fragmentModel = FragmentModel.from(graphClass)
                FragmentMergeBuilderAdapter(fragmentModel, objectMapper, sessionManager)
            } else {
                throw IllegalArgumentException("Class ${graphClass.name} must be annotated with @GraphView or @GraphFragment")
            }
        }

        /**
         * Creates the appropriate merge builder for a graph object class using KClass.
         */
        fun forClass(
            graphClass: kotlin.reflect.KClass<*>,
            objectMapper: ObjectMapper,
            sessionManager: SessionManager
        ): GraphObjectMergeBuilder {
            return forClass(graphClass.java, objectMapper, sessionManager)
        }
    }
}

/**
 * Adapter to make FragmentMergeBuilder conform to GraphObjectMergeBuilder interface.
 * Wraps the single-statement fragment builder into a list-based interface.
 */
class FragmentMergeBuilderAdapter(
    private val fragmentModel: FragmentModel,
    private val objectMapper: ObjectMapper,
    private val sessionManager: SessionManager
) : GraphObjectMergeBuilder {

    override fun <T : Any> buildMergeStatements(obj: T, cascade: CascadeType): List<MergeStatement> {
        val fragmentBuilder = FragmentMergeBuilder(fragmentModel, objectMapper)

        // Check if object is in session to determine dirty fields
        val idValue = sessionManager.extractIdValue(obj, fragmentModel)?.toString()
        val dirtyFields = if (idValue != null) {
            sessionManager.getDirtyFields(obj, idValue)
        } else null

        // Note: Fragments don't have relationships, so cascade is ignored
        return listOf(fragmentBuilder.buildMergeStatement(obj, dirtyFields))
    }
}
