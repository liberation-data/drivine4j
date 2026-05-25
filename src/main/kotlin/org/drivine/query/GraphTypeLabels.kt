package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.GraphViewModel

/**
 * Resolves the Neo4j labels for a graph type. Shared by the load and delete query builders so
 * label resolution lives in exactly one place.
 *
 * Note: this inspects only a type's *own* @NodeFragment annotation (no superclass/interface
 * inheritance). That is intentional — it is the label set the query builders have always emitted
 * into MATCH/projection patterns. For the persisted, inheritance-aware label set see
 * [org.drivine.model.FragmentModel.labelsFor].
 */
internal object GraphTypeLabels {

    /**
     * Labels declared directly on a @NodeFragment class, or empty if none/not annotated.
     */
    fun fragmentLabels(fragmentType: Class<*>): List<String> {
        val annotation = fragmentType.getAnnotation(NodeFragment::class.java)
        return annotation?.labels?.toList() ?: emptyList()
    }

    /**
     * Labels for a type that may be a @NodeFragment or a @GraphView. For a view, returns its
     * root fragment's labels. Empty if the type is neither.
     */
    fun labelsForType(type: Class<*>): List<String> {
        type.getAnnotation(NodeFragment::class.java)?.let { return it.labels.toList() }
        type.getAnnotation(GraphView::class.java)?.let {
            val viewModel = GraphViewModel.from(type)
            return fragmentLabels(viewModel.rootFragment.fragmentType)
        }
        return emptyList()
    }
}