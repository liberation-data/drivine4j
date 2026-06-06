package org.drivine.annotation

/**
 * A per-root **count** field on a `@GraphView`: how many nodes the root relates to over [type],
 * computed without materializing the collection.
 *
 * ```kotlin
 * @GraphView
 * data class ActorStats(
 *     @Root val actor: Actor,
 *     @Count("ACTED_IN") val movieCount: Long,
 * )
 * ```
 *
 * Shorthand for `@Aggregate(AggregateFunction.COUNT, type = …)`. The field should be `Long`.
 *
 * @see Aggregate
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Count(
    val type: String,
    val direction: Direction = Direction.OUTGOING,
)