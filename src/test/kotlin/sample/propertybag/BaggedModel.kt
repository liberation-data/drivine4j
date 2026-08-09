package sample.propertybag

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.Root

/**
 * Fixtures for `@PropertyBag` tests: a fragment with an open `metadata` bag alongside declared
 * fields, a view wrapping it, and a relationship target that also carries a bag (to prove bags
 * round-trip through `@GraphView` projections).
 */
@NodeFragment(labels = ["Bagged"])
data class BaggedNode(
    @NodeId val id: String,
    val title: String,
    @PropertyBag val metadata: Map<String, Any?> = emptyMap(),
)

@NodeFragment(labels = ["Tag"])
data class TaggedNode(
    @NodeId val id: String,
    val name: String,
    @PropertyBag(prefix = "attr") val attributes: Map<String, Any?> = emptyMap(),
)

@GraphView
data class BaggedView(
    @Root val node: BaggedNode,
    @GraphRelationship(type = "HAS_TAG", direction = Direction.OUTGOING)
    val tags: List<TaggedNode> = emptyList(),
)

/**
 * Typed property bags: the declared value type is what Jackson coerces to on read, so these come
 * back as `Int` / `Double` / `Instant` rather than the driver's widened `Long` / `Double` / temporal.
 * The `Map<String, Any?>` asymmetry ([BaggedNode.metadata]) applies only to untyped bags.
 */
@NodeFragment(labels = ["TypedBag"])
data class TypedBagNode(
    @NodeId val id: String,
    @PropertyBag(prefix = "score") val scores: Map<String, Int> = emptyMap(),
    @PropertyBag(prefix = "ratio") val ratios: Map<String, Double> = emptyMap(),
    @PropertyBag(prefix = "label") val labels: Map<String, String> = emptyMap(),
    @PropertyBag(prefix = "flag") val flags: Map<String, Boolean> = emptyMap(),
    @PropertyBag(prefix = "at") val timestamps: Map<String, java.time.Instant> = emptyMap(),
)

/** Two non-overlapping bags on one fragment — supported. */
@NodeFragment(labels = ["TwoBag"])
data class TwoBagNode(
    @NodeId val id: String,
    @PropertyBag(prefix = "meta") val metadata: Map<String, Any?> = emptyMap(),
    @PropertyBag(prefix = "attr") val attributes: Map<String, Any?> = emptyMap(),
)

/** Overlapping bag prefixes (`a.` is a prefix of `a.b.`) — rejected at model build. */
@NodeFragment(labels = ["Overlap"])
data class OverlapNode(
    @NodeId val id: String,
    @PropertyBag(prefix = "a") val one: Map<String, Any?> = emptyMap(),
    @PropertyBag(prefix = "a.b") val two: Map<String, Any?> = emptyMap(),
)