package org.drivine.annotation

import org.drivine.schema.SimilarityFunction

/**
 * Declares a vector index on the annotated embedding property of a [NodeFragment].
 *
 * Dimensions are not part of the annotation because they come from the embedding model at
 * runtime — they are resolved through a [org.drivine.schema.VectorDimensionProvider] when the
 * fragment is scanned into a [org.drivine.schema.SchemaCatalog].
 *
 * Tuning here is deliberately limited to what is portable across engines. Engine-specific options
 * (Neo4j quantization, FalkorDB `efRuntime`) go in a hand-written [org.drivine.schema.SchemaCatalog]
 * spec via [org.drivine.schema.EngineVectorOptions]; declaring the same index in both places is
 * expected and supported — the tuned declaration wins. Keep the annotation in place regardless, since
 * [org.drivine.query.VectorIndexResolver] resolves vector queries from it.
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
    /**
     * Pins the neighbours per node in the HNSW graph. Any value < 1 means "engine default" —
     * annotation attributes cannot be null, so 0 is the unset sentinel.
     */
    val hnswM: Int = 0,
    /**
     * Pins the candidate-list size used while building the HNSW graph. Any value < 1 means "engine
     * default", as for [hnswM].
     */
    val hnswEfConstruction: Int = 0,
)