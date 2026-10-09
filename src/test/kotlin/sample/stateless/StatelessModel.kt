package sample.stateless

import org.drivine.annotation.Aggregate
import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.Root

/** Fixtures for `StatelessGraphObjectManager`: Claim -[:MENTIONS]-> Human | Company, Human -[:WORKS_AT]-> Company. */
@NodeFragment(labels = ["Claim"])
data class Claim(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
)

/** A node type with no stamp field. */
@NodeFragment(labels = ["Memo"])
data class Memo(@NodeId val id: String, val text: String)

@NodeFragment(labels = ["Human"])
data class Human(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["Company"])
data class Company(@NodeId val id: String, val name: String)

/** Two fields over one relationship type, told apart by the label of the target. */
@GraphView
data class ClaimView(
    @Root val claim: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val companies: List<Company> = emptyList(),
)

/** A relationship field beside a path field whose first hop has the same type. */
@GraphView
data class ClaimEmployers(
    @Root val claim: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
    @ReadOnly
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Human"),
        Hop("WORKS_AT", Direction.OUTGOING),
    ])
    val employers: List<Company> = emptyList(),
)

@GraphView
data class MemoView(
    @Root val memo: Memo,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
)

/** A relationship field that is written beside one that is only loaded. */
@GraphView
data class ClaimReviewers(
    @Root val claim: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
    @ReadOnly
    @GraphRelationship(type = "REVIEWED_BY", direction = Direction.OUTGOING)
    val reviewers: List<Human> = emptyList(),
)

/** Computed fields, declared read-only as they must be. */
@GraphView
data class ClaimStats(
    @Root val claim: Claim,
    @ReadOnly @Count("MENTIONS") val mentionCount: Long,
)

/** Rejected at model build: a path field that is not declared read-only. */
@GraphView
data class UndeclaredPath(
    @Root val claim: Claim,
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Human"),
        Hop("WORKS_AT", Direction.OUTGOING),
    ])
    val employers: List<Company> = emptyList(),
)

/** Rejected at model build: a count that is not declared read-only. */
@GraphView
data class UndeclaredCount(
    @Root val claim: Claim,
    @Count("MENTIONS") val mentionCount: Long,
)

/** Rejected at model build: an aggregate that is not declared read-only. */
@GraphView
data class UndeclaredAggregate(
    @Root val claim: Claim,
    @Aggregate(AggregateFunction.AVG, type = "MENTIONS", property = "weight") val averageWeight: Double,
)
