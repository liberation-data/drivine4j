package org.drivine.annotation

/**
 * Marks a collection property to be stored as a JSON string in the graph database.
 *
 * Useful for engines that don't support list/array property values (e.g., Neptune).
 * On engines that do support lists (Neo4j, FalkorDB), the property is still stored
 * as a JSON string — portable but loses native list indexing.
 *
 * Example:
 * ```kotlin
 * @RelationshipFragment
 * data class WorkHistory(
 *     val role: String,
 *     @JsonPacked val tags: List<String>? = null,
 *     val target: Organization
 * )
 * ```
 *
 * On write: `["backend", "senior"]` → stored as string `'["backend","senior"]'`
 * On read: string `'["backend","senior"]'` → deserialized to `List<String>`
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonPacked