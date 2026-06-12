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
 * @param topKParam the bound parameter name carrying the requested K (e.g. `"topK"`)
 * @param vectorParam the bound parameter name carrying the query embedding (e.g. `"queryVector"`)
 */
data class VectorQuerySpec(
    val label: String,
    val property: String,
    val indexName: String,
    val similarity: SimilarityFunction,
    val topKParam: String,
    val vectorParam: String,
)