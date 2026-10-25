package sample.multilabel

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * Fragments with more than one label, whose search indexes are on the first: a `:Paper` that is not
 * `:Published` is in the index, and is no [PublishedPaper].
 */
@NodeFragment(labels = ["Paper", "Published"])
data class PublishedPaper(
    @NodeId val id: String,
    @FullTextIndex val body: String,
)

@GraphView
data class PublishedPaperView(@Root val paper: PublishedPaper)

@NodeFragment(labels = ["Clip", "Published"])
data class PublishedClip(
    @NodeId val id: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
)

@GraphView
data class PublishedClipView(@Root val clip: PublishedClip)
