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
     * The labels of the root an equality every root meets is read through the labelled index of, which only a node of the
     * label meets: see [OraclePredicates.predicate]. They need no test of their own.
     */
    val implied = mutableSetOf<String>()

    /**
     * Whether the root [alias] carries [labels]; null when nothing is left to test. A label a labelled index answers is not
     * tested again, and once one is, no other is tested in the form the index over labels answers either: Oracle would be
     * given that index to choose, and takes it, believing it gives one row where it gives every node of the label.
     */
    fun rootHasLabels(alias: String, labels: Collection<String>): String? =
        OracleSql.hasLabels(alias, labels - implied, scanned = !narrowed && implied.isEmpty())

    private companion object {
        /** As much of a field's name as a row is called by: Oracle allows 128, and a count and an underscore follow. */
        const val MAX_NAME = 100
    }
}
