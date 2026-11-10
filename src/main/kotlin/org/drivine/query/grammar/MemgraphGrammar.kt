package org.drivine.query.grammar

import org.drivine.cypher.api.SearchStore
import org.drivine.query.sort.CollectionSortEmitter
import org.drivine.schema.SimilarityFunction

/**
 * Memgraph — openCypher engine with full ACID and working nested pattern comprehensions, but no
 * orphan delete (see [supportsOrphanDelete]). Ships without APOC (uses MAGE instead).
 *
 * Memgraph's `EXISTS { pattern }` support is more restrictive than Neo4j 5's — it rejects
 * "unbounded variables" inside EXISTS and disallows EXISTS in a WITH clause. To stay compatible
 * with the widest Memgraph version range we fall back to inline pattern predicates (the
 * [OpenCypherGrammar] defaults), which Memgraph accepts everywhere. Filtered existence uses a
 * size-of-pattern-comprehension check, nested for a where on a nested view's relationships.
 */
class MemgraphGrammar(
    collectionSortEmitter: CollectionSortEmitter
) : OpenCypherGrammar(collectionSortEmitter) {
    override val nestedViewProjector: NestedViewProjector = InlineNestedViewProjector()

    /** Memgraph has no `epochMillis`; `timestamp(moment)` gives the microseconds since the epoch. */
    override fun epochSeconds(moment: String): TemporalOrderSupport = TemporalOrderSupport.Written("timestamp($moment) / 1000000.0")

    // Memgraph rejects `EXISTS` inside a `WITH` clause ("Not yet implemented: Exists cannot
    // be used within WITH!") which is what the CASCADE DELETE_ORPHAN query emits. Opt out so
    // callers get a clean UnsupportedOperationException instead of a cryptic server error.
    override val supportsOrphanDelete: Boolean = false
    override val orphanDeleteLimit: String =
        "Memgraph cannot use EXISTS inside WITH, which the check for a target's remaining relationships needs."

    /**
     * Its check is a pattern comprehension in the `WHERE`, which holds another in its own; one
     * comprehension takes a single path, so sibling relationships cannot be flattened into it.
     */
    override val nestsFilteredExistenceChecks: Boolean = true

    /**
     * Memgraph cannot execute the `CALL { WITH rootAlias ... RETURN count(x) AS _ec0 }` pattern
     * that [OpenCypherGrammar.filteredExistenceCheck] emits — CALL-with-imported-variables
     * surfaces internally as an "Exists" AST node, and Memgraph errors with
     * "Not yet implemented: Exists cannot be used within WITH!". Use a size-of-pattern-
     * comprehension check instead: standard openCypher, no subquery, no variable imports.
     */
    override fun filteredExistenceCheck(
        relationshipPattern: String,
        whereClause: String,
        uniqueId: Int
    ): FilteredExistenceResult {
        return FilteredExistenceResult(
            inlineCondition = "size([$relationshipPattern WHERE $whereClause | 1]) > 0"
        )
    }

    override val supportsVectorSearch: Boolean = true

    /**
     * `vector_search.search(index_name, limit, query_vector)` — queried by index name, yielding
     * `node`, `distance`, and `similarity`. The score is made from `distance`, rescaled to the score
     * Neo4j and the SQL stores give: for the `cos` metric the distance is `1 - cos`, so the score
     * `(1 + cos) / 2` is `1 - distance / 2`; for `l2sq` it is `d²`, so the score `1 / (1 + d²)` is
     * `1 / (1 + distance)`.
     *
     * Not from `similarity`: Memgraph gives the absolute value of `1 - distance` there, so a cosine
     * of -0.6 has the similarity of 0.6, and opposite vectors score as high as equal ones.
     */
    override fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String {
        val score = when (spec.similarity) {
            SimilarityFunction.COSINE -> "1.0 - distance / 2.0"
            SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + distance)"
        }
        return "CALL vector_search.search(${spec.indexNameArgument()}, \$${spec.topKParam}, ${spec.vectorArgument()})\n" +
            "YIELD node, distance\n" +
            "WITH ${spec.carriedPrefix()}node AS $rootAlias, $score AS $scoreAlias"
    }

    override val supportsFullTextSearch: Boolean = true

    /**
     * `text_search.search_all(index_name, query)` — Memgraph's text index (MAGE) is referenced by
     * **name** and `search_all` searches across every indexed property, as the shared module writes it.
     * The `CALL` is given the search's index limit, capped at a million: without one `text_search`
     * yields its best 1,000 hits and no more, and a limit near `Int.MAX_VALUE` takes the server down.
     */
    @Deprecated("loadMatching takes this engine's full-text head from the shared module's FullTextSearch: search with loadMatching. To be removed.")
    override fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String =
        moduleFullTextHead(SearchStore.MEMGRAPH, spec, rootAlias, scoreAlias)
}
