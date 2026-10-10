package sample.stateless

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphTransient
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root

/** Two lists of one kind of node, each over a relationship of its own: a person is in the backlog, or is done. */
@GraphView
data class ClaimQueues(
    @Root val claim: Claim,
    @GraphRelationship(type = "WAITING", direction = Direction.OUTGOING)
    val backlog: List<Human> = emptyList(),
    @GraphRelationship(type = "DONE", direction = Direction.OUTGOING)
    val done: List<Human> = emptyList(),
)

/** One kind of node reached both ways: the people a claim names, and the people who endorse it. */
@GraphView
data class ClaimCircle(
    @Root val claim: Claim,
    @GraphRelationship(type = "NAMES", direction = Direction.OUTGOING)
    val named: List<Human> = emptyList(),
    @GraphRelationship(type = "ENDORSES", direction = Direction.INCOMING)
    val endorsers: List<Human> = emptyList(),
)

/** A node whose id is stored under another name than its field's. */
@NodeFragment(labels = ["Widget"])
data class Widget(
    @NodeId @GraphProperty("widget_key") val key: String,
    val name: String,
    @NodeStamp val stamp: String? = null,
)

@GraphView
data class WidgetView(
    @Root val widget: Widget,
    @GraphRelationship(type = "USED_BY", direction = Direction.OUTGOING)
    val users: List<Human> = emptyList(),
)

/** A node with a computed property that is not stored. */
@NodeFragment(labels = ["Thread"])
data class Thread(@NodeId val id: String, val title: String) {
    @get:GraphTransient
    val shout: String get() = title.uppercase()
}

@GraphView
data class ClaimThreads(
    @Root val claim: Claim,
    @GraphRelationship(type = "IN_THREAD", direction = Direction.OUTGOING)
    val threads: List<Thread> = emptyList(),
)

/** Stamped nodes joined by a relationship read in either direction. */
@GraphView
data class ClaimPeers(
    @Root val claim: Claim,
    @GraphRelationship(type = "PEER", direction = Direction.UNDIRECTED)
    val peers: List<Claim> = emptyList(),
)

/** A node whose id is a number. */
@NodeFragment(labels = ["Ticket"])
data class Ticket(@NodeId val id: Long, val title: String)

@GraphView
data class ClaimTickets(
    @Root val claim: Claim,
    @GraphRelationship(type = "TRACKS", direction = Direction.OUTGOING)
    val tickets: List<Ticket> = emptyList(),
)

/** A relationship with a property that can be null. */
@RelationshipFragment
data class Remark(val note: String?, val target: Human)

@GraphView
data class ClaimRemarks(
    @Root val claim: Claim,
    @GraphRelationship(type = "REMARKS", direction = Direction.OUTGOING)
    val remarks: List<Remark> = emptyList(),
)

/** The same, to a node with a property bag, which has a part of the statement to itself. */
@RelationshipFragment
data class TagRemark(val note: String?, val target: Tagged)

@GraphView
data class ClaimTagRemarks(
    @Root val claim: Claim,
    @GraphRelationship(type = "TAG_REMARKS", direction = Direction.OUTGOING)
    val remarks: List<TagRemark> = emptyList(),
)