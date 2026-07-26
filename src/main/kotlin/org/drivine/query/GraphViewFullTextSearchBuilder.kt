package org.drivine.query

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.FullTextQuerySpec

/**
 * Generates a Cypher **full-text** search over a single `@GraphView`.
 *
 * The full-text counterpart to [GraphViewVectorSearchBuilder]: the grammar's full-text `CALL` head
 * (already score-normalized to `[0, 1]`) replaces the `MATCH` (the index *produces* the root nodes),
 * the relevance is threaded through as `_score`, and the same projection + required-relationship
 * `EXISTS` checks the normal load uses (via the shared [GraphViewProjectionAssembler]) are layered
 * on top. Results are wrapped as `{ value: <view>, score: _score } AS row`, ordered by relevance
 * (highest first) and capped at `topK` (a trailing `LIMIT`, since the full-text `CALL` takes no `k`).
 *
 * Filtering is **post-search**: required relationships (and the optional threshold / caller `where`)
 * prune the candidates the index returned, so the query can yield fewer than `topK` rows.
 */
internal class GraphViewFullTextSearchBuilder(
    viewModel: GraphViewModel,
    private val grammar: CypherGrammar,
    private val context: BuildContext,
) {

    private val assembler = GraphViewProjectionAssembler(viewModel, grammar, context)

    companion object {
        /** Alias the normalized full-text relevance score is bound to throughout the query. */
        private const val SCORE_VAR = "_score"
    }

    /**
     * @param spec the resolved index + bound parameter names to search
     * @param thresholdParam optional bound parameter name; when set, adds `_score >= $param`
     * @param callerWhere optional caller-supplied predicate (already rendered Cypher, without the
     *   `WHERE` keyword) `AND`-ed into the post-projection filter, exactly as the vector path does.
     */
    fun build(spec: FullTextQuerySpec, thresholdParam: String?, callerWhere: String? = null): String {
        val rootFieldName = assembler.rootFieldName
        val scoreVar = SCORE_VAR

        // The grammar's CALL ... normalize ... WITH establishes `rootFieldName` and `scoreVar`.
        val head = grammar.fullTextSearchHead(spec, rootFieldName, scoreVar)

        // Build the WITH projection first — it accumulates the prologs/bridge variables the prolog
        // section then reads. Carry the score through so the RETURN can reference it.
        val withSections = assembler.projectionSections().toMutableList()
        withSections.add("    // relevance score\n    $scoreVar")

        val prologSection = fullTextPrologSection(rootFieldName, scoreVar)
        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        // Post-projection filter: required relationships (null when absent), an optional relevance
        // threshold, and any caller-supplied predicate — the same post-projection placement the
        // vector path uses to stay FalkorDB-safe.
        val filters = assembler.requiredRelationshipAliases().map { "$it IS NOT NULL" }.toMutableList()
        thresholdParam?.let { filters.add("$scoreVar >= \$$it") }
        callerWhere?.let { filters.add(it) }
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
ORDER BY $scoreVar DESC
LIMIT ${'$'}${spec.topKParam}"""

        return head + prologSection + withClause + whereSection + returnClause
    }

    /**
     * Like [GraphViewVectorSearchBuilder]'s prolog section, but carries the relevance [scoreVar]
     * alongside the root and any projection bridge variables. When no prologs were registered the
     * score is already in scope from the grammar's head `WITH`, so nothing is emitted.
     */
    private fun fullTextPrologSection(rootFieldName: String, scoreVar: String): String {
        if (context.prologs.isEmpty()) return ""
        val prologs = "\n" + context.prologs.joinToString("\n")
        val carried = (listOf(rootFieldName, scoreVar) + context.bridgeVariables).distinct()
        return "$prologs\nWITH ${carried.joinToString(", ")}"
    }
}