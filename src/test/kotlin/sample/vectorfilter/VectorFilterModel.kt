package sample.vectorfilter

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.VectorIndex
import org.drivine.query.dsl.PropertyBagReference
import org.drivine.query.dsl.ResolvableNodeReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.schema.SimilarityFunction

/**
 * A `@VectorIndex` fragment mixing a promoted `@GraphProperty` field (`sectionId` → `section_id`) with
 * free-form `@PropertyBag(prefix = "metadata")` — the shape a consumer runs *filtered* vector search
 * over. Used to verify `loadNearest(class, Q, vector, topK, threshold) { where { } }` accepts a bare
 * `@NodeFragment` (the vector mirror of filtered `loadMatching`).
 */
@NodeFragment(labels = ["VecDoc"])
data class VecDocNode(
    @NodeId val id: String,
    @GraphProperty("section_id") val sectionId: String? = null,
    @VectorIndex(similarity = SimilarityFunction.COSINE) val embedding: List<Float>? = null,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
)

/** Hand-written DSL mirroring the codegen shape (test source isn't KSP-processed). */
class VecDocNodeQueryDsl : ResolvableNodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    val sectionId = StringPropertyReference("n", "section_id")
    val metadata = PropertyBagReference("n", "metadata.")

    override val fieldKeyPaths: Map<String, String> = mapOf(
        "id" to "id",
        "sectionId" to "section_id",
        "section_id" to "section_id",
    )
    override val bagPrefixes: List<String> = listOf("metadata.")

    companion object {
        val INSTANCE = VecDocNodeQueryDsl()
    }
}

/**
 * A sealed `@VectorIndex` fragment: the embedding is declared on the base (a PROPERTY-target
 * `@VectorIndex`, so it resolves from the base type), and subtypes carry a concrete label. Exercises
 * polymorphic per-node dispatch through the *filtered* fragment vector path.
 */
@NodeFragment(labels = ["VecContent"])
sealed interface VecContentNode {
    @get:NodeId val id: String

    @VectorIndex(similarity = SimilarityFunction.COSINE)
    val embedding: List<Float>?
}

@NodeFragment(labels = ["VecContent", "VecChunk"])
data class VecChunkNode(
    override val id: String,
    override val embedding: List<Float>? = null,
    val chunkText: String = "",
) : VecContentNode

@NodeFragment(labels = ["VecContent", "VecImage"])
data class VecImageNode(
    override val id: String,
    override val embedding: List<Float>? = null,
    val caption: String = "",
) : VecContentNode

/** Hand DSL for the sealed base (alias `n`); no @GraphProperty/@PropertyBag, so an empty resolver. */
class VecContentNodeQueryDsl : ResolvableNodeReference {
    override val nodeAlias: String = "n"
    val id = StringPropertyReference("n", "id")
    override val fieldKeyPaths: Map<String, String> = mapOf("id" to "id")
    override val bagPrefixes: List<String> = emptyList()

    companion object {
        val INSTANCE = VecContentNodeQueryDsl()
    }
}
