package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person

@GraphView
data class RaisedAndAssignedIssue(
    val issue: Issue,

    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING, alias = "assigned")
    val assignedTo: List<Person>,

    @GraphRelationship(type = "RAISED_BY", direction = Direction.OUTGOING, alias = "raiser")
    val raisedBy: PersonContext
)
