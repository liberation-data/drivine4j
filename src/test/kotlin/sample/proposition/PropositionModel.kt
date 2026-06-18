package sample.proposition

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.schema.SimilarityFunction

/**
 * The motivating model for the 0.0.48 query features: a proposition store with a vector-indexed
 * embedding and a to-many `HAS_MENTION` relationship. Used by the quantified-predicate tests
 * (Feature 1) and the filtered vector-search tests (Feature 2).
 */
@NodeFragment(labels = ["Proposition"])
data class PropositionNode(
    @NodeId val id: String,
    val contextId: String,
    val status: String,
    val level: Int,
    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>? = null,
    /**
     * Source chunk ids this proposition is grounded in — a list-valued property, for `hasItem`.
     * Nullable because drivine's deserialization fills a missing node property with null rather than
     * the Kotlin default, and most fixtures' seeds don't set `grounding`.
     */
    val grounding: List<String>? = null,
)

@NodeFragment(labels = ["Mention"])
data class Mention(
    @NodeId val id: String,
    val resolvedId: String?,
    val role: String,
)

@GraphView
data class PropositionView(
    @Root val proposition: PropositionNode,
    @GraphRelationship(type = "HAS_MENTION", direction = Direction.OUTGOING)
    val mentions: List<Mention> = emptyList(),
)