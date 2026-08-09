package sample.graphproperty

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * Fixtures for `@GraphProperty` — mapping a camelCase Kotlin field to a snake_case on-disk node
 * property while the field name stays the identity used in code, the DSL, and result mapping.
 */

/**
 * A chunk whose structural keys live on-disk in snake_case, promoted to idiomatic camelCase fields.
 * A `@RangeIndex` on an overridden field must index the on-disk name.
 */
@NodeFragment(labels = ["ContentElement", "Chunk"])
data class ChunkNode(
    @NodeId val id: String,
    val text: String,
    @GraphProperty("container_section_id") @RangeIndex val containerSectionId: String? = null,
    @GraphProperty("sequence_number") val sequenceNumber: Long? = null,
    @GraphProperty("root_document_id") val rootDocumentId: String? = null,
)

/** A `@VectorIndex` on a `@GraphProperty` field — the index must be created on the on-disk name. */
@NodeFragment(labels = ["Embedded"])
data class EmbeddedNode(
    @NodeId val id: String,
    @GraphProperty("embedding_vec") @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
)

/** The `@NodeId` itself is overridden — the MERGE key and load `WHERE` must use the on-disk name. */
@NodeFragment(labels = ["Widget"])
data class WidgetNode(
    @NodeId @GraphProperty("widget_key") val key: String,
    val name: String,
)

/** A relationship-target fragment with its own override, to exercise the `@GraphView` seam. */
@NodeFragment(labels = ["Section"])
data class SectionNode(
    @NodeId val id: String,
    @GraphProperty("display_title") val displayTitle: String? = null,
)

/**
 * A view embedding two `@GraphProperty` fragments — the root ([ChunkNode]) and a relationship target
 * ([SectionNode]) — so both the root and relationship-target projections must alias field ← property.
 */
@GraphView
data class ChunkWithSection(
    @Root val chunk: ChunkNode,
    @GraphRelationship(type = "IN_SECTION", direction = Direction.OUTGOING)
    val section: SectionNode,
)

// ----- Polymorphic hierarchy exercising the `.*` load path -----

/** Interface base with a `@NodeFragment` label; concrete subtypes register via `registerSubtype`. */
@NodeFragment(labels = ["Element"])
interface Element {
    @get:NodeId
    val id: String
}

/** A subtype carrying a `@GraphProperty` field — loaded through the polymorphic `.*` projection. */
@NodeFragment(labels = ["Element", "ParagraphElement"])
data class ParagraphElement(
    @NodeId override val id: String,
    val body: String,
    @GraphProperty("leaf_section_id") val leafSectionId: String? = null,
) : Element
