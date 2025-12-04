package org.drivine.annotation

/**
 * Marks a property in a @GraphView as the root fragment.
 *
 * A GraphView must have exactly one @Root property that points to a @NodeFragment.
 * This is the primary node that the view is based on, with relationships extending from it.
 *
 * Example:
 * ```kotlin
 * @GraphView
 * data class PersonContext(
 *     @Root val person: Person,
 *     @GraphRelationship(type = "WORKS_FOR")
 *     val worksFor: List<Organization>
 * )
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Root