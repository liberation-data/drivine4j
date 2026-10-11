package org.drivine.backend.oracle

/**
 * What the parts of one statement share as it is written: the values its parameters take, the names
 * given out so far, and what has been said about how its roots are found. One instance writes one
 * statement.
 */
internal class OracleStatementScope {
    private val bindings = mutableMapOf<String, Any?>()
    private var names = 0

    /**
     * Whether the roots are found by something narrower than their labels: an id, or the node they are
     * related to. The labels are then tested on what is found, and not used to find it.
     */
    var narrowed = false

    /** The kind a property no field declares is read as, learnt from the cursor value a seek compares it with. */
    val inferredKinds = mutableMapOf<String, SqlKind>()

    /** A name no other part of the statement has. */
    fun next(prefix: String): String = "${prefix}${names++}_"

    /** Binds [value] and gives the parameter that stands for it. */
    fun bound(value: Any?): String {
        val name = "_p${bindings.size}"
        bindings[name] = value
        return "\$$name"
    }

    /** The values the statement's parameters take, as written so far. */
    fun bindings(): Map<String, Any?> = bindings
}
