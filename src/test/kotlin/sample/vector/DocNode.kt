package sample.vector

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * A document fragment with a single cosine-similarity embedding — the common `loadNearest`
 * case where the vector index is inferred (no property argument needed).
 */
@NodeFragment(labels = ["Doc"])
data class DocNode(
    @NodeId val id: String,
    val title: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
)