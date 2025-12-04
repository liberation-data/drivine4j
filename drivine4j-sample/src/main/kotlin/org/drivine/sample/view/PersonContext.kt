package org.drivine.sample.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.sample.fragment.Organization
import org.drivine.sample.fragment.Person

@GraphView
data class PersonContext(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val worksFor: List<Organization>
)
