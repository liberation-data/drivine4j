package org.drivine.query.grammar

/**
 * The resolved inputs a [CypherGrammar] needs to emit a full-text search head. Pure data —
 * engine-specific syntax lives in each grammar's [CypherGrammar.fullTextSearchHead].
 *
 * Carries *both* the index name (Neo4j / Memgraph query by name) and the label (FalkorDB queries
 * by label — it has no index names), so every grammar can pick what it needs — the same
 * name-vs-label split [VectorQuerySpec] carries for vector search.
 *
 * @param label the node label the full-text index is declared on (the root fragment's primary label)
 * @param indexName the resolved index name (explicit, or `${label}_${properties joined by _}_fulltext`)
 * @param queryParam the bound parameter name carrying the query string (e.g. `"_fullTextQuery"`)
 * @param topKParam the bound parameter name carrying the requested K, the trailing `LIMIT` on the
 *   rows that survive the filters
 * @param properties the properties the index covers, by the names they are stored under: what a
 *   store with no index of its own to call searches
 * @param indexLimitParam the bound parameter name carrying how many hits the index call may yield.
 *   Every hit the call yields is collected to normalize the scores and carried by each row after it,
 *   so this — not [topKParam] — is what bounds a search's memory; see
 *   [CypherGrammar.normalizedFullTextHead]
 */
data class FullTextQuerySpec(
    val label: String,
    val indexName: String,
    val queryParam: String,
    val topKParam: String,
    val properties: List<String> = emptyList(),
    val indexLimitParam: String = topKParam,
)