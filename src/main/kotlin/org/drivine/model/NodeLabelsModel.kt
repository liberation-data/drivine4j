package org.drivine.model

/**
 * A resolved `@NodeLabels` field on a fragment: the set-valued [fieldName] whose contents are the
 * node's labels beyond the fragment's own.
 *
 * [closedSet] is the enum's member names when the field is a set of an enum — the only labels the
 * field reads and the only ones a save may remove. It is null for a set of `String`, which reads
 * every label.
 *
 * An open set has no enum to say which of the node's labels are its own, so it records them on the
 * node, in [ownedProperty]: the labels this field has put there and not since removed. That list is
 * what a save under `CLEAR` may remove from.
 */
data class NodeLabelsModel(
    val fieldName: String,
    val closedSet: Set<String>? = null,
) {
    /** The node property an open set records its labels in; null for a closed set, which needs none. */
    val ownedProperty: String? = if (closedSet == null) "$OWNED_PREFIX$fieldName" else null

    /** The value the field takes for a node carrying [nodeLabels]. */
    fun read(nodeLabels: Collection<String>): List<String> =
        if (closedSet == null) nodeLabels.toList() else nodeLabels.filter { it in closedSet }

    /** The members of the closed set that [current] no longer holds; empty for an open set. */
    fun dropped(current: Collection<String>): List<String> =
        closedSet?.filter { it !in current }.orEmpty()

    companion object {
        /**
         * Prefix of every node property Drivine writes for its own bookkeeping. No field or bag may
         * use it, and a flat bag never claims a property under it.
         */
        const val RESERVED_PREFIX = "__drivine."

        /** Prefix of the node properties in which open sets record the labels they own. */
        const val OWNED_PREFIX = "${RESERVED_PREFIX}labels."
    }
}
