package sample.polytree

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * A content hierarchy that is heterogeneous in data but homogeneous in label (`ContentElement`):
 * every node also carries a concrete subtype label (`Chunk` / `LeafSection` / `Document`). Used to
 * verify a **polymorphic root fragment inside a recursive `@GraphView`** loads a mixed tree with each
 * node deserialized to its concrete subtype.
 */
@NodeFragment(labels = ["ContentElement"])
sealed interface ContentElementNode {
    @get:NodeId val id: String
}

@NodeFragment(labels = ["ContentElement", "Chunk"])
data class ChunkNode(
    override val id: String,
    val text: String = "",
    // A subtype-only @GraphProperty, to prove override reconstruction works through polymorphic dispatch.
    @GraphProperty("container_section_id") val containerSectionId: String? = null,
) : ContentElementNode

@NodeFragment(labels = ["ContentElement", "LeafSection"])
data class LeafSectionNode(
    override val id: String,
    val heading: String = "",
) : ContentElementNode

@NodeFragment(labels = ["ContentElement", "Document"])
data class DocumentNode(
    override val id: String,
    val title: String = "",
) : ContentElementNode

/** Recursive view whose node is the **polymorphic** [ContentElementNode] — the repro case. */
@GraphView
data class TypedContentTreeView(
    @Root val element: ContentElementNode,
    @GraphRelationship(type = "HAS_PARENT", direction = Direction.INCOMING, maxDepth = 10)
    val children: List<TypedContentTreeView>,
)

/** A concrete generic fragment over the same nodes — the existing working shape (regression guard). */
@NodeFragment(labels = ["ContentElement"])
data class ContentElementFragment(
    @NodeId val id: String,
)

@GraphView
data class ContentTreeView(
    @Root val element: ContentElementFragment,
    @GraphRelationship(type = "HAS_PARENT", direction = Direction.INCOMING, maxDepth = 10)
    val children: List<ContentTreeView>,
)

// ----- Non-recursive polymorphic relationship target -----

@NodeFragment(labels = ["Folder"])
data class FolderNode(@NodeId val id: String, val name: String = "")

/** A non-recursive view whose relationship target is the polymorphic [ContentElementNode]. */
@GraphView
data class FolderWithHead(
    @Root val folder: FolderNode,
    @GraphRelationship(type = "HAS_HEAD", direction = Direction.OUTGOING)
    val head: ContentElementNode,
)
