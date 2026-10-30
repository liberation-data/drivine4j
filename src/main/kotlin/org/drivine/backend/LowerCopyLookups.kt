package org.drivine.backend

import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.LowerCopies
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.schema.LowerCopyCatalog

/**
 * [backend], with a case-insensitive comparison on a `@CaseInsensitive` property of the root made a
 * plain comparison with the property's lowercased copy (see [LowerCopies]), which an index over the
 * copy serves: `eqIgnoreCase` becomes [ComparisonOperator.EQUALS_LOWERED] and `containsIgnoreCase`
 * [ComparisonOperator.CONTAINS_LOWERED], each on the copy.
 *
 * Only once [copies] says every node of a label of the root has the copy: until then a node saved
 * before there were copies would be missed, and the comparison lowercases the property itself, as
 * for any other. Nor for an empty value, which Oracle keeps no copy of. Only the root's own
 * conditions, of a load, a count, a delete, a grouped read and a patch: the roots of a scored search,
 * and the nodes related to a root, are read as the maps a load projects, which hold no copy.
 *
 * @param backend the store's own backend, which writes the statements
 * @param copies which copies are filled in; null where the store keeps no record of them
 */
internal class LowerCopyLookups(internal val backend: GraphBackend, copies: () -> LowerCopyCatalog?) : GraphBackend by backend {

    private val copies by lazy(copies)

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan =
        backend.loadAll(graphClass, query.also { rewrite(graphClass, it.conditions) })

    override fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement =
        backend.count(graphClass, query.also { rewrite(graphClass, it.conditions) })

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement =
        backend.deleteAll(graphClass, query.also { rewrite(graphClass, it.conditions) })

    override fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement =
        backend.groups(graphClass, query.also { rewrite(graphClass, it.conditions) })

    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement =
        backend.patch(graphClass, id, values, conditions.toMutableList().also { rewrite(graphClass, it) })

    /** Replaces each of [conditions] that a copy can answer, in place. */
    private fun rewrite(graphClass: Class<*>, conditions: MutableList<WhereCondition>) {
        if (conditions.isEmpty()) return
        val copies = copies ?: return
        val view = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null
        val model = FragmentModel.from(view?.rootFragment?.fragmentType ?: graphClass)
        val filled = model.lowerCopied.filter { property -> model.labels.any { copies.filledIn(it, property) } }.toSet()
        if (filled.isEmpty()) return
        // A view's root is named by its field; every condition of a fragment's read is on the fragment.
        val root = view?.rootFragment?.fieldName
        fun rewritten(condition: WhereCondition): WhereCondition = when (condition) {
            is WhereCondition.PropertyCondition -> {
                val alias = condition.propertyPath.substringBefore('.')
                val property = condition.propertyPath.substringAfter('.')
                val lowered = LOWERED[condition.operator]
                val value = condition.value
                if (lowered != null && (root == null || alias == root) && property in filled && value is String && value.isNotEmpty()) {
                    WhereCondition.PropertyCondition("$alias.${LowerCopies.of(property)}", lowered, value)
                } else {
                    condition
                }
            }
            is WhereCondition.OrCondition -> WhereCondition.OrCondition(condition.conditions.map(::rewritten))
            is WhereCondition.NotCondition -> WhereCondition.NotCondition(condition.conditions.map(::rewritten))
            else -> condition
        }
        conditions.replaceAll(::rewritten)
    }

    private companion object {
        /** Each case-insensitive comparison, and what it is on a lowercased copy. */
        val LOWERED = mapOf(
            ComparisonOperator.EQUALS_IGNORE_CASE to ComparisonOperator.EQUALS_LOWERED,
            ComparisonOperator.CONTAINS_IGNORE_CASE to ComparisonOperator.CONTAINS_LOWERED,
        )
    }
}
