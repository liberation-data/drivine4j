package org.drivine.query

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.VectorQuerySpec

/**
 * Generates a Cypher **vector** (approximate nearest-neighbour) search over a single `@GraphView`.
 *
 * The grammar's vector `CALL` head replaces the `MATCH` (the index *produces* the root nodes),
 * the normalized similarity is threaded through as `_score`, and the same projection + required-
 * relationship `EXISTS` checks the normal load uses (via the shared [GraphViewProjectionAssembler])
 * are layered on top. Results are wrapped as `{ value: <view>, score: _score } AS row` and ordered
 * by similarity, highest first.
 *
 * Filtering is **post-search**: required relationships (and the optional threshold) prune the K
 * candidates the index returned, so the query can yield fewer than K rows. That is the deliberate
 * semantics — `topK` is the index's `k`, not a guaranteed result count.
 *
 * This builder owns only the vector-specific composition (the `CALL` head, the score-carrying
 * prolog wiring, and the scored RETURN); everything else is shared with [GraphViewLoadBuilder].
 */
internal class GraphViewVectorSearchBuilder(
    viewModel: GraphViewModel,
    private val grammar: CypherGrammar,
    private val context: BuildContext,
) {

    private val assembler = GraphViewProjectionAssembler(viewModel, grammar, context)

    companion object {
        /** Alias the normalized vector-similarity score is bound to throughout the vector query. */
        private const val SCORE_VAR = "_score"
    }

    /**
     * @param vectorSpec the resolved index + bound parameter names to search
     * @param thresholdParam optional bound parameter name; when set, adds `_score >= $param`
     */
    fun build(vectorSpec: VectorQuerySpec, thresholdParam: String?): String {
        val rootFieldName = assembler.rootFieldName
        val scoreVar = SCORE_VAR

        // The grammar's CALL ... YIELD ... WITH establishes `rootFieldName` and `scoreVar`.
        val head = grammar.vectorSearchHead(vectorSpec, rootFieldName, scoreVar)

        // Build the WITH projection first — it accumulates the prologs/bridge variables the prolog
        // section then reads. Carry the score through so the RETURN can reference it.
        val withSections = assembler.projectionSections().toMutableList()
        withSections.add("    // similarity score\n    $scoreVar")

        val prologSection = vectorPrologSection(rootFieldName, scoreVar)
        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        // Post-projection filter: required relationships are filtered on their *projected* value
        // (null when absent), plus an optional similarity threshold. This runs after the projection
        // WITH — a pre-projection inline existence pattern over a vector-index node trips FalkorDB
        // (see [GraphViewProjectionAssembler.requiredRelationshipAliases]).
        val filters = assembler.requiredRelationshipAliases().map { "$it IS NOT NULL" }.toMutableList()
        thresholdParam?.let { filters.add("$scoreVar >= \$$it") }
        val whereSection = if (filters.isEmpty()) "" else "\nWHERE " + filters.joinToString("\n  AND ")

        // Wrap the view projection and its score in a single map column so the result mapper
        // collapses to one value per row; the manager unpacks `value` + `score` into Scored<T>.
        val returnClause = """

RETURN {
    value: {
${assembler.valueFieldEntries("        ").joinToString(",\n")}
    },
    score: $scoreVar
} AS row
ORDER BY $scoreVar DESC"""

        return head + prologSection + withClause + whereSection + returnClause
    }

    /**
     * Like [GraphViewLoadBuilder]'s prolog section, but carries the similarity [scoreVar] alongside
     * the root and any projection bridge variables. When no prologs were registered the score is
     * already in scope from the grammar's head `WITH`, so nothing is emitted.
     */
    private fun vectorPrologSection(rootFieldName: String, scoreVar: String): String {
        if (context.prologs.isEmpty()) return ""
        val prologs = "\n" + context.prologs.joinToString("\n")
        val carried = (listOf(rootFieldName, scoreVar) + context.bridgeVariables).distinct()
        return "$prologs\nWITH ${carried.joinToString(", ")}"
    }
}