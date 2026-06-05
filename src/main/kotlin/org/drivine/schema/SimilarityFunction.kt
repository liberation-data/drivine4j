package org.drivine.schema

/**
 * Similarity function for vector indexes.
 *
 * Each engine uses its own vocabulary for these (Neo4j: `cosine` / `euclidean`,
 * Memgraph: `cos` / `l2sq`, FalkorDB: `cosine` / `euclidean`). The per-engine
 * [SchemaGrammar] normalizes to and from this enum.
 */
enum class SimilarityFunction {
    COSINE,
    EUCLIDEAN,
}