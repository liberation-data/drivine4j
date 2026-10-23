package sample.stateless

import org.drivine.annotation.Aggregate
import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Count
import org.drivine.annotation.Default
import org.drivine.annotation.Direction
import org.drivine.annotation.EmptyWhenAbsent
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.JsonPacked
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root

/** Fixtures for `StatelessGraphObjectManager`: Claim -[:MENTIONS]-> Human | Company, Human -[:WORKS_AT]-> Company. */
@NodeFragment(labels = ["Claim"])
data class Claim(
    @NodeId val id: String,
    val text: String,
    val note: String? = null,
    @NodeStamp val stamp: String? = null,
)

/**
 * A node every writer of its id adds sources to: the aggregate `create` and `update` compose for. A
 * first write that overwrote another would lose that writer's sources.
 */
@NodeFragment(labels = ["Finding"])
data class Finding(
    @NodeId val id: String,
    val sources: List<String>,
    @NodeStamp val stamp: String? = null,
) {
    /** This finding with the sources of [other] it does not have yet. */
    fun merge(other: Finding): Finding = copy(sources = (sources + other.sources).distinct())
}

/**
 * Claims whose last field was added after nodes were saved without it, so a load fills it in: by
 * `@EmptyWhenAbsent`, and by `@Default`.
 */
@NodeFragment(labels = ["Claim"])
data class ClaimWithRoles(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    @EmptyWhenAbsent val roles: List<String>,
)

@NodeFragment(labels = ["Claim"])
data class ClaimWithStatus(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    @Default val status: String = "open",
)

/**
 * A claim with a property bag, so loaded with every property of the node, whose status was added
 * after nodes were saved without it: a load leaves the field out, and Kotlin fills in its default.
 */
@NodeFragment(labels = ["Claim"])
data class ClaimWithBagAndStatus(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    @PropertyBag(prefix = "meta") val meta: Map<String, Any?> = emptyMap(),
    val status: String = "open",
)

/**
 * A claim whose status may be null and has a constructor default: a load naming each field reads a
 * node without the property as null, so the default is not what such a load gives.
 */
@NodeFragment(labels = ["Claim"])
data class ClaimWithOptionalStatus(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    val status: String? = "open",
)

/**
 * A claim whose tags are packed as JSON text, which another writer may have packed another way: with
 * other spacing, as text that is not JSON, or as a list.
 */
@NodeFragment(labels = ["Claim"])
data class ClaimWithTags(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    @JsonPacked val tags: List<String>? = null,
)

/** A view whose root has packed tags. */
@GraphView
data class ClaimWithTagsView(
    @Root val claim: ClaimWithTags,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val people: List<Human> = emptyList(),
)

/** A claim whose packed tags were added after nodes were saved without them: a load gives them their default. */
@NodeFragment(labels = ["Claim"])
data class ClaimWithDefaultTags(
    @NodeId val id: String,
    val text: String,
    @NodeStamp val stamp: String? = null,
    @Default @JsonPacked val tags: List<String> = listOf("new"),
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

/** A relationship read against its direction: the claims that mention a person. */
@GraphView
data class HumanClaims(
    @Root val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

/** The same relationship read the other way round: a person who mentions claims. */
@GraphView
data class HumanMentions(
    @Root val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
)

/** An incoming relationship between two nodes of one label. */
@GraphView
data class HumanFollowers(
    @Root val human: Human,
    @GraphRelationship(type = "FOLLOWS", direction = Direction.INCOMING)
    val followers: List<Human> = emptyList(),
)

/** A relationship that carries a property: the page a claim cites a person on. */
@RelationshipFragment
data class Citation(val page: Int, val target: Human)

@GraphView
data class ClaimCitations(
    @Root val claim: Claim,
    @GraphRelationship(type = "CITES", direction = Direction.OUTGOING)
    val cited: List<Citation> = emptyList(),
)

/** A view of views: a memo and the claims it covers, each with the people it mentions. */
@GraphView
data class Dossier(
    @Root val memo: Memo,
    @GraphRelationship(type = "COVERS", direction = Direction.OUTGOING)
    val claims: List<ClaimView> = emptyList(),
)

/** A computed field declared read-only, which it is anyway. */
@GraphView
data class ClaimStats(
    @Root val claim: Claim,
    @ReadOnly @Count("MENTIONS") val mentionCount: Long,
)

/** A path field that is not declared read-only, and is read-only all the same. */
@GraphView
data class UndeclaredPath(
    @Root val claim: Claim,
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Human"),
        Hop("WORKS_AT", Direction.OUTGOING),
    ])
    val employers: List<Company> = emptyList(),
)

/** A count that is not declared read-only, and is never written all the same. */
@GraphView
data class UndeclaredCount(
    @Root val claim: Claim,
    @Count("MENTIONS") val mentionCount: Long,
)

/** An aggregate that is not declared read-only, and is never written all the same. */
@GraphView
data class UndeclaredAggregate(
    @Root val claim: Claim,
    @Aggregate(AggregateFunction.AVG, type = "MENTIONS", property = "weight") val averageWeight: Double,
)
