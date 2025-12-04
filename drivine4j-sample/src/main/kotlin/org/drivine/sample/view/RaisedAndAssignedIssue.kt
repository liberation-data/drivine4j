package org.drivine.sample.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.sample.fragment.Issue
import org.drivine.sample.fragment.IssueCore
import org.drivine.sample.fragment.Person

@GraphView
data class RaisedAndAssignedIssue(
    @Root val issue: IssueCore,

    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    val assignedTo: List<Person>,

    @GraphRelationship(type = "RAISED_BY", direction = Direction.OUTGOING)
    val raisedBy: PersonContext
) : Issue by issue
