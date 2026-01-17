package org.drivine.annotation

/**
 * Specifies client-side sorting for a collection relationship in a GraphView.
 *
 * When applied to a relationship field, the collection will be automatically
 * sorted after deserialization based on the specified property.
 *
 * Example:
 * ```kotlin
 * @GraphView
 * data class IssueWithAssignees(
 *     @Root val issue: Issue,
 *     @GraphRelationship(type = "ASSIGNED_TO", direction = Direction.OUTGOING)
 *     @SortedBy("person.name")  // Sort by nested property
 *     val assignees: List<AssigneeWithContext>
 * )
 * ```
 *
 * Supports nested property paths using dot notation (e.g., "person.name").
 *
 * @param property The property path to sort by (e.g., "name" or "person.name")
 * @param ascending Sort direction - true for ascending (default), false for descending
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SortedBy(
    val property: String,
    val ascending: Boolean = true
)