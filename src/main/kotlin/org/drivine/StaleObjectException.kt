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
) : RuntimeException(
    """
    ${type.simpleName} '$id' ${happened(expectedStamp, foundStamp, deleted)} by another writer after it was loaded, so it was not saved.
    Expected stamp $expectedStamp, found ${if (deleted) "no node" else foundStamp ?: "none"}.
    Load it again and re-apply the change: StatelessGraphObjectManager.update does this.
    """.trimIndent()
)

/** What became of the node: the first token of a stamp speaks for its own data, the second for its relationships. */
private fun happened(expected: String, found: String?, deleted: Boolean): String = when {
    deleted -> "was deleted"
    found != null && found.substringBefore(':') == expected.substringBefore(':') -> "had a relationship added or removed"
    else -> "was changed"
}
