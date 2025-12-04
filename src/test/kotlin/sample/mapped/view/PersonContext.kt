package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person

@GraphView
data class PersonContext(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val worksFor: List<Organization>
)
