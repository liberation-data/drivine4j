package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person

@GraphView
data class PersonContext(
    val person: Person,

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING, alias = "assigned")
    val worksFor: List<Organization>
)
