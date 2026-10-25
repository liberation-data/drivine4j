package sample.vector

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/** A fragment with a cosine and a Euclidean embedding, to pin the score each store gives a known pair. */
@NodeFragment(labels = ["ScoredPair"])
data class ScoredPairNode(
    @NodeId val id: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val cosine: List<Float>? = null,
    @VectorIndex(similarity = SimilarityFunction.EUCLIDEAN)
    val euclidean: List<Float>? = null,
)
