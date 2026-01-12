package sample.mapped.view

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person

/**
 * Level 3 - The deeply nested GraphView.
 * When loaded directly, works fine. But when nested 3 levels deep, the @Root is null.
 */
@GraphView
data class DeeplyNestedView(
    @Root val person: Person,  // This should NOT be null when nested

    @GraphRelationship(type = "WORKS_FOR", direction = Direction.OUTGOING)
    val employer: Organization?
)

/**
 * Level 2 - Middle level GraphView that references Level 3.
 * This is inside a List in Level 1.
 */
@GraphView
data class MiddleLevelView(
    @Root val issue: sample.mapped.fragment.Issue,

    // This relationship points to another GraphView (Level 3)
    @GraphRelationship(type = "RAISED_BY", direction = Direction.OUTGOING)
    val raisedBy: DeeplyNestedView?
)

/**
 * Level 1 - Top level GraphView with List of Level 2.
 * Loading this should correctly populate all 3 levels.
 */
@GraphView
data class TopLevelWithNestedGraphViews(
    @Root val root: Organization,

    // List of Level 2 GraphViews, each containing Level 3
    @GraphRelationship(type = "HAS_ISSUES", direction = Direction.OUTGOING)
    val issues: List<MiddleLevelView>
)