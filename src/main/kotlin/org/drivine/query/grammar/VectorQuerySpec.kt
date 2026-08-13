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
 * @param indexNameParam bound parameter carrying [indexName], or null to emit it as a literal.
 * @param labelParam bound parameter carrying [label], or null to emit it as a literal.
 *
 *   Both are set together, and only when the caller targeted a partition label at runtime. Binding
 *   rather than interpolating matters twice over there: a literal produces a **distinct query string
 *   per partition**, so the plan cache holds one plan per corpus instead of one in total, and a label
 *   derived from application data would otherwise be concatenated straight into Cypher. Left null for
 *   the ordinary case, where the label is a compile-time constant from the fragment and the emitted
 *   text is unchanged.
 */
data class VectorQuerySpec(
    val label: String,
    val property: String,
    val indexName: String,
    val similarity: SimilarityFunction,
    val topKParam: String,
    val vectorParam: String,
    val rowLimitParam: String? = null,
    val indexNameParam: String? = null,
    val labelParam: String? = null,
) {

    /** The index-name argument as this query should spell it: a bound parameter, or a quoted literal. */
    fun indexNameArgument(): String = indexNameParam?.let { "\$$it" } ?: "'$indexName'"

    /** The label argument as this query should spell it: a bound parameter, or a quoted literal. */
    fun labelArgument(): String = labelParam?.let { "\$$it" } ?: "'$label'"
}