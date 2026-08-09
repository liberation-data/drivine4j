package org.drivine.annotation

/**
 * Declares a full-text (tokenized) index on a [NodeFragment].
 *
 * Two placements, mirroring [RangeIndex]:
 *  - **Property-level** — single-property fulltext index on the annotated property. [properties]
 *    must be left empty.
 *  - **Class-level** — multi-property fulltext index across [properties]. Repeatable, so a fragment
 *    can declare several fulltext indexes.
 *
 * ```kotlin
 * @NodeFragment(labels = ["Chunk"])
 * @FullTextIndex(properties = ["title", "body"])   // multi-property
 * data class ChunkNode(
 *     @NodeId val id: String,
 *     @FullTextIndex                                // single property
 *     val summary: String,
 *     val title: String,
 *     val body: String,
 * )
 * ```
 *
 * @param properties class-level (multi-property) use only: the properties the index covers, in order.
 * @param name explicit index name; empty derives one from label and properties. Honoured on Neo4j
 *   and Memgraph; ignored on FalkorDB (unnamed indexes).
 * @param analyzer engine analyzer/tokenizer name; empty applies the engine default. Only Neo4j both
 *   accepts and reports one back, so it is only emitted and drift-checked there.
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.CLASS,
)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class FullTextIndex(
    /** Class-level (multi-property) use only: the properties the index covers, in order. */
    val properties: Array<String> = [],
    /** Explicit index name; empty derives one from label and properties. */
    val name: String = "",
    /** Engine analyzer/tokenizer name; empty applies the engine default (Neo4j only). */
    val analyzer: String = "",
)