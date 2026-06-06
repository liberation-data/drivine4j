package org.drivine.annotation

/**
 * A per-root **aggregate** field on a `@GraphView` — a scalar summary (count/sum/avg/min/max) over
 * the nodes the root relates to via [type], computed in the load query without materializing the
 * collection in the application.
 *
 * ```kotlin
 * @GraphView
 * data class ActorStats(
 *     @Root val actor: Actor,
 *     @Aggregate(AggregateFunction.AVG, type = "RATED", property = "score") val avgRating: Double,
 *     @Count("ACTED_IN") val movieCount: Long,   // COUNT shorthand
 * )
 * ```
 *
 * [property] is required for SUM/AVG/MIN/MAX (the numeric property of the related node to aggregate)
 * and ignored for COUNT. Aggregates are single-hop only; for multi-hop use [GraphPath] then count
 * in the application.
 *
 * @see Count
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Aggregate(
    val function: AggregateFunction,
    val type: String,
    val property: String = "",
    val direction: Direction = Direction.OUTGOING,
)