package org.drivine.annotation

/**
 * Declares a range (exact-match / b-tree) index on a [NodeFragment].
 *
 * Two placements:
 *  - **Property-level** — single-property index on the annotated property. [properties] must be
 *    left empty.
 *  - **Class-level** — composite index across [properties]. Repeatable, so a fragment can declare
 *    several composite indexes.
 *
 * ```kotlin
 * @NodeFragment(labels = ["Message"])
 * @RangeIndex(properties = ["sessionId", "createdAt"])   // composite
 * data class MessageNode(
 *     @NodeId val id: String,
 *     @RangeIndex                                         // single property
 *     val sessionId: String,
 *     val createdAt: Instant,
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
annotation class RangeIndex(
    /** Class-level (composite) use only: the properties the index covers, in order. */
    val properties: Array<String> = [],
    /** Explicit index name; empty derives one from label and properties. */
    val name: String = "",
)