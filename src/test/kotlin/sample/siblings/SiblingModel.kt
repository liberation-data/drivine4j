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
