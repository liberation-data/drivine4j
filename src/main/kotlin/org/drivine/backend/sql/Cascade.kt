package org.drivine.backend.sql

import org.drivine.annotation.GraphView
import org.drivine.model.GraphViewModel
import org.drivine.query.GraphTypeLabels

/**
 * A field that holds views of its own class, of [view], that a cascade follows by [step] once it has
 * come by [path].
 */
internal class CascadeRecursion(val view: String, val field: String, val path: List<Step>, val step: Step)

/**
 * The paths a cascading delete of a view follows from a root, and the fields that hold views of their
 * own class among them: what a SQL store's delete reaches, however it writes the reaching.
 */
internal class Cascade private constructor(val paths: List<List<Step>>, val recursions: List<CascadeRecursion>) {

    /** Each step of the paths, once for each type and direction: a node one such step below another is held by it. */
    val steps: List<Step> get() = paths.flatten().distinctBy { it.type to it.direction }

    companion object {
        /**
         * The paths a cascading delete of [model] follows from a root: one for each relationship field it
         * writes, and for each field of a view nested in it, while that view is within its depth. The
         * steps after a field that holds views of its own class continue from its far node, which stands
         * for every level of it: they are the nested view's other fields, as a load reads at each level.
         */
        fun of(model: GraphViewModel): Cascade {
            val paths = mutableListOf<List<Step>>()
            val recursions = mutableListOf<CascadeRecursion>()
            fun walk(view: GraphViewModel, path: List<Step>, visits: Map<String, Int>, followRecursive: Boolean) {
                // A read-only field (every path is one) is not the view's to delete: other code writes what it loads.
                view.relationships.filterNot { it.readOnly || (it.isRecursive && !followRecursive) }.forEach { relationship ->
                    val targetType = if (relationship.isRelationshipFragment) relationship.targetNodeType!! else relationship.elementType
                    val labels = GraphTypeLabels.labelsForType(targetType)
                    if (labels.isEmpty()) return@forEach
                    val depth = if (relationship.isRecursive) relationship.maxDepth.coerceAtLeast(1) else 1
                    val step = Step(relationship.type, relationship.direction, labels, depth)
                    val reached = path + step
                    paths += reached
                    val visited = visits[targetType.name] ?: 0
                    if (relationship.isRecursive) {
                        recursions += CascadeRecursion(view.className, relationship.fieldName, path, step)
                        // Its own field is the step taken to its depth already.
                        walk(GraphViewModel.from(targetType), reached, visits, followRecursive = false)
                    } else if (targetType.isAnnotationPresent(GraphView::class.java) && visited < relationship.maxDepth) {
                        walk(GraphViewModel.from(targetType), reached, visits + (targetType.name to visited + 1), followRecursive = true)
                    }
                }
            }
            walk(model, emptyList(), mapOf(model.className to 1), followRecursive = true)
            return Cascade(paths, recursions)
        }
    }
}
