package org.drivine

/**
 * A save found that the node is not as it was when the object was loaded: something else changed or
 * deleted it in between, or, for a save that replaces a relationship list, added or removed one of its
 * relationships. The save is one statement, and it wrote nothing: not the node, not a related node, not
 * a relationship.
 *
 * To carry on, load the object again and re-apply the change, which
 * `StatelessGraphObjectManager.update` does. A save of an object whose stamp is null is not checked.
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
     * True when the node was changed by an earlier save of the same `saveAll`, and by no other
     * writer: one object of the batch holds the root of another, and its save changed that node or
     * its relationships before the other's save compared the stamp.
     */
    val bySameBatch: Boolean = false,
) : RuntimeException(
    if (bySameBatch) {
        """
        ${type.simpleName} '$id' was changed by an earlier save of the same saveAll, so the batch was not saved.
        Another object of the batch holds this node, and its save changed the node or a relationship of it before this one's stamp was compared.
        Expected stamp $expectedStamp, found ${foundStamp ?: "none"}.
        Save the objects one at a time, each from what the save before it returned, or give this object first in the batch.
        """.trimIndent()
    } else {
        """
        ${type.simpleName} '$id' ${happened(expectedStamp, foundStamp, deleted)} after it was loaded, so it was not saved.
        Expected stamp $expectedStamp, found ${if (deleted) "no node" else foundStamp ?: "none"}.
        Load it again and re-apply the change: StatelessGraphObjectManager.update does this.
        """.trimIndent()
    }
)

/** What became of the node: the first token of a stamp speaks for its own data, the second for its relationships. */
private fun happened(expected: String, found: String?, deleted: Boolean): String = when {
    deleted -> "was deleted by another writer"
    // A stamp with no relationship token: the object was saved over a node it had not loaded.
    found != null && found.substringBefore(':') == expected.substringBefore(':') && expected.substringAfter(':', "").isEmpty() ->
        "has relationships the object does not vouch for (it was saved over a node it had not loaded, so its stamp carries no relationship token), and its relationships were to be replaced"
    found != null && found.substringBefore(':') == expected.substringBefore(':') -> "had a relationship added or removed by another writer"
    else -> "was changed by another writer"
}
