package sample.stateless

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root

/** A stamped node held by two fields of one view: the claim that leads, and the claims supported. */
@GraphView
data class ClaimPair(
    @Root val claim: Claim,
    @GraphRelationship(type = "LED_BY", direction = Direction.OUTGOING)
    val lead: Claim? = null,
    @GraphRelationship(type = "SUPPORTS", direction = Direction.OUTGOING)
    val supports: List<Claim> = emptyList(),
)

@NodeFragment(labels = ["Worker"])
data class Worker(@NodeId val id: String, val name: String)

/** A node that carries the labels of a [Worker] and one more, so a field of workers reads it too. */
@NodeFragment(labels = ["Worker", "Manager"])
data class Manager(@NodeId val id: String, val name: String)

/** Two fields over one relationship type whose targets overlap: every manager is a worker. */
@GraphView
data class ClaimStaff(
    @Root val claim: Claim,
    @GraphRelationship(type = "ASSIGNED", direction = Direction.OUTGOING)
    val workers: List<Worker> = emptyList(),
    @GraphRelationship(type = "ASSIGNED", direction = Direction.OUTGOING)
    val managers: List<Manager> = emptyList(),
)

/** A field of people beside a field of views that hold people too. The fields are saved in the order of their names. */
@GraphView
data class Board(
    @Root val memo: Memo,
    @GraphRelationship(type = "LISTS", direction = Direction.OUTGOING)
    val attendees: List<Human> = emptyList(),
    @GraphRelationship(type = "COVERS", direction = Direction.OUTGOING)
    val claims: List<ClaimView> = emptyList(),
)

/** A stamped node no copy can be made of: it is not a data class, its fields cannot be set, and it cannot be rebuilt. */
@NodeFragment(labels = ["Odd"])
class Odd(@NodeId val id: String, seed: Any) {
    val text: String = "made from $seed"

    @NodeStamp
    val stamp: String? = null
}

/** A view whose root is declared by the class it extends. */
abstract class RootedInClaim(@Root val claim: Claim)

@GraphView
class InheritedClaimView(
    claim: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
) : RootedInClaim(claim)

/** A view that is neither a data class nor has fields that can be set, holding stamped nodes. */
@GraphView
class FrozenBoard(
    @Root val frozen: Frozen,
    @GraphRelationship(type = "SUPPORTS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
)

/** A view that holds its stamped nodes in a set. */
@GraphView
data class ClaimSet(
    @Root val claim: Claim,
    @GraphRelationship(type = "SUPPORTS", direction = Direction.OUTGOING)
    val supports: Set<Claim> = emptySet(),
)

/** A view of views whose roots are stamped. */
@GraphView
data class ClaimDossier(
    @Root val claim: Claim,
    @GraphRelationship(type = "COVERS", direction = Direction.OUTGOING)
    val covered: List<ClaimView> = emptyList(),
)

/** A stamp declared where none is kept: on a view, and on the properties of a relationship. */
@GraphView
data class StampedView(@Root val claim: Claim, @NodeStamp val stamp: String? = null)

@RelationshipFragment
data class StampedCitation(val page: Int, val target: Human, @NodeStamp val stamp: String? = null)

@GraphView
data class ClaimStampedCitations(
    @Root val claim: Claim,
    @GraphRelationship(type = "CITES", direction = Direction.OUTGOING)
    val cited: List<StampedCitation> = emptyList(),
)
