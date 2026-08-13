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
 * row`, ordered by similarity. Fragments have no relationships, so the only filters are the optional
 * similarity threshold and an optional caller `where { }` predicate over the node's properties.
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
     * @param callerWhere optional caller-supplied predicate (already rendered Cypher against the node
     *   alias `n`, without the `WHERE` keyword) `AND`-ed into the filter — the fragment DSL's node
     *   property predicates, applied to the full node the vector head bound to `n`.
     */
    fun build(vectorSpec: VectorQuerySpec, thresholdParam: String?, callerWhere: String? = null): String {
        if (fragmentModel.labels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for fragment ${fragmentModel.className}. @GraphFragment must specify at least one label.")
        }

        val node = NODE_ALIAS
        val scoreVar = SCORE_VAR

        // The grammar's CALL ... YIELD ... WITH establishes `node` and `scoreVar`.
        val head = grammar.vectorSearchHead(vectorSpec, node, scoreVar)

        // A fragment's filters: the optional score threshold (a scalar — no relationships) and any
        // caller `where { }` predicate over the node's properties.
        val filters = buildList {
            thresholdParam?.let { add("$scoreVar >= \$$it") }
            callerWhere?.let { add(it) }
        }
        val whereSection = if (filters.isEmpty()) "" else "\nWHERE " + filters.joinToString("\n  AND ")

        // Present only when the caller searched wider than the rows they want back: the index yields
        // the wide set, the WHERE thins it, and this trims what survives to the requested count. The
        // trim must follow the filter — trimming first would discard exactly the rows over-fetching
        // was meant to recover.
        val limitSection = vectorSpec.rowLimitParam?.let { "\nLIMIT \$$it" } ?: ""

        // A bag fragment takes the `.*` path for the same reason FragmentQueryBuilder does: the open
        // prefixed properties have no single column to map, so the explicit projection would drop them.
        val isPolymorphic = fragmentModel.clazz.kotlin.isAbstract || fragmentModel.clazz.kotlin.isSealed ||
            fragmentModel.propertyBags.isNotEmpty()

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
ORDER BY $scoreVar DESC""" + limitSection
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
ORDER BY $scoreVar DESC""" + limitSection
        }

        return head + whereSection + returnClause
    }
}