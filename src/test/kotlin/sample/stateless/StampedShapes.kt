package sample.stateless

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root

/** A stamped node whose fields can be set, as a Java object with setters has them. */
@NodeFragment(labels = ["Draft"])
class Draft(
    @NodeId var id: String = "",
    var text: String = "",
    var tags: List<String>? = null,
    @NodeStamp var stamp: String? = null,
)

/** A stamped node that is neither a data class nor has fields that can be set. */
@NodeFragment(labels = ["Frozen"])
class Frozen(@NodeId val id: String, val text: String, @NodeStamp val stamp: String? = null)

/** A stamped node with a property bag, which is loaded with every property of the node. */
@NodeFragment(labels = ["Tagged"])
data class Tagged(
    @NodeId val id: String,
    val text: String,
    @PropertyBag(prefix = "meta") val meta: Map<String, Any?> = emptyMap(),
    @NodeStamp val stamp: String? = null,
)

/** A view whose fields can be set. */
@GraphView
class DraftBoard(
    @Root var draft: Draft = Draft(),
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    var people: MutableList<Human> = mutableListOf(),
)

/** A relationship read in either direction. */
@GraphView
data class Pals(
    @Root val human: Human,
    @GraphRelationship(type = "KNOWS", direction = Direction.UNDIRECTED)
    val pals: List<Human> = emptyList(),
)

/** A relationship that holds at most one node. */
@GraphView
data class ClaimLead(
    @Root val claim: Claim,
    @GraphRelationship(type = "LED_BY", direction = Direction.OUTGOING)
    val lead: Human? = null,
)

/** A relationship with a property, read against its direction: the claims that cite a person. */
@RelationshipFragment
data class CitedBy(val page: Int, val target: Claim)

@GraphView
data class HumanCitations(
    @Root val human: Human,
    @GraphRelationship(type = "CITES", direction = Direction.INCOMING)
    val citedBy: List<CitedBy> = emptyList(),
)

/** A claim and the claims it supports. */
@GraphView
data class ClaimSupports(
    @Root val claim: Claim,
    @GraphRelationship(type = "SUPPORTS", direction = Direction.OUTGOING)
    val supports: List<Claim> = emptyList(),
)

/** The same relationship from its other end: a claim and the claims that support it. */
@GraphView
data class ClaimSupporters(
    @Root val claim: Claim,
    @GraphRelationship(type = "SUPPORTS", direction = Direction.INCOMING)
    val supporters: List<Claim> = emptyList(),
)

/** A view whose related nodes carry a property bag, which no batch of rows can write. */
@GraphView
data class ClaimTags(
    @Root val claim: Claim,
    @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING)
    val tags: List<Tagged> = emptyList(),
)
