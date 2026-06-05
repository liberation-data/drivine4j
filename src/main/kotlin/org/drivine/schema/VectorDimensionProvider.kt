package org.drivine.schema

/**
 * Resolves vector index dimensionality at runtime.
 *
 * Vector dimensions cannot be declared in a [org.drivine.annotation.VectorIndex] annotation
 * because they come from the embedding model in use. Consumers supply a provider when scanning
 * fragments into a [SchemaCatalog] — typically backed by their embedding service:
 *
 * ```kotlin
 * @Bean
 * fun schemaCatalog(embeddingService: EmbeddingService) = SchemaCatalog.fromFragments(
 *     VectorDimensionProvider { _, _ -> embeddingService.dimensions },
 *     PropositionNode::class,
 *     MentionNode::class,
 * )
 * ```
 */
fun interface VectorDimensionProvider {

    /** Returns the embedding dimensionality for the vector index on (label, property). */
    fun dimensionsFor(label: String, property: String): Int

    companion object {

        /** A provider that returns the same fixed dimensionality for every vector index. */
        @JvmStatic
        fun fixed(dimensions: Int): VectorDimensionProvider =
            VectorDimensionProvider { _, _ -> dimensions }
    }
}