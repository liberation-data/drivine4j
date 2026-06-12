package org.drivine.query

import org.drivine.model.FragmentModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.VectorQuerySpec

/**
 * Generates a Cypher vector (approximate nearest-neighbour) search over a plain `@NodeFragment`.
 *
 * The fragment counterpart to [GraphViewVectorSearchBuilder]: the grammar's vector `CALL` head
 * produces the matching nodes, which are projected with the fragment's own field mapping (the same
 * shape [FragmentQueryBuilder] uses for a load) and returned as `{ value: <fragment>, score } AS
 * row`, ordered by similarity. Fragments have no relationships, so there are no required-relationship
 * filters — only the optional similarity threshold.
 */
internal class FragmentVectorSearchBuilder(
    private val fragmentModel: FragmentModel,
    private val grammar: CypherGrammar,
) {

    companion object {
        private const val SCORE_VAR = "_score"
        private const val NODE_ALIAS = "n"
    }

    /**
     * @param vectorSpec the resolved index + bound parameter names to search
     * @param thresholdParam optional bound parameter name; when set, adds `_score >= $param`
     */
    fun build(vectorSpec: VectorQuerySpec, thresholdParam: String?): String {
        if (fragmentModel.labels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for fragment ${fragmentModel.className}. @GraphFragment must specify at least one label.")
        }

        val node = NODE_ALIAS
        val scoreVar = SCORE_VAR

        // The grammar's CALL ... YIELD ... WITH establishes `node` and `scoreVar`.
        val head = grammar.vectorSearchHead(vectorSpec, node, scoreVar)

        // The only filter a fragment can have is the score threshold (a scalar — no relationships).
        val whereSection = thresholdParam?.let { "\nWHERE $scoreVar >= \$$it" } ?: ""

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
                "${it.name}: $node.${it.name}"
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

        return head + whereSection + returnClause
    }
}