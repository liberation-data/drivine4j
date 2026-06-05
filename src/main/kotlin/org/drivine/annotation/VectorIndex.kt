package org.drivine.annotation

import org.drivine.schema.SimilarityFunction

/**
 * Declares a vector index on the annotated embedding property of a [NodeFragment].
 *
 * Dimensions are not part of the annotation because they come from the embedding model at
 * runtime — they are resolved through a [org.drivine.schema.VectorDimensionProvider] when the
 * fragment is scanned into a [org.drivine.schema.SchemaCatalog].
 *
 * ```kotlin
 * @NodeFragment(labels = ["Proposition"])
 * data class PropositionNode(
 *     @NodeId val id: String,
 *     val text: String,
 *     @VectorIndex(similarity = SimilarityFunction.COSINE)
 *     val embedding: List<Float>?,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class VectorIndex(
    val similarity: SimilarityFunction = SimilarityFunction.COSINE,
    /** Explicit index name; empty derives one from label and property. */
    val name: String = "",
)