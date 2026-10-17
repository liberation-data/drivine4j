package sample.atomic

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.Root

/**
 * Fixtures for a `saveAll` that must be applied whole: recording a processed chunk writes its hash
 * and moves a bookmark, and neither is to be written without the other.
 */
@NodeFragment(labels = ["ChunkHash"])
data class ChunkHash(@NodeId val id: String, val chunk: String)

/** A stamped fragment. Its cursor is unique where a test declares it so. */
@NodeFragment(labels = ["Bookmark"])
data class Bookmark(
    @NodeId val id: String,
    val cursor: String,
    @NodeStamp val stamp: String? = null,
)

/** A third label, with no stamp. */
@NodeFragment(labels = ["Ledger"])
data class Ledger(@NodeId val id: String, val total: Int)

/** A view: each one a batch holds is saved by a statement of its own. */
@GraphView
data class BookmarkHashes(
    @Root val bookmark: Bookmark,
    @GraphRelationship(type = "COVERS", direction = Direction.OUTGOING)
    val hashes: List<ChunkHash> = emptyList(),
)

/** A second stamped fragment. */
@NodeFragment(labels = ["Watermark"])
data class Watermark(
    @NodeId val id: String,
    val at: Int,
    @NodeStamp val stamp: String? = null,
)
