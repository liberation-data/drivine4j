package org.drivine.annotation

/**
 * Marks a Kotlin/Java property so Drivine excludes it from graph
 * mapping. Use on computed/derived getters, lazy caches, transient
 * collaborators, or any other class member that lives on a
 * `@NodeFragment` for in-memory ergonomics but should NOT appear as
 * a Neo4j node property on save.
 *
 * **Why not just `@JsonIgnore`.** `@JsonIgnore` is Jackson's
 * serialization concern — a downstream API serializer may legitimately
 * want to ignore-or-include a property independent of whether it's
 * persisted to the graph. The two concerns can pull in opposite
 * directions (a lazy cache that you want in the JSON API response
 * but not in Neo4j; a relationship target you want in Neo4j but not
 * in JSON). Domain-specific `@DrivineIgnore` lets callers be explicit
 * about which concern is which.
 *
 * Applies to getters by default — that's where computed properties
 * live in Kotlin. Also valid on backing fields for the `private var
 * cached*` cache pattern.
 *
 * Example:
 * ```kotlin
 * @NodeFragment(labels = ["EmailSignal"])
 * class EmailSignal(
 *     @NodeId var nodeId: String = "",
 *     var threadJson: String = "{}",
 * ) {
 *     @get:DrivineIgnore
 *     val thread: EmailThread by lazy { mapper.readValue(threadJson, EmailThread::class.java) }
 *
 *     @field:DrivineIgnore
 *     private var cachedThread: EmailThread? = null
 * }
 * ```
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class DrivineIgnore
