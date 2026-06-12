package sample.vector

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/** A fragment with two embeddings — exercises the disambiguation / ambiguity-error paths. */
@NodeFragment(labels = ["DualDoc"])
data class DualEmbeddingNode(
    @NodeId val id: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val titleEmbedding: List<Float>? = null,
    @VectorIndex(similarity = SimilarityFunction.EUCLIDEAN)
    val bodyEmbedding: List<Float>? = null,
)