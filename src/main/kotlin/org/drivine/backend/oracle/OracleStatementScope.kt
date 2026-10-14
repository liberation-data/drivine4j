package org.drivine.backend.oracle

/**
 * What the parts of one statement share as it is written: the values its parameters take, the names
 * given out so far, and what has been said about how its roots are found. One instance writes one
 * statement.
 */
internal class OracleStatementScope {
    private val bindings = mutableMapOf<String, Any?>()
    private val names = mutableSetOf<String>()

    /**
     * Whether the roots are found by something narrower than their labels: an id, or the node they are
     * related to. The labels are then tested on what is found, and not used to find it.
     */
    var narrowed = false

    /**
     * The labels of the roots that a condition every root meets is answered for by a labelled index:
     * the condition reads the property through the index's expression, which is null for a node
     * without the label, so the label needs no test of its own. See [OraclePredicates.predicate].
     */
    val implied = mutableSetOf<String>()

    /**
     * Whether the root [alias] carries [labels]; null when nothing is left to test. A label a labelled
     * index answers is not tested again, and once one is, no other is tested in the form the index over
     * labels answers either: Oracle would be given that index to choose, and takes it, believing it
     * gives one row where it gives every node of the label.
     */
    fun rootHasLabels(alias: String, labels: Collection<String>): String? =
        OracleSql.hasLabels(alias, labels - implied, scanned = !narrowed && implied.isEmpty())

    /** The kind a property no field declares is read as, learnt from the cursor value a seek compares it with. */
    val inferredKinds = mutableMapOf<String, SqlKind>()

    /**
     * The name of a row of the statement that stands for [name], a field of the model or what the row
     * is there for: `people_`, and `people2_` for the next row of that name, so that a statement reads
     * in the words of the model it loads. No other row of the statement has the name, and it ends as
     * no word SQL keeps does, so a field may be called `user` or `order`.
     */
    fun row(name: String): String {
        val plain = name.replace(UNNAMEABLE, "_").take(MAX_NAME).let { if (it.firstOrNull()?.isLetter() == true) it else "r$it" }
        var count = 1
        var row = "${plain}_"
        while (!names.add(row.lowercase())) row = "$plain${++count}_"
        return row
    }

    /** Binds [value] and gives the parameter that stands for it. */
    fun bound(value: Any?): String {
        val name = "_p${bindings.size}"
        bindings[name] = value
        return "\$$name"
    }

    /** The values the statement's parameters take, as written so far. */
    fun bindings(): Map<String, Any?> = bindings

    private companion object {
        /** What an Oracle name written bare cannot hold. */
        val UNNAMEABLE = Regex("[^A-Za-z0-9_]")

        /** As much of a field's name as a row is called by: Oracle allows 128, and a count and an underscore follow. */
        const val MAX_NAME = 100
    }
}
