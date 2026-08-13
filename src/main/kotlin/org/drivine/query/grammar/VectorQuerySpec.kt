package org.drivine.query.grammar

import org.drivine.schema.SimilarityFunction

/**
 * The resolved inputs a [CypherGrammar] needs to emit a vector (approximate nearest-neighbour)
 * search head. Pure data — engine-specific syntax lives in each grammar's [CypherGrammar.vectorSearchHead].
 *
 * Carries *both* the index name (Neo4j / Memgraph query by name) and the label + property
 * (FalkorDB queries by label + property), so every grammar can pick what it needs.
 *
 * @param label the node label the vector index is declared on (the root fragment's primary label)
 * @param property the embedding property the index covers
 * @param indexName the resolved index name (explicit, or `${label}_${property}_vector`)
 * @param similarity the similarity function the index was built with
 * @param topKParam the bound parameter name carrying the K handed to the **index**.
 *
 *   This is the HNSW search beam width, not just a row count: in Lucene-backed engines the result
 *   queue *is* the candidate queue, so a small K explores a small part of the graph and can miss
 *   vectors that are genuinely nearest. Raising it is the only lever on recall at query time.
 * @param vectorParam the bound parameter name carrying the query embedding (e.g. `"queryVector"`)
 * @param rowLimitParam the bound parameter name for a trailing `LIMIT`, or null for no limit.
 *
 *   Set only when the caller asked for a wider search than the number of rows they want back
 *   ([topKParam] > rows requested). Null keeps the emitted query byte-identical to what an untuned
 *   search has always produced.
 */
data class VectorQuerySpec(
    val label: String,
    val property: String,
    val indexName: String,
    val similarity: SimilarityFunction,
    val topKParam: String,
    val vectorParam: String,
    val rowLimitParam: String? = null,
)