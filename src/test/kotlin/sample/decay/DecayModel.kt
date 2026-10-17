package sample.decay

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
import java.time.Instant

/**
 * A belief whose worth decays with the days since it was last read, and which is worth nothing
 * outside the window it is valid in: the shape an order by an expression is for. The moment it was
 * last read is held twice, as a moment and as seconds since the epoch, since not every engine can
 * read a moment as a number.
 */
@NodeFragment(labels = ["Belief"])
data class Belief(
    @NodeId val id: String,
    val confidence: Double,
    val decay: Double,
    val lastReadSeconds: Long,
    val lastRead: Instant,
    val boost: Double? = null,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
)

@NodeFragment(labels = ["BeliefSource"])
data class BeliefSource(
    @NodeId val id: String,
    val weight: Int? = null,
)

@GraphView
data class BeliefView(
    @Root val belief: Belief,
    @GraphRelationship(type = "CITES", direction = Direction.OUTGOING)
    val sources: List<BeliefSource> = emptyList(),
)

/** Hand-written DSLs mirroring the codegen shape (the test source set does not run KSP). */
class BeliefQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val confidence = PropertyReference<Double>(nodeAlias, "confidence")
    val decay = PropertyReference<Double>(nodeAlias, "decay")
    val lastReadSeconds = PropertyReference<Long>(nodeAlias, "lastReadSeconds")
    val lastRead = PropertyReference<Instant>(nodeAlias, "lastRead")
    val boost = PropertyReference<Double>(nodeAlias, "boost")
    val validFrom = PropertyReference<Instant>(nodeAlias, "validFrom")
    val validTo = PropertyReference<Instant>(nodeAlias, "validTo")

    companion object {
        val INSTANCE = BeliefQueryDsl()
    }
}

class BeliefSourceProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val weight = PropertyReference<Int>(nodeAlias, "weight")
}

class BeliefViewQueryDsl {
    val belief = BeliefQueryDsl("belief")
    val sources = BeliefSourceProperties("sources")

    companion object {
        val INSTANCE = BeliefViewQueryDsl()
    }
}
