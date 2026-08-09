package org.drivine.sample.fragment

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * Exercises `@GraphProperty` through the KSP codegen: the generated `ChunkNodeProperties` must expose
 * a `containerSectionId` accessor whose `PropertyReference` carries the on-disk name
 * `container_section_id`.
 */
@NodeFragment(labels = ["ContentElement", "Chunk"])
data class ChunkNode(
    @NodeId val id: String,
    val text: String,
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
)

@GraphView
data class ChunkView(
    @Root val chunk: ChunkNode,
)
