package org.drivine.sample.fragment

import org.drivine.annotation.RelationshipFragment
import java.time.LocalDate

/**
 * RelationshipFragment example - models properties on the WORKS_FOR relationship edge.
 *
 * This demonstrates how to capture metadata about relationships, not just nodes.
 * In this case: when someone started working, their role, and what organization.
 */
@RelationshipFragment
data class WorkHistory(
    val startDate: LocalDate,
    val role: String,
    val target: Organization
)
