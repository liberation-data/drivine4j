package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import sample.mapped.fragment.Issue

/**
 * A GraphView that contains a List of another GraphView which has single-object relationships.
 * Used to test that single relationships inside nested GraphViews use [0] in queries.
 */
@GraphView
data class ParentViewWithNestedList(
    @Root val issue: Issue,

    // List of nested GraphViews - each has a single primaryEmployer relationship
    @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
    val assignees: List<NestedViewWithSingleRelationship>
)