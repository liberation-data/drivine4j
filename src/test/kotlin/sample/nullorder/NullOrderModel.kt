package sample.nullorder

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/**
 * A node some of whose rows have no value for the property they are ordered by: the shape a null
 * placement (`desc().nullsLast()`) is for. Loaded as a fragment and as the root of a view, since the
 * two order by different paths.
 */
@NodeFragment(labels = ["Ranked"])
data class RankedNode(
    @NodeId val id: String,
    val score: Double? = null,
)

@NodeFragment(labels = ["RankedTag"])
data class RankedTag(
    @NodeId val id: String,
    val weight: Int? = null,
)

@GraphView
data class RankedView(
    @Root val ranked: RankedNode,
    @GraphRelationship(type = "TAGGED", direction = Direction.OUTGOING)
    val tags: List<RankedTag> = emptyList(),
)

/** Hand-written DSLs mirroring the codegen shape (the test source set does not run KSP). */
class RankedNodeQueryDsl(override val nodeAlias: String = "n") : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val score = PropertyReference<Double>(nodeAlias, "score")

    companion object {
        val INSTANCE = RankedNodeQueryDsl()
    }
}

class RankedTagProperties(override val nodeAlias: String) : NodeReference {
    val id = StringPropertyReference(nodeAlias, "id")
    val weight = PropertyReference<Int>(nodeAlias, "weight")
}

class RankedViewQueryDsl {
    val ranked = RankedNodeQueryDsl("ranked")
    val tags = RankedTagProperties("tags")

    companion object {
        val INSTANCE = RankedViewQueryDsl()
    }
}
