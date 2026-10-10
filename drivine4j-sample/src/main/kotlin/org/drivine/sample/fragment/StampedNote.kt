package org.drivine.sample.fragment

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp

/**
 * Exercises `@NodeStamp` through the KSP codegen: the generated `StampedNoteProperties` must expose a
 * `stamp` accessor whose `PropertyReference` carries the property the stamp is stored under, not the
 * field's name.
 */
@NodeFragment(labels = ["StampedNote"])
data class StampedNote(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
)
