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
     * Whether the roots are found by something narrower than their labels: an id, the node they are
     * related to, or a property whose index finds few nodes. The labels are then tested on what is
     * found, and not used to find it.
     */
    var narrowed = false

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
