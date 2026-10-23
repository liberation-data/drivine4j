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
 * @param loaded the root's fields as the object was loaded, by field name, as a save writes them,
 *   but a `@JsonPacked` field the node held text for as that text, as the load read it. Where the engine [reads without a snapshot][org.drivine.query.grammar.CypherGrammar.readsWithoutSnapshot],
 *   a checked save compares them with the node under its lock, as well as the stamp: the stamp a
 *   load was handed may be newer than the data it read
 */
internal class SaveRequest(
    val obj: Any,
    val checked: Boolean,
    val nullPolicy: NullPolicy,
    val rootWriteFields: Set<String>? = null,
    val replaced: (RelationshipModel) -> RemovedTargets? = { null },
    val before: JsonNode? = null,
    val after: JsonNode? = null,
    val rootMode: RootMode = RootMode.EITHER,
    val loaded: Map<String, Any?>? = null,
) {
    init {
        require((before == null) == (after == null)) { "A save of what a change altered needs the object's digest as loaded and its digest now." }
        // A root made by the save has no stamp to compare and nothing loaded to differ from.
        require(rootMode != RootMode.ABSENT || (!checked && before == null)) { "A save that only makes its root is of a whole object, unchecked." }
        require(loaded == null || checked) { "Only a checked save compares the fields as loaded." }
    }

    companion object {
        /**
         * The save of what a change altered in an object that was loaded: [dirty] of its root's fields,
         * and what differs between [before] and [after] of the rest. It is checked, against [loaded]
         * too, it clears what the change set to null, and it makes no node: one that was loaded and is
         * gone stays gone.
         */
        fun changed(obj: Any, before: JsonNode, after: JsonNode, dirty: Set<String>?, loaded: Map<String, Any?>): SaveRequest =
            SaveRequest(obj, checked = true, NullPolicy.CLEAR, dirty, before = before, after = after, rootMode = RootMode.FOUND, loaded = loaded)

        /**
         * The save that makes [obj] only if its root is not there: of the whole object, unchecked, a
         * null field left alone. A root that is there is not written, nor anything else.
         */
        fun absent(obj: Any): SaveRequest = SaveRequest(obj, checked = false, NullPolicy.IGNORE, rootMode = RootMode.ABSENT)
    }
}
