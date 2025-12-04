package org.drivine.sample.view

import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.sample.fragment.Person
import org.drivine.sample.fragment.WorkHistory

/**
 * GraphView demonstrating RelationshipFragment usage.
 *
 * Shows how to capture relationship properties (startDate, role) along with
 * the target node (Organization). This is useful for modeling employment history,
 * transaction records, audit trails, etc.
 */
@GraphView
data class PersonCareer(
    @Root val person: Person,

    @GraphRelationship(type = "WORKS_FOR")
    val employmentHistory: List<WorkHistory>
)
