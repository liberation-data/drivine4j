package org.drivine.schema

/**
 * A vector (approximate nearest-neighbour) index over a single embedding property.
 *
 * ### Pinning the physical index
 *
 * Only [dimensions] and [similarity] are always emitted. Everything else about the physical index —
 * the HNSW graph shape, quantization — is left to the engine unless pinned, and engine defaults change
 * between versions: Neo4j 2026.04 creates with `vector.quantization.enabled: true`, `hnsw.m: 16`,
 * `ef_construction: 100`, which are not the defaults earlier versions used. The same declaration
 * therefore produces different physical indexes on different servers, silently, and the difference
 * surfaces only as a recall or latency change nobody can attribute.
 *
 * Pinning comes in two layers. [hnswM] and [hnswEfConstruction] are portable — HNSW is the shared
 * algorithm, so they mean the same thing on every engine that has one, and they are the only tuning
 * [org.drivine.annotation.VectorIndex] can express. Anything engine-specific goes in [engineOptions],
 * which only a hand-written [SchemaCatalog] spec can supply. Where both are given, the engine-specific
 * value wins on that engine and the portable one still applies elsewhere.
 *
 * Leaving a parameter unpinned means "whatever the engine chooses" and is never drift — there is no
 * declared value for the database to differ from. See [SchemaGrammar.matchesShape].
 *
 * @param label node label, e.g. `"Proposition"`
 * @param property the embedding property, e.g. `"embedding"`
 * @param dimensions dimensionality of the embedding vectors (typically from the embedding model)
 * @param similarity similarity function used for search
 * @param name explicit index name; null derives `"${label}_${property}_vector"`
 * @param hnswM portable pin for the neighbours per node in the HNSW graph; null leaves it to the engine
 * @param hnswEfConstruction portable pin for the candidate-list size used while building the graph;
 *   null leaves it to the engine
 * @param engineOptions engine-specific options, at most one entry per engine
 */
data class VectorIndexSpec(
    override val label: String,
    val property: String,
    val dimensions: Int,
    val similarity: SimilarityFunction = SimilarityFunction.COSINE,
    override val name: String? = null,
    val hnswM: Int? = null,
    val hnswEfConstruction: Int? = null,
    val engineOptions: List<EngineVectorOptions> = emptyList(),
) : IndexSpec {

    init {
        require(dimensions > 0) { "Vector index dimensions must be positive, got $dimensions" }
        requireNameNotWhitespace(name, "VectorIndexSpec")
        requirePositiveOrNull(hnswM, "VectorIndexSpec.hnswM")
        requirePositiveOrNull(hnswEfConstruction, "VectorIndexSpec.hnswEfConstruction")
        val engines = engineOptions.map { it.engine }
        require(engines.size == engines.distinct().size) {
            "VectorIndexSpec declares more than one set of options for the same engine: $engines"
        }
    }

    override val properties: List<String>
        get() = listOf(property)

    override val kind: SchemaItemKind
        get() = SchemaItemKind.VECTOR_INDEX

    override fun defaultName(): String = "${label}_${property}_vector"

    /** This spec's options for [engine], or null if it declares none. */
    fun optionsFor(engine: String): EngineVectorOptions? = engineOptions.firstOrNull { it.engine == engine }

    /**
     * The portable parameters that apply on [engine], with any engine-specific override resolved.
     *
     * Engine-specific parameters that have no portable equivalent (Neo4j's quantization, FalkorDB's
     * `efRuntime`) are not here — the grammar for that engine reads its own options class directly,
     * which is what keeps engine vocabulary out of the shared layer.
     */
    fun tuningFor(engine: String): VectorTuning {
        val options = optionsFor(engine)
        return VectorTuning(
            hnswM = options?.hnswM ?: hnswM,
            hnswEfConstruction = options?.hnswEfConstruction ?: hnswEfConstruction,
        )
    }

    /**
     * This spec with engine options removed, i.e. its portable declaration.
     *
     * [SchemaCatalog] compares specs in this form so that a fragment-scanned declaration and a tuned
     * catalog declaration of the same index are recognised as the same item rather than a conflict.
     */
    fun withoutEngineOptions(): VectorIndexSpec = copy(engineOptions = emptyList())

    /** Whether this spec pins anything beyond dimensions and similarity, on any engine. */
    val pinsPhysicalConfig: Boolean
        get() = hnswM != null || hnswEfConstruction != null || engineOptions.isNotEmpty()
}
