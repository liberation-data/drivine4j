package org.drivine.annotation

/**
 * Marks a class as a relationship fragment - an object representing a relationship
 * with properties plus a target node.
 *
 * This pattern allows relationships to carry additional properties beyond just
 * connecting two nodes.
 *
 * Example:
 * ```kotlin
 * @GraphRelationshipFragment
 * data class Assignment(
 *     val assignedAt: Instant,
 *     val priority: String,
 *     val target: Person  // The Person being assigned
 * )
 * ```
 *
 * Usage in a GraphView:
 * ```kotlin
 * @GraphView
 * data class IssueWithAssignments(
 *     val issue: Issue,
 *     @GraphRelationship(type = "ASSIGNED_TO")
 *     val assignedTo: List<Assignment>  // Rich relationship objects
 * )
 * ```
 *
 * This is in contrast to the simple pattern which directly references the target:
 * ```kotlin
 * @GraphRelationship(type = "ASSIGNED_TO")
 * val assignedTo: List<Person>  // Simple direct reference
 * ```
 *
 * @see GraphRelationship
 * @see GraphFragment
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphRelationshipFragment