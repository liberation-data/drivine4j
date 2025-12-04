package org.drivine.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.GraphView

@NodeFragment(labels = ["TestNode"])
data class TestNode(
    @NodeId
    val id: String,
    val name: String
)

@RelationshipFragment
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
