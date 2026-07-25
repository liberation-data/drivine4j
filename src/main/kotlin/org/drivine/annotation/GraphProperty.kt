package org.drivine.annotation

/**
 * Overrides the **on-disk node-property name** a [NodeFragment] field is persisted to, while the
 * Kotlin field name stays the identity used everywhere in code, the query DSL, result mapping, and
 * session/dirty tracking.
 *
 * The OGM equivalent of JPA's `@Column(name = …)` / Spring Data Neo4j's `@Property`. Use it to bind
 * an idiomatic camelCase field to a property that exists on-disk under a different name (typically
 * snake_case) without a data migration:
 *
 * ```kotlin
 * @NodeFragment(labels = ["ContentElement", "Chunk"])
 * data class ChunkNode(
 *     @NodeId val id: String,
 *     val text: String,
 *     @GraphProperty("container_section_id") val containerSectionId: String?,
 *     @GraphProperty("sequence_number")      val sequenceNumber: Long?,
 * )
 * ```
 *
 * `containerSectionId` reads and writes the node property `container_section_id`; `where { … }`
 * filters on `container_section_id`; a `@VectorIndex` / `@RangeIndex` / `@Unique` on the field indexes
 * `container_section_id`. Everything referenced by the field name in Kotlin is unchanged.
 *
 * Applicable to `@NodeId` too — the override then flows into both the MERGE key and the load `WHERE`.
 *
 * **Not combinable with [PropertyBag]** on the same field (a bag manages its own prefixed property
 * names); that combination is rejected at model build. Two fields mapping to the same on-disk name —
 * whether via `@GraphProperty` or a collision with another field's default name — also fails fast at
 * model build, naming both fields.
 *
 * @param value the node-property name to read from and write to.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphProperty(val value: String)
