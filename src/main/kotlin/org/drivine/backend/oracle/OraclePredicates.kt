package org.drivine.backend.oracle

import org.drivine.backend.sql.Fragments
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/**
 * Writes the predicates of a statement over an [OracleGraph] from the query DSL: the conditions on the
 * root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. Each is the module's SQL for the Cypher the DSL writes ([Fragments]): a condition on a
 * related node is the module's `EXISTS`, which joins back to the node table only by the keys Oracle
 * keeps (`+ 0`), and an equality on the root is read through the labelled index of its label.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 * @param rootLabels the labels of the root
 * @param depthOverrides the depths the query reads relationship fields to, by field, which a condition on one walks too
 */
internal class OraclePredicates(
    private val scope: OracleStatementScope,
    private val rootAlias: String,
    private val root: String,
    private val rootModel: FragmentModel,
    private val viewModel: GraphViewModel?,
    private val rootLabels: () -> List<String> = { emptyList() },
    private val depthOverrides: Map<String, Int> = emptyMap(),
    private val fragments: Fragments,
) {

    /**
     * The predicate [conditions] make on the root; null when there are none.
     *
     * With [indexed], a condition every root the statement reads by its labels meets, a label whose labelled index the module
     * reads an equality through, which only a node of the label meets, is not tested again ([OracleStatementScope.implied]):
     * the index gives the nodes of the label that meet it and nothing else, whether the value is rare or held by most of them.
     * Tested on their own in the form the index over labels answers, the labels would be read through that index instead:
     * Oracle estimates it gives one row, and it gives every node of the label.
     */
    fun predicate(conditions: List<WhereCondition>, indexed: Boolean = false): String? {
        val labels = rootLabels()
        val predicate = fragments.where(root, rootAlias, labels, conditions, viewModel, depthOverrides) ?: return null
        if (indexed && !scope.narrowed) scope.implied += labels.filter { "CASE WHEN ${OracleSql.labelled(root, it, "k").substringAfter("CASE WHEN ").substringBefore(" THEN ")}" in predicate }
        return predicate
    }

    /** The `ORDER BY` terms for [order], as Cypher orders it, compiled by the module ([Fragments.orderTerms]). */
    fun orderTerms(order: OrderTerm): String = fragments.orderTerms(root, rootAlias, order).joinToString()

    /** The predicate that selects the rows after a cursor, as Cypher compares the root's properties with the cursor's values. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        fragments.after(root, rootModel.labels, orders, values, collectionSortCount)
}
