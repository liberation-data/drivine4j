package org.drivine.annotation

/**
 * Declares a uniqueness constraint on a [NodeFragment].
 *
 * Two placements:
 *  - **Property-level** — single-property constraint on the annotated property. [properties] must
 *    be left empty.
 *  - **Class-level** — composite constraint across [properties]. Repeatable, so a fragment can
 *    declare several composite constraints.
 *
 * ```kotlin
 * @NodeFragment(labels = ["Membership"])
 * @Unique(properties = ["tenantId", "userId"])   // composite
 * data class MembershipNode(
 *     @NodeId @Unique                             // single property
 *     val id: String,
 *     val tenantId: String,
 *     val userId: String,
 * )
 * ```
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Unique(
    /** Class-level (composite) use only: the properties the constraint covers, in order. */
    val properties: Array<String> = [],
    /** Explicit constraint name; empty derives one from label and properties. */
    val name: String = "",
)