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
 * @param topKParam the bound parameter name carrying the requested K (applied as a trailing `LIMIT`,
 *   because the full-text `CALL` — unlike vector — takes no `k` argument)
 */
data class FullTextQuerySpec(
    val label: String,
    val indexName: String,
    val queryParam: String,
    val topKParam: String,
)