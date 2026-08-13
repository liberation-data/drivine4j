package org.drivine.query

import org.drivine.DrivineException
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Targeting a vector index chosen at runtime rather than at compile time.
 *
 * Per-partition indexes turn a post-filtered ANN read into a pre-filtered one: the search runs inside
 * the partition, so `k` recovers its intuitive meaning instead of being diluted by a `where {}` that
 * only applies after the index has already yielded.
 */
class VectorPartitionLabelTest {

    @NodeFragment(labels = ["Proposition"])
    data class PropositionNode(
        @NodeId val id: String,
        @VectorIndex val embedding: List<Float>?,
    )

    @NodeFragment(labels = ["Pinned"])
    data class PinnedNameNode(
        @NodeId val id: String,
        @VectorIndex(name = "hand_written_index") val embedding: List<Float>?,
    )

    private val neo4j = Neo4j5Grammar(ApocSortMapsEmitter())
    private val vector = listOf(0.1f, 0.2f, 0.3f)

    private fun plan(partitionLabel: String? = null, grammar: org.drivine.query.grammar.CypherGrammar = neo4j) =
        VectorSearchPlanner.plan(
            PropositionNode::class.java, null, vector, topK = 40, threshold = null,
            grammar = grammar, searchK = null, partitionLabel = partitionLabel,
        )

    // ----- Default is unchanged -----

    @Test
    fun `without a partition the index name is a literal, exactly as before`() {
        val p = plan()

        assertTrue(p.cypher.contains("'Proposition_embedding_vector'"), p.cypher)
        assertFalse(p.bindings.containsKey(VectorSearchPlanner.INDEX_NAME_PARAM))
        assertFalse(p.bindings.containsKey(VectorSearchPlanner.LABEL_PARAM))
    }

    // ----- Targeting -----

    @Test
    fun `a partition label re-derives the index name from that label`() {
        val p = plan(partitionLabel = "Corpus_abc")

        assertEquals("Corpus_abc_embedding_vector", p.bindings[VectorSearchPlanner.INDEX_NAME_PARAM])
    }

    @Test
    fun `the derived name matches what the schema side would create for that label`() {
        // The create/read symmetry is the whole reason the label is the parameter rather than the name.
        // If these ever diverge, a partitioned search looks for an index that was never created.
        val p = plan(partitionLabel = "Corpus_abc")
        val created = VectorIndexSpec("Corpus_abc", "embedding", 1536).effectiveName

        assertEquals(created, p.bindings[VectorSearchPlanner.INDEX_NAME_PARAM])
    }

    @Test
    fun `the index name is bound, not interpolated`() {
        // One plan for every partition instead of one per corpus, and no application-derived string
        // concatenated into Cypher.
        val p = plan(partitionLabel = "Corpus_abc")

        assertTrue(p.cypher.contains("\$${VectorSearchPlanner.INDEX_NAME_PARAM}"), p.cypher)
        assertFalse(p.cypher.contains("Corpus_abc"), "label must not appear in the query text:\n${p.cypher}")
    }

    @Test
    fun `two partitions produce identical query text`() {
        // The plan-cache property, stated as a test: same plan, different bindings.
        val a = plan(partitionLabel = "Corpus_a")
        val b = plan(partitionLabel = "Corpus_b")

        assertEquals(a.cypher, b.cypher)
        assertNotEquals(
            a.bindings[VectorSearchPlanner.INDEX_NAME_PARAM],
            b.bindings[VectorSearchPlanner.INDEX_NAME_PARAM],
        )
    }

    @Test
    fun `an engine addressed by label binds the label instead`() {
        // FalkorDB queries by label + property rather than by index name.
        val p = plan(partitionLabel = "Corpus_abc", grammar = FalkorDbCypherGrammar(ApocSortMapsEmitter()))

        assertEquals("Corpus_abc", p.bindings[VectorSearchPlanner.LABEL_PARAM])
        assertTrue(p.cypher.contains("\$${VectorSearchPlanner.LABEL_PARAM}"), p.cypher)
    }

    // ----- Targeting is not identity -----

    @Test
    fun `the projection still returns the fragment's own shape`() {
        // The yielded node carries both labels (:Proposition:Corpus_abc), so switching which index is
        // searched must not change what comes back. This is the assumption partitioning rests on.
        val partitioned = plan(partitionLabel = "Corpus_abc")
        val plain = plan()

        val projectionOf = { c: String -> c.substringAfter("RETURN") }
        assertEquals(projectionOf(plain.cypher), projectionOf(partitioned.cypher))
    }

    // ----- Conflicts -----

    @Test
    fun `an explicitly named index cannot be partitioned`() {
        // With a pinned name there is no per-partition name to derive; silently ignoring one of the two
        // would send the search to an index the caller did not ask for.
        val error = assertThrows<DrivineException> {
            VectorSearchPlanner.plan(
                PinnedNameNode::class.java, null, vector, topK = 40, threshold = null,
                grammar = neo4j, searchK = null, partitionLabel = "Corpus_abc",
            )
        }

        assertTrue(error.message!!.contains("hand_written_index"), error.message)
        assertTrue(error.message!!.contains("Corpus_abc"), error.message)
    }

    // ----- Composes with searchK -----

    @Test
    fun `a partition and an over-fetch compose`() {
        val p = VectorSearchPlanner.plan(
            PropositionNode::class.java, null, vector, topK = 40, threshold = null,
            grammar = neo4j, searchK = 200, partitionLabel = "Corpus_abc",
        )

        assertEquals(200, p.bindings[VectorSearchPlanner.TOP_K_PARAM])
        assertEquals(40, p.bindings[VectorSearchPlanner.ROW_LIMIT_PARAM])
        assertEquals("Corpus_abc_embedding_vector", p.bindings[VectorSearchPlanner.INDEX_NAME_PARAM])
    }
}
