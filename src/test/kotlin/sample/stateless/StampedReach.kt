package sample.stateless

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root

/** A relationship that holds at most one node, and that node carries a stamp: the claim a claim rests on. */
@GraphView
data class ClaimBasis(
    @Root val claim: Claim,
    @GraphRelationship(type = "RESTS_ON", direction = Direction.OUTGOING)
    val basis: Claim? = null,
)

/** A view of views with a stamp at each level: the root of each nested view carries one, and so does each node it holds. */
@GraphView
data class ClaimDigest(
    @Root val memo: Memo,
    @GraphRelationship(type = "DIGESTS", direction = Direction.OUTGOING)
    val parts: List<ClaimSupports> = emptyList(),
)
