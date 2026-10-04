package org.drivine.model

/**
 * A resolved `@PropertyBag` field on a fragment: the open map [fieldName] and the [storedPrefix]
 * that each entry's key is prefixed with on the node (`"$storedPrefix$key"`).
 *
 * `storedPrefix` already includes the delimiter — e.g. field `metadata` with the default annotation
 * gives `"metadata."`, so an entry `source` is stored as the node property `metadata.source`.
 *
 * A [flat] bag has an empty prefix and owns whatever nothing else does: every node property that is
 * neither a declared field's ([reservedKeys]) nor under another bag's prefix ([reservedPrefixes]).
 */
data class PropertyBagModel(
    val fieldName: String,
    val storedPrefix: String,
    val flat: Boolean = false,
    val reservedKeys: Set<String> = emptySet(),
    val reservedPrefixes: List<String> = emptyList(),
) {
    /** The stored node-property key for a bag entry [key], e.g. `metadata.source`. */
    fun storedKey(key: String): String = "$storedPrefix$key"

    /** Whether a node property [nodeKey] belongs to this bag; if so its entry key is recoverable via [entryKey]. */
    fun owns(nodeKey: String): Boolean =
        if (flat) nodeKey !in reservedKeys && reservedPrefixes.none { nodeKey.startsWith(it) }
        else nodeKey.startsWith(storedPrefix) && nodeKey.length > storedPrefix.length

    /** The bag entry key for a node property this bag [owns]: strips [storedPrefix]. */
    fun entryKey(nodeKey: String): String = nodeKey.substring(storedPrefix.length)
}