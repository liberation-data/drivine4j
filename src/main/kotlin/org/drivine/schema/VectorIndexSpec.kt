package org.drivine.schema

/**
 * A vector (approximate nearest-neighbour) index over a single embedding property.
 *
 * @param label node label, e.g. `"Proposition"`
 * @param property the embedding property, e.g. `"embedding"`
 * @param dimensions dimensionality of the embedding vectors (typically from the embedding model)
 * @param similarity similarity function used for search
 * @param name explicit index name; null derives `"${label}_${property}_vector"`
 */
data class VectorIndexSpec(
    override val label: String,
    val property: String,
    val dimensions: Int,
    val similarity: SimilarityFunction = SimilarityFunction.COSINE,
    override val name: String? = null,
) : IndexSpec {

    init {
        require(dimensions > 0) { "Vector index dimensions must be positive, got $dimensions" }
    }

    override val properties: List<String>
        get() = listOf(property)

    override val kind: SchemaItemKind
        get() = SchemaItemKind.VECTOR_INDEX

    override fun defaultName(): String = "${label}_${property}_vector"
}