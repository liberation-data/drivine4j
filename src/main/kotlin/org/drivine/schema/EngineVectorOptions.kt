package org.drivine.schema

/**
 * Engine-specific physical options for a vector index.
 *
 * These exist because the portable layer cannot express everything: quantization is a Neo4j concept,
 * `efRuntime` a FalkorDB one, and pretending otherwise would either invent a lowest common denominator
 * or leak one engine's vocabulary into every other. They are declared only in a hand-written
 * [SchemaCatalog] spec — annotation attributes cannot carry them, and [org.drivine.annotation.VectorIndex]
 * deliberately stays portable.
 *
 * Note the deliberate limit on how far these travel. They are **typed data, never DDL fragments**: only
 * the grammar for the matching [engine] reads its own options class, so [SchemaItemSpec]'s rule that
 * specs carry no engine syntax still holds — a spec says *what* is wanted, the grammar decides how to
 * spell it. An engine that receives options it cannot express reports them via
 * [SchemaGrammar.unsupportedVectorTuning] rather than dropping them silently.
 *
 * Where an option duplicates a portable one ([hnswM], [hnswEfConstruction]), the engine-specific value
 * wins for that engine and the portable value still applies everywhere else — so a single declaration
 * can say "m = 16 generally, but 32 on Neo4j".
 */
sealed interface EngineVectorOptions {

    /** The [SchemaGrammar.engine] these options apply to. Matched exactly. */
    val engine: String

    /** Overrides [VectorIndexSpec.hnswM] on this engine; null defers to the portable value. */
    val hnswM: Int?

    /** Overrides [VectorIndexSpec.hnswEfConstruction] on this engine; null defers to the portable value. */
    val hnswEfConstruction: Int?
}

/**
 * Neo4j-specific vector index options.
 *
 * @param quantizationEnabled pins `vector.quantization.enabled`. Neo4j 2026.04 defaults this to true
 *   where earlier versions did not, and quantization trades recall for memory, so leaving it unpinned
 *   means the same declaration can produce measurably different search quality per server version.
 */
data class Neo4jVectorOptions(
    val quantizationEnabled: Boolean? = null,
    override val hnswM: Int? = null,
    override val hnswEfConstruction: Int? = null,
) : EngineVectorOptions {

    init {
        requirePositiveOrNull(hnswM, "Neo4jVectorOptions.hnswM")
        requirePositiveOrNull(hnswEfConstruction, "Neo4jVectorOptions.hnswEfConstruction")
    }

    override val engine: String get() = ENGINE

    companion object {
        const val ENGINE = "Neo4j"
    }
}

/**
 * FalkorDB-specific vector index options.
 *
 * @param efRuntime pins the search-time candidate list size. Unlike the build-time parameters this
 *   affects every query rather than the stored index, and FalkorDB is the only supported engine that
 *   accepts it as index configuration.
 */
data class FalkorDbVectorOptions(
    override val hnswM: Int? = null,
    override val hnswEfConstruction: Int? = null,
    val efRuntime: Int? = null,
) : EngineVectorOptions {

    init {
        requirePositiveOrNull(hnswM, "FalkorDbVectorOptions.hnswM")
        requirePositiveOrNull(hnswEfConstruction, "FalkorDbVectorOptions.hnswEfConstruction")
        requirePositiveOrNull(efRuntime, "FalkorDbVectorOptions.efRuntime")
    }

    override val engine: String get() = ENGINE

    companion object {
        const val ENGINE = "FalkorDB"
    }
}

/** The physical vector parameters that apply on one engine, after resolving overrides. */
data class VectorTuning(
    val hnswM: Int? = null,
    val hnswEfConstruction: Int? = null,
) {
    /** Whether anything is pinned at all; an unpinned tuning leaves every choice to the engine. */
    val isEmpty: Boolean get() = hnswM == null && hnswEfConstruction == null
}

internal fun requirePositiveOrNull(value: Int?, what: String) {
    require(value == null || value > 0) { "$what must be positive, got $value" }
}
