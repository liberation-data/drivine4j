package org.drivine.query

import org.drivine.model.FragmentModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.VectorQuerySpec
import org.drivine.schema.SchemaGrammar

/**
 * Writes the Cypher search that finds, for each of a set of nodes, its nearest among the same set: the
 * statement behind `loadNearestAmong` on the graph engines.
 *
 * Each id is unwound to its node, and the grammar's vector head is asked for that node's nearest by
 * the node's own embedding, so the engine answers every seed in one statement through its index. The
 * index knows nothing of the set, so what it yields is narrowed afterwards to the set, less the seed
 * itself and what scores under the threshold, and the best of what is left are kept. That is why the
 * index is asked for more than are kept: a seed whose nearest lie mostly outside the set needs a wider
 * search to find those inside it.
 *
 * One row comes back for each seed found with its embedding, `{seed, near}`, `near` its neighbours as
 * `{id, score}`, best first. A seed whose neighbours are all narrowed away still has its row, with
 * `near` empty, so a caller can tell it from an id with no embedding, which has none.
 */
internal class NearestAmongBuilder(
    private val fragmentModel: FragmentModel,
    private val grammar: CypherGrammar,
) {

    companion object {
        private const val SEED = "seed"
        private const val SEED_ID = "_seedId"
        private const val NODE = "n"
        private const val SCORE = "_score"
    }

    /**
     * @param vectorSpec the resolved index, with the parameter of the index's K; its vector is read
     *   from each seed, not bound
     * @param idsParam the parameter that holds the ids of the set
     * @param topKParam the parameter that holds how many neighbours each seed keeps
     * @param thresholdParam the parameter of the least score kept, or null to keep every score
     */
    fun build(vectorSpec: VectorQuerySpec, idsParam: String, topKParam: String, thresholdParam: String?): String {
        require(fragmentModel.labels.isNotEmpty()) {
            "No labels defined for fragment ${fragmentModel.className}. @NodeFragment must specify at least one label."
        }
        val id = requireNotNull(fragmentModel.nodeIdReference) { "${fragmentModel.className} has no @NodeId field to find a node by." }
        val embedding = "$SEED.${SchemaGrammar.identifier(vectorSpec.property)}"
        val head = grammar.vectorSearchHead(vectorSpec.copy(vectorSource = embedding, carried = listOf(SEED_ID)), NODE, SCORE)
        val kept = listOfNotNull("m.id IN \$$idsParam", "m.id <> $SEED_ID", thresholdParam?.let { "m.score >= \$$it" })
        // The index is on the first label, so a neighbour with that label alone is in it, and is no node
        // of the set, though it may have an id the caller passed.
        val labelled = indexedNodeLabelCheck(NODE, fragmentModel.labels, vectorSpec.label)?.let { "\nWHERE $it" } ?: ""
        return """
            |UNWIND ${'$'}$idsParam AS $SEED_ID
            |MATCH ($SEED:${fragmentModel.labels.joinToString(":")})
            |WHERE $SEED.$id = $SEED_ID AND $embedding IS NOT NULL
            |$head$labelled
            |WITH $SEED_ID, $NODE, $SCORE
            |ORDER BY $SCORE DESC
            |WITH $SEED_ID, collect({id: $NODE.$id, score: $SCORE}) AS _found
            |RETURN {seed: $SEED_ID, near: [m IN _found WHERE ${kept.joinToString(" AND ")}][0..${'$'}$topKParam]} AS row
        """.trimMargin()
    }
}
