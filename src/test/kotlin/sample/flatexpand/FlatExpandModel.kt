package sample.flatexpand

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * Fixtures for the flat `List<Fragment>` variable-length (`*1..N`) traversal: `maxDepth` on a flat
 * fragment list returns the transitive N-hop neighbourhood as a de-duplicated flat list.
 */

@NodeFragment(labels = ["Seq"])
data class SeqNode(@NodeId val id: String, val text: String? = null)

/** Anchor + everything reachable within N hops, forward and backward, flat. */
@GraphView
data class SeqExpandView(
    @Root val node: SeqNode,
    @GraphRelationship(type = "NEXT", direction = Direction.OUTGOING, maxDepth = 10)
    val following: List<SeqNode>,
    @GraphRelationship(type = "NEXT", direction = Direction.INCOMING, maxDepth = 10)
    val preceding: List<SeqNode>,
)

/** A tighter window, to prove `maxDepth` bounds the traversal (and `depth()` can override it). */
@GraphView
data class SeqWindow2View(
    @Root val node: SeqNode,
    @GraphRelationship(type = "NEXT", direction = Direction.OUTGOING, maxDepth = 2)
    val following: List<SeqNode>,
)

/** Default `maxDepth` (1) — must stay a single-hop comprehension (regression guard). */
@GraphView
data class SeqOneHopView(
    @Root val node: SeqNode,
    @GraphRelationship(type = "NEXT", direction = Direction.OUTGOING)
    val next: List<SeqNode>,
)

// ----- Polymorphic flat variable-length list -----

@NodeFragment(labels = ["Elem"])
sealed interface ElemNode {
    @get:NodeId val id: String
}

@NodeFragment(labels = ["Elem", "TextElem"])
data class TextElem(override val id: String, val body: String? = null) : ElemNode

@NodeFragment(labels = ["Elem", "ImageElem"])
data class ImageElem(override val id: String, val url: String? = null) : ElemNode

@NodeFragment(labels = ["ChainHead"])
data class ChainHeadNode(@NodeId val id: String)

/** A variable-length flat list of a **polymorphic** fragment — each reached node dispatched by type. */
@GraphView
data class ElemChainView(
    @Root val head: ChainHeadNode,
    @GraphRelationship(type = "FOLLOWED_BY", direction = Direction.OUTGOING, maxDepth = 10)
    val rest: List<ElemNode>,
)
