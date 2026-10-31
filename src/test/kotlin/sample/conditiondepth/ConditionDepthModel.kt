package sample.conditiondepth

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * Fixtures for a condition on a list of fragments read over several hops (`maxDepth` above 1), which
 * tests the nodes the load reads: every one within the field's depth, not only those one hop away.
 */
@NodeFragment(labels = ["CdNode"])
data class CdNode(@NodeId val id: String, val text: String? = null)

@NodeFragment(labels = ["CdHolder"])
data class CdHolder(@NodeId val id: String)

/** The nodes up to two hops along `NEXT`. */
@GraphView
data class CdWindowView(
    @Root val node: CdNode,
    @GraphRelationship(type = "NEXT", direction = Direction.OUTGOING, maxDepth = 2)
    val following: List<CdNode> = emptyList(),
)

/** A view that holds the windows: a condition on their field is one of a nested view. */
@GraphView
data class CdHolderView(
    @Root val holder: CdHolder,
    @GraphRelationship(type = "HAS", direction = Direction.OUTGOING)
    val windows: List<CdWindowView> = emptyList(),
)

@NodeFragment(labels = ["CdPeer"])
data class CdPeer(@NodeId val id: String)

/**
 * The peers up to two hops along `LINK`, either way: a walk back over the relationship it came by
 * reaches the node it started from, which no path does, since a path takes a relationship once.
 */
@GraphView
data class CdPeerView(
    @Root val peer: CdPeer,
    @GraphRelationship(type = "LINK", direction = Direction.UNDIRECTED, maxDepth = 2)
    val peers: List<CdPeer> = emptyList(),
)

// Hand-written query DSL, mirroring what `drivine4j-codegen` emits.

class CdNodeProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val text = StringPropertyReference(nodeAlias, "text")
}

class CdWindowViewQueryDsl {
    val node = CdNodeProperties("node")
    val following = CdNodeProperties("following")

    companion object {
        val INSTANCE = CdWindowViewQueryDsl()
    }
}

class CdPeerViewQueryDsl {
    val peer = CdNodeProperties("peer")
    val peers = CdNodeProperties("peers")

    companion object {
        val INSTANCE = CdPeerViewQueryDsl()
    }
}

class CdWindowProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val text = StringPropertyReference(nodeAlias, "text")
    val following = CdNodeProperties("${nodeAlias}_following")
}

class CdHolderViewQueryDsl {
    val holder = CdNodeProperties("holder")
    val windows = CdWindowProperties("windows")

    companion object {
        val INSTANCE = CdHolderViewQueryDsl()
    }
}
