package org.drivine

/**
 * A save found that the node is not as it was when the object was loaded: something else changed or
 * deleted it in between, or, for a save that replaces a relationship list, added or removed one of its
 * relationships. The save is one statement, and it wrote nothing: not the node, not a related node, not
 * a relationship.
 *
 * To carry on, load the object again and re-apply the change, which
 * `StatelessGraphObjectManager.update` does. A save of an object whose stamp is null is not checked.
 *
 * It is also what a checked save throws when the engine itself turns the save away each time it is
 * run, because another writer was changing a node the save writes. Memgraph does so inside a
 * transaction that read the node before another writer committed, since the transaction goes on
 * seeing the node as it was. It can also be a node the save reaches through a relationship that is
 * contended, and not the object's own: the save cannot tell which, so it does not say the object
 * changed. The engine's error is then the [cause], and [foundStamp] is not known. A transaction this
 * happens in has failed as a whole, and is to be run again from its start.
 */
class StaleObjectException(
    /** The fragment class of the node. */
    val type: Class<*>,
    val id: Any,
    /** The stamp the object carried. */
    val expectedStamp: String,
    /** The stamp the node carries now; null when it has none, or when the node is [deleted]. */
    val foundStamp: String?,
    /** True when the node no longer exists. */
    val deleted: Boolean,
    /**
     * The engine's error, when it was the engine that turned the save away; null when the stamps
     * differed. When it is not null, the object's node may be as it was loaded.
     */
    cause: Throwable? = null,
) : RuntimeException(
    if (cause == null) {
        """
        ${type.simpleName} '$id' ${happened(expectedStamp, foundStamp, deleted)} by another writer after it was loaded, so it was not saved.
        Expected stamp $expectedStamp, found ${if (deleted) "no node" else foundStamp ?: "none"}.
        Load it again and re-apply the change: StatelessGraphObjectManager.update does this.
        """.trimIndent()
    } else {
        """
        ${type.simpleName} '$id' was not saved: the engine turned the save away each time it was run, because another writer was changing a node it writes. ${cause.message}
        The node may have changed since it was loaded. Outside a transaction, load it again and re-apply the change: StatelessGraphObjectManager.update does this. Inside one, run the transaction again.
        """.trimIndent()
    },
    cause,
)

/** What became of the node: the first token of a stamp speaks for its own data, the second for its relationships. */
private fun happened(expected: String, found: String?, deleted: Boolean): String = when {
    deleted -> "was deleted"
    found != null && found.substringBefore(':') == expected.substringBefore(':') -> "had a relationship added or removed"
    else -> "was changed"
}
