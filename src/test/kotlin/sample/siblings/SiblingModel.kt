package sample.siblings

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root

/** A view held by another whose two relationships are siblings: a where on both must hold of one desk. */
@NodeFragment(labels = ["SibOffice"])
data class SibOffice(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["SibStaffer"])
data class SibStaffer(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["SibFirm"])
data class SibFirm(@NodeId val id: String, val name: String)

@GraphView
data class SibDesk(
    @Root val staffer: SibStaffer,
    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING) val employer: SibFirm?,
    @GraphRelationship(type = "MENTORED_BY", direction = Direction.OUTGOING) val mentor: SibStaffer?,
)

@GraphView
data class SibOfficeView(
    @Root val office: SibOffice,
    @GraphRelationship(type = "SEATS", direction = Direction.OUTGOING) val desks: List<SibDesk>,
)

@NodeFragment(labels = ["SibCampus"])
data class SibCampus(@NodeId val id: String, val name: String)

/** A view that holds [SibOfficeView]: its desks' sibling relationships are two levels down. */
@GraphView
data class SibCampusView(
    @Root val campus: SibCampus,
    @GraphRelationship(type = "HAS_OFFICE", direction = Direction.OUTGOING) val offices: List<SibOfficeView>,
)

/** A desk whose mentor is a [SibDesk]: one sibling leads a level further than the other. */
@GraphView
data class SibMentoredDesk(
    @Root val staffer: SibStaffer,
    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING) val employer: SibFirm?,
    @GraphRelationship(type = "MENTORED_BY", direction = Direction.OUTGOING) val mentor: SibDesk?,
)

@GraphView
data class SibMentoredOfficeView(
    @Root val office: SibOffice,
    @GraphRelationship(type = "SEATS", direction = Direction.OUTGOING) val desks: List<SibMentoredDesk>,
)
