package org.drivine.query

import com.fasterxml.jackson.databind.JsonNode
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.model.RelationshipModel

/**
 * What one save is asked to write: the same of every backend, each of which writes it as one
 * [SaveStatement].
 *
 * @param obj the fragment or view to save
 * @param checked whether the root is saved only if it still carries the stamp [obj] holds
 * @param nullPolicy whether a null field is left alone or cleared
 * @param rootWriteFields the only root fields to write; null for all of them
 * @param replaced what becomes of the targets a relationship field of the root no longer holds,
 *   for a field whose list is the whole list; null for a field that only adds
 * @param before the digest of [obj] as it was loaded, with [after] its digest now. When given, the
 *   statement writes what differs: it drops the relationships that are gone, leaves alone the
 *   related nodes that are as they were, and of one that was altered writes the fields that differ,
 *   clearing one set to null
 * @param rootMode whether a root that is not there is made, and one that is there written. A save
 *   that only makes its root ([RootMode.ABSENT]) is of a whole object, unchecked
 */
internal class SaveRequest(
    val obj: Any,
    val checked: Boolean,
    val nullPolicy: NullPolicy,
    val rootWriteFields: Set<String>? = null,
    val replaced: (RelationshipModel) -> RemovedTargets? = { null },
    val before: JsonNode? = null,
    val after: JsonNode? = null,
    val rootMode: RootMode = RootMode.MERGE,
) {
    init {
        require((before == null) == (after == null)) { "A save of what a change altered needs the object's digest as loaded and its digest now." }
        // A root made by the save has no stamp to compare and nothing loaded to differ from.
        require(rootMode != RootMode.ABSENT || (!checked && before == null)) { "A save that only makes its root is of a whole object, unchecked." }
    }

    companion object {
        /**
         * The save of what a change altered in an object that was loaded: [dirty] of its root's fields,
         * and what differs between [before] and [after] of the rest. It is checked, it clears what the
         * change set to null, and it makes no node: one that was loaded and is gone stays gone.
         */
        fun changed(obj: Any, before: JsonNode, after: JsonNode, dirty: Set<String>?): SaveRequest =
            SaveRequest(obj, checked = true, NullPolicy.CLEAR, dirty, before = before, after = after, rootMode = RootMode.FOUND)
    }
}

/**
 * Which root a save writes: one it makes, one it finds, or either. Where the root is not one the
 * save writes, the statement gives no row and writes nothing.
 */
internal enum class RootMode(val makes: Boolean, val writesFound: Boolean) {
    /** A save: the root is made when it is not there, and written when it is. */
    MERGE(makes = true, writesFound = true),

    /** An update: a root that is gone stays gone. */
    FOUND(makes = false, writesFound = true),

    /** A create: a root that is there is not written. */
    ABSENT(makes = true, writesFound = false),
}
