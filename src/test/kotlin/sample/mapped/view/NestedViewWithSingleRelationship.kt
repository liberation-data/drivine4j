package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person

/**
 * A GraphView that has a single-object (non-collection) relationship.
 * Used to test that nested GraphViews correctly use [0] for single relationships.
 */
@GraphView
data class NestedViewWithSingleRelationship(
    @Root val person: Person,

    // Single-object relationship (NOT a List) - should use [0] in query
    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val primaryEmployer: Organization?
)