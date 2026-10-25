package sample.multilabel

import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference
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

/** [PublishedPaper]'s query DSL, written by hand: the test source set isn't KSP-processed. */
class PublishedPaperQueryDsl : NodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")

    companion object {
        val INSTANCE = PublishedPaperQueryDsl()
    }
}

@GraphView
data class PublishedPaperView(@Root val paper: PublishedPaper)

@NodeFragment(labels = ["Clip", "Published"])
data class PublishedClip(
    @NodeId val id: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
)

@GraphView
data class PublishedClipView(@Root val clip: PublishedClip)

/**
 * A fragment whose second label is inherited: it persists as `:Essay:Writing`, and its full-text
 * index is on `Essay`, so an `:Essay` that is not `:Writing` is in the index and is no [Essay].
 */
@NodeFragment(labels = ["Writing"])
interface Writing

@NodeFragment(labels = ["Essay"])
data class Essay(
    @NodeId val id: String,
    @FullTextIndex val body: String,
) : Writing

/** A fragment with more than one label searched through a partition's index, on a label it does not declare. */
@NodeFragment(labels = ["Note", "Published"])
data class PublishedNote(
    @NodeId val id: String,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
)
