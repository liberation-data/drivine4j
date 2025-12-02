package org.drivine.model

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphNodeId
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphRelationshipFragment
import org.drivine.annotation.GraphView

@GraphFragment(labels = ["TestNode"])
data class TestNode(
    @GraphNodeId
    val id: String,
    val name: String
)

@GraphRelationshipFragment
data class TestRelationshipFragment(
    val createdAt: String,
    val target: TestNode
)

@GraphView
data class TestViewWithRelationshipFragment(
    val root: TestNode,
    @GraphRelationship(type = "RELATED_TO")
    val related: List<TestRelationshipFragment>
)