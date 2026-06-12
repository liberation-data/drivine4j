package sample.vector

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/**
 * A view rooted on [DocNode] with a **required** single author. The required relationship is what
 * exercises the post-filter semantics of vector search: a Doc with no `WRITTEN_BY` edge is pruned
 * *after* the K-nearest search, so `loadNearest` can return fewer than `topK` rows.
 */
@GraphView
data class DocView(
    @Root val doc: DocNode,

    @GraphRelationship(type = "WRITTEN_BY", direction = Direction.OUTGOING)
    val author: AuthorNode,
)