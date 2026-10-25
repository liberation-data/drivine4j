package org.drivine.sample.view

import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.sample.fragment.Organization

/** An organization and the units it runs, each an OrgChart of its own: a view that holds itself. */
@GraphView
data class OrgChart(
    @Root val organization: Organization,
    @GraphRelationship(type = "RUNS", maxDepth = 3)
    val units: List<OrgChart>,
)
