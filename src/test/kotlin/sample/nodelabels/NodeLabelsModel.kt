package sample.nodelabels

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeLabels
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root

/** Fixtures for `@NodeLabels` and the flat `@PropertyBag`. */
enum class Role { Admin, Reviewer, Author }

/** A closed set: the field speaks for the three [Role] labels and no other. */
@NodeFragment(labels = ["Member"])
data class MemberNode(
    @NodeId val id: String,
    val name: String,
    @NodeLabels val roles: Set<Role> = emptySet(),
)

/** An open set and a flat bag: the shape of a node whose labels and properties are both open-ended. */
@NodeFragment(labels = ["Thing"])
data class ThingNode(
    @NodeId val id: String,
    val name: String,
    @NodeLabels val labels: Set<String> = emptySet(),
    @PropertyBag(flat = true) val properties: Map<String, Any?> = emptyMap(),
)

/** A flat bag beside a prefixed one: the flat bag must leave the prefixed bag's properties alone. */
@NodeFragment(labels = ["Mixed"])
data class MixedBagNode(
    @NodeId val id: String,
    @PropertyBag(prefix = "meta") val meta: Map<String, Any?> = emptyMap(),
    @PropertyBag(flat = true) val rest: Map<String, Any?> = emptyMap(),
)

@GraphView
data class ThingView(
    @Root val thing: ThingNode,
    @GraphRelationship(type = "OWNED_BY", direction = Direction.OUTGOING)
    val owners: List<MemberNode> = emptyList(),
)

/** Rejected at model build: the enum names the fragment's own label. */
enum class Clash { Clashing, Other }

@NodeFragment(labels = ["Clashing"])
data class ClashingNode(
    @NodeId val id: String,
    @NodeLabels val kinds: Set<Clash> = emptySet(),
)

/** Rejected at model build: a property under the prefix Drivine keeps for itself. */
@NodeFragment(labels = ["Trespassing"])
data class TrespassingNode(
    @NodeId val id: String,
    @PropertyBag(prefix = "__drivine.mine") val mine: Map<String, Any?> = emptyMap(),
)
