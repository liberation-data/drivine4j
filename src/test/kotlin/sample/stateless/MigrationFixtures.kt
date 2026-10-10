package sample.stateless

import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.Root

/** Fixtures for the migration tools, beside those in `StatelessModel.kt`. */

/** A kind of person: every node it names is a `Human` node too. */
@NodeFragment(labels = ["VipHuman", "Human"])
data class VipHuman(@NodeId val id: String, val name: String)

/** A person of one kind who mentions claims: the relationship `HumanClaims` reads the other way round. */
@GraphView
data class VipMentions(
    @Root val vip: VipHuman,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
)

/** The claims that mention a person of one kind, read against the direction `HumanMentions` declares. */
@GraphView
data class VipClaims(
    @Root val vip: VipHuman,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

/** A person who mentions claims, where other code writes the relationships and the view only loads them. */
@GraphView
data class HumanMentionsLoaded(
    @Root val human: Human,
    @ReadOnly
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
)

/** A claim's companies, where other code writes the relationships and the view only loads them. */
@GraphView
data class ClaimCompaniesLoaded(
    @Root val claim: Claim,
    @ReadOnly
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val companies: List<Company> = emptyList(),
)

/** A view of views whose nested view reads a relationship against its direction. */
@GraphView
data class MemoPeople(
    @Root val memo: Memo,
    @GraphRelationship(type = "NAMES", direction = Direction.OUTGOING)
    val people: List<HumanClaims> = emptyList(),
)

/** A path whose first hop names no label. */
@GraphView
data class ClaimAnyEmployers(
    @Root val claim: Claim,
    @ReadOnly
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING),
        Hop("WORKS_AT", Direction.OUTGOING),
    ])
    val employers: List<Company> = emptyList(),
)

/** A path whose first hop reaches the kind of node the path ends at. */
@GraphView
data class HumanHoldings(
    @Root val human: Human,
    @ReadOnly
    @GraphPath([
        Hop("OWNS", Direction.OUTGOING, label = "Company"),
        Hop("OWNS", Direction.OUTGOING),
    ])
    val holdings: List<Company> = emptyList(),
)

@NodeFragment(labels = ["Organization"])
data class Organization(@NodeId val id: String, val name: String)

/** A company that is an organization too: a first hop to a `Company` can end at one. */
@NodeFragment(labels = ["Company", "Organization"])
data class Corporation(@NodeId val id: String, val name: String)

/** A path whose first hop reaches a `Company` and which ends at an `Organization`. */
@GraphView
data class HumanGroups(
    @Root val human: Human,
    @ReadOnly
    @GraphPath([
        Hop("OWNS", Direction.OUTGOING, label = "Company"),
        Hop("PART_OF", Direction.OUTGOING),
    ])
    val groups: List<Organization> = emptyList(),
)

@GraphView
data class CorporationStaff(
    @Root val corporation: Corporation,
    @GraphRelationship(type = "EMPLOYS", direction = Direction.OUTGOING)
    val staff: List<Human> = emptyList(),
)

/** The companies that are part of an organization, read against their direction. A `Corporation` is both. */
@GraphView
data class OrganizationParts(
    @Root val organization: Organization,
    @GraphRelationship(type = "PART_OF", direction = Direction.INCOMING)
    val parts: List<Company> = emptyList(),
)

/** A path whose first hop is the relationship `ClaimEmployers.employers` was written as: a claim mentions a company. */
@GraphView
data class ClaimOwners(
    @Root val claim: Claim,
    @ReadOnly
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Company"),
        Hop("OWNED_BY", Direction.OUTGOING),
    ])
    val owners: List<Human> = emptyList(),
)

/** A path whose first hop points at the root, from the kind of node the path ends at. */
@GraphView
data class HumanBackers(
    @Root val human: Human,
    @ReadOnly
    @GraphPath([
        Hop("BACKS", Direction.INCOMING, label = "Company"),
        Hop("OWNS", Direction.OUTGOING),
    ])
    val holdings: List<Company> = emptyList(),
)

/** A fragment with no label: any node is one. */
@NodeFragment
data class Thing(@NodeId val id: String)

/** The claims that mention a node of any label. */
@GraphView
data class ThingClaims(
    @Root val thing: Thing,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

/** A path from a node of any label. */
@GraphView
data class ThingEmployers(
    @Root val thing: Thing,
    @ReadOnly
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Human"),
        Hop("WORKS_AT", Direction.OUTGOING),
    ])
    val employers: List<Company> = emptyList(),
)

/** A path from a person through the claims they mention: its first hop is the relationship `HumanClaims` reads the other way round. */
@GraphView
data class HumanClaimSources(
    @Root val human: Human,
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Claim"),
        Hop("ABOUT", Direction.OUTGOING),
    ])
    val companies: List<Company> = emptyList(),
)

/** The claims that mention a person, declared read-only when the model was upgraded. */
@GraphView
data class HumanClaimsLoaded(
    @Root val human: Human,
    @ReadOnly
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

/** A person and how many relationships they have to anything they mention: a count names no target. */
@GraphView
data class HumanMentionCount(
    @Root val human: Human,
    @Count("MENTIONS") val mentioned: Long = 0,
)

/** A claim and how many relationships it has to anything it mentions. */
@GraphView
data class ClaimMentionCount(
    @Root val claim: Claim,
    @Count("MENTIONS") val mentioned: Long = 0,
)

/**
 * A path from a person to claims, whose hops are not the relationship `HumanClaims` reads: the old
 * save wrote it as one all the same, a `MENTIONS` from the person straight to each claim.
 */
@GraphView
data class HumanClaimsByEmployer(
    @Root val human: Human,
    @GraphPath([
        Hop("MENTIONS", Direction.OUTGOING, label = "Company"),
        Hop("ABOUT", Direction.OUTGOING),
    ])
    val claims: List<Claim> = emptyList(),
)

/** The relationship `HumanClaims` reads, read either way round. */
@GraphView
data class HumanMentionsEitherWay(
    @Root val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.UNDIRECTED)
    val claims: List<Claim> = emptyList(),
)

/** `HumanClaims` from its other end, read against its direction too: the people a claim is mentioned by. */
@GraphView
data class ClaimMentioners(
    @Root val claim: Claim,
    @GraphRelationship(type = "MENTIONS", direction = Direction.INCOMING)
    val people: List<Human> = emptyList(),
)

/** A view of views of views: `HumanClaims` is two levels down. */
@GraphView
data class CompanyMemos(
    @Root val company: Company,
    @GraphRelationship(type = "FILES", direction = Direction.OUTGOING)
    val memos: List<MemoPeople> = emptyList(),
)

/** A view nested in itself, read against its direction: an organization and those that report to it. */
@GraphView
data class OrganizationTree(
    @Root val organization: Organization,
    @GraphRelationship(type = "REPORTS_TO", direction = Direction.INCOMING)
    val reports: List<OrganizationTree> = emptyList(),
)

/** The claims that cover a memo, read against their direction. */
@GraphView
data class MemoCoveredBy(
    @Root val memo: Memo,
    @GraphRelationship(type = "COVERS", direction = Direction.INCOMING)
    val claims: List<Claim> = emptyList(),
)

/** The same, read over several hops: the old save wrote each claim as one `COVERS` from the memo. */
@GraphView
data class MemoCoverChain(
    @Root val memo: Memo,
    @GraphRelationship(type = "COVERS", direction = Direction.INCOMING, maxDepth = 5)
    val chain: List<Claim> = emptyList(),
)

/** A kind of view that names its subtypes. */
@GraphView
sealed class HumanCard {
    abstract val human: Human
}

/** One of them, which declares the relationship `HumanClaims` reads, pointing away from the root. */
@GraphView
data class MentioningCard(
    @Root override val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
) : HumanCard()

/** A view that holds views of that kind. */
@GraphView
data class MemoCards(
    @Root val memo: Memo,
    @GraphRelationship(type = "SHOWS", direction = Direction.OUTGOING)
    val cards: List<HumanCard> = emptyList(),
)

/** A kind of view that names no subtype: nothing says which views are of this kind. */
@GraphView
abstract class HumanSheet {
    abstract val human: Human
}

/** One of them. */
@GraphView
data class MentioningSheet(
    @Root override val human: Human,
    @GraphRelationship(type = "MENTIONS", direction = Direction.OUTGOING)
    val claims: List<Claim> = emptyList(),
) : HumanSheet()

/** A view that holds views of that kind. */
@GraphView
data class MemoSheets(
    @Root val memo: Memo,
    @GraphRelationship(type = "SHOWS", direction = Direction.OUTGOING)
    val sheets: List<HumanSheet> = emptyList(),
)
