package org.drivine.backend.sqlite

import org.drivine.backend.sql.Fragments
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/**
 * Writes the predicates of a statement over a [SqliteGraph] from the query DSL: the conditions on the
 * root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. Each is the module's SQL for the Cypher the DSL writes ([Fragments]): a condition on a
 * related node is the module's `EXISTS`, and a value is compared and ordered as Cypher compares and
 * orders it, whatever its kind.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 * @param rootLabels the labels of the root
 * @param depthOverrides the depths the query reads relationship fields to, by field, which a condition on one walks too
 */
internal class SqlitePredicates(
    private val rootAlias: String,
    private val root: String,
    private val rootModel: FragmentModel,
    private val viewModel: GraphViewModel?,
    private val rootLabels: () -> List<String> = { emptyList() },
    private val depthOverrides: Map<String, Int> = emptyMap(),
    private val fragments: Fragments,
) {

    /** The predicate [conditions] make on the root; null when there are none. */
    fun predicate(conditions: List<WhereCondition>): String? =
        fragments.where(root, rootAlias, rootLabels(), conditions, viewModel, depthOverrides)

    /** The `ORDER BY` terms for [order], as Cypher orders it, compiled by the module ([Fragments.orderTerms]). */
    fun orderTerms(order: OrderTerm): String = fragments.orderTerms(root, rootAlias, order).joinToString()

    /** The predicate that selects the rows after a cursor, as Cypher compares the root's properties with the cursor's values. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        fragments.after(root, rootModel.labels, orders, values, collectionSortCount)
}
