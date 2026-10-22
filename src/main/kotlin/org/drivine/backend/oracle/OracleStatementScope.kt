package org.drivine.backend.oracle

import org.drivine.backend.sql.StatementScope

/**
 * The [StatementScope] of a statement over an [OracleGraph], with what has been said about how its
 * roots are found.
 */
internal class OracleStatementScope : StatementScope(MAX_NAME) {

    /**
     * Whether the roots are found by something narrower than their labels: an id, or the node they are
     * related to. The labels are then tested on what is found, and not used to find it.
     */
    var narrowed = false

    /**
     * For each property of the root that a condition every root meets compares, the label whose
     * labelled index answers it. Every comparison with the property is read through that label's
     * index, whose expression is null for a node without the label: so the label needs no test of its
     * own. Each label here is tested so by a comparison through its own index, and never through
     * another label's, which would say nothing of it. See [OraclePredicates.predicate].
     */
    val answered = linkedMapOf<String, String>()

    /** The labels of the roots that need no test of their own: see [answered]. */
    val implied: Set<String>
        get() = answered.values.toSet()

    /**
     * Whether the root [alias] carries [labels]; null when nothing is left to test. A label a labelled
     * index answers is not tested again, and once one is, no other is tested in the form the index over
     * labels answers either: Oracle would be given that index to choose, and takes it, believing it
     * gives one row where it gives every node of the label.
     */
    fun rootHasLabels(alias: String, labels: Collection<String>): String? =
        OracleSql.hasLabels(alias, labels - implied, scanned = !narrowed && implied.isEmpty())

    private companion object {
        /** As much of a field's name as a row is called by: Oracle allows 128, and a count and an underscore follow. */
        const val MAX_NAME = 100
    }
}
