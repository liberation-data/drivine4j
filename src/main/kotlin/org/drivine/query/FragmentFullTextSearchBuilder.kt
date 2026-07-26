package org.drivine.query

import org.drivine.model.FragmentModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.FullTextQuerySpec

/**
 * Generates a Cypher full-text search over a plain `@NodeFragment`.
 *
 * The full-text counterpart to [FragmentVectorSearchBuilder]: the grammar's full-text `CALL` head
 * (already score-normalized to `[0, 1]`) produces the matching nodes, which are projected with the
 * fragment's own field mapping (the same shape [FragmentQueryBuilder] uses for a load) and returned
 * as `{ value: <fragment>, score } AS row`, ordered by relevance and capped at `topK`. Fragments
 * have no relationships, so the only filter is the optional similarity threshold.
 *
 * Unlike vector search — where `topK` is the index `CALL`'s `k` — full-text `CALL`s take no `k`, so
 * `topK` is applied here as a trailing `LIMIT`.
 */
internal class FragmentFullTextSearchBuilder(
    private val fragmentModel: FragmentModel,
    private val grammar: CypherGrammar,
) {

    companion object {
        private const val SCORE_VAR = "_score"
        private const val NODE_ALIAS = "n"
    }

    /**
     * @param spec the resolved index + bound parameter names to search
     * @param thresholdParam optional bound parameter name; when set, adds `_score >= $param`
     * @param callerWhere optional caller-supplied predicate (already rendered Cypher against the node
     *   alias `n`, without the `WHERE` keyword) `AND`-ed into the filter — the fragment DSL's node
     *   property predicates, applied directly to the full node the search head bound to `n`.
     */
    fun build(spec: FullTextQuerySpec, thresholdParam: String?, callerWhere: String? = null): String {
        if (fragmentModel.labels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for fragment ${fragmentModel.className}. @GraphFragment must specify at least one label.")
        }

        val node = NODE_ALIAS
        val scoreVar = SCORE_VAR

        // The grammar's CALL ... normalize ... WITH establishes `node` and `scoreVar`.
        val head = grammar.fullTextSearchHead(spec, node, scoreVar)

        // A fragment's filters: the optional score threshold (a scalar — no relationships) and any
        // caller `where { }` predicate over the node's properties.
        val filters = buildList {
            thresholdParam?.let { add("$scoreVar >= \$$it") }
            callerWhere?.let { add(it) }
        }
        val whereSection = if (filters.isEmpty()) "" else "\nWHERE " + filters.joinToString("\n  AND ")

        val limit = "\nLIMIT \$${spec.topKParam}"

        val isPolymorphic = fragmentModel.clazz.kotlin.isAbstract || fragmentModel.clazz.kotlin.isSealed

        // Wrap the fragment projection + score in a single map column so the result mapper collapses
        // to one value per row; the manager unpacks `value` + `score` into Scored<T>.
        val returnClause = if (isPolymorphic) {
            // Polymorphic types project all properties via .* (concrete subtype resolved from labels).
            """

WITH properties($node) AS props, labels($node) AS lbls, $scoreVar
RETURN {
    value: props {
        .*,
        labels: lbls
    },
    score: $scoreVar
} AS row
ORDER BY $scoreVar DESC"""
        } else {
            val fieldMappings = fragmentModel.fields.joinToString(",\n        ") {
                "${it.name}: $node.${it.propertyName}"
            }
            """

RETURN {
    value: {
        $fieldMappings,
        labels: labels($node)
    },
    score: $scoreVar
} AS row
ORDER BY $scoreVar DESC"""
        }

        return head + whereSection + returnClause + limit
    }
}