package org.drivine.annotation

/**
 * Maps a `@GraphView` field to a node reached by a multi-hop **path**, skipping the intermediate
 * nodes. Where [GraphRelationship] is a single hop, `@GraphPath` traverses several and projects only
 * the final node — the element type of the annotated field.
 *
 * ```kotlin
 * @GraphView
 * data class ActorDirectors(
 *     @Root val actor: Actor,
 *     @GraphPath([
 *         Hop("ACTED_IN",    Direction.OUTGOING, label = "Movie"),  // through Movie — not mapped
 *         Hop("DIRECTED_BY", Direction.OUTGOING),                   // to Director
 *     ])
 *     val directors: List<Director>,   // List → collection; Director? → single; Director → required
 * )
 * ```
 *
 * Targets are **de-duplicated** — an actor who made two movies by the same director yields that
 * director once. Field cardinality mirrors [GraphRelationship]: `List<T>` is a collection, `T?` a
 * single optional, `T` a required single (roots lacking the path are filtered out).
 *
 * The targets come in no set order unless one hop names one with [Hop.orderBy]: a list then holds
 * them in the order of the first node of that hop that leads to each, and a single field holds the
 * target the first such node leads to. A path inside a view that is itself nested in another view
 * is not read as a path on the Cypher engines; declare it on the view that is loaded.
 *
 * Unlike a recursive [GraphRelationship], a path is a fixed, heterogeneous hop list — `maxDepth`
 * does not apply.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphPath(
    val hops: Array<Hop>,
)