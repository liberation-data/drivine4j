package org.drivine.annotation

/**
 * Declares Neo4j's TEXT index on the annotated string property of a [NodeFragment], for
 * `CONTAINS`, `STARTS WITH` and `ENDS WITH` lookups on it.
 *
 * Single-property only, as Neo4j's TEXT index is. For tokenized search over one or more properties
 * use [FullTextIndex], which is also what Memgraph's own `TEXT INDEX` is. Only Neo4j has this
 * index: on any other engine, applying the schema fails rather than make another index in its
 * place (see [org.drivine.schema.TextIndexSpec]).
 *
 * ```kotlin
 * @NodeFragment(labels = ["Chunk"])
 * data class ChunkNode(
 *     @NodeId val id: String,
 *     @TextIndex
 *     val title: String,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class TextIndex(
    /** Explicit index name; empty derives one from label and property. */
    val name: String = "",
    /**
     * Whether the index is over the lowercased copy a `@CaseInsensitive` field keeps (see
     * [CaseInsensitive]), so that it serves `containsIgnoreCase`.
     */
    val ignoreCase: Boolean = false,
)
