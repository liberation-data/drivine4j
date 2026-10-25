package sample.sortpath

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/**
 * Collections to sort by a path that a split on `_` misreads: a relationship named with an underscore
 * at the top and beneath, and a collection three levels down.
 */
@NodeFragment(labels = ["SpItem"])
data class SpItem(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["SpOrg"])
data class SpOrg(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["SpGroup"])
data class SpGroup(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["SpTeam"])
data class SpTeam(@NodeId val id: String, val name: String)

@GraphView
data class SpTeamView(
    @Root val team: SpTeam,
    @GraphRelationship(type = "HAS_MEMBER", direction = Direction.OUTGOING) val members: List<SpItem>,
)

@GraphView
data class SpGroupView(
    @Root val group: SpGroup,
    @GraphRelationship(type = "HAS_SUB_TEAM", direction = Direction.OUTGOING) val sub_teams: List<SpTeamView>,
)

@GraphView
data class SpOrgView(
    @Root val org: SpOrg,
    @GraphRelationship(type = "HAS_UNIT", direction = Direction.OUTGOING) val sub_units: List<SpItem>,
    @GraphRelationship(type = "HAS_GROUP", direction = Direction.OUTGOING) val groups: List<SpGroupView>,
)
