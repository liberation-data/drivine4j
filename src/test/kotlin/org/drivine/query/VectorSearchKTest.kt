package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.VectorIndex
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The over-fetch knob on filtered vector search.
 *
 * `k` handed to a Lucene-backed vector index is the HNSW search beam width, not merely a row count —
 * a vector at true rank 3 can be missed at k = 100 and found at k = 200 on the same index. Because the
 * `where {}` filter runs *after* the index yields, a scoped caller also receives roughly
 * `k × selectivity` rows. `searchK` addresses both: search wide, then trim what survives.
 */
class VectorSearchKTest {

    @NodeFragment(labels = ["Probe"])
    data class ProbeNode(
        @NodeId val id: String,
        @VectorIndex val embedding: List<Float>?,
    )

    private val grammar = Neo4j5Grammar(ApocSortMapsEmitter())
    private val vector = listOf(0.1f, 0.2f, 0.3f)

    private fun plan(topK: Int, searchK: Int? = null) =
        VectorSearchPlanner.plan(ProbeNode::class.java, null, vector, topK, null, grammar, searchK)

    // ----- Off by default -----

    @Test
    fun `without searchK the query is unchanged and carries no LIMIT`() {
        // Turning the knob off must be a genuine no-op, not a differently-shaped query.
        val p = plan(topK = 40)

        assertFalse(p.cypher.contains("LIMIT"), p.cypher)
        assertEquals(40, p.bindings[VectorSearchPlanner.TOP_K_PARAM])
        assertFalse(p.bindings.containsKey(VectorSearchPlanner.ROW_LIMIT_PARAM))
    }

    // ----- The knob -----

    @Test
    fun `searchK becomes the K handed to the index`() {
        // This is the recall lever: the index searches with a beam of 200, not 40.
        val p = plan(topK = 40, searchK = 200)

        assertEquals(200, p.bindings[VectorSearchPlanner.TOP_K_PARAM])
    }

    @Test
    fun `topK becomes the post-filter row limit`() {
        val p = plan(topK = 40, searchK = 200)

        assertEquals(40, p.bindings[VectorSearchPlanner.ROW_LIMIT_PARAM])
        assertTrue(p.cypher.contains("LIMIT \$${VectorSearchPlanner.ROW_LIMIT_PARAM}"), p.cypher)
    }

    @Test
    fun `the LIMIT is emitted after the ORDER BY, not before the filter`() {
        // Trimming before the WHERE would discard exactly the rows over-fetching was meant to recover.
        val p = plan(topK = 40, searchK = 200)
        val orderBy = p.cypher.indexOf("ORDER BY")
        val limit = p.cypher.indexOf("LIMIT")

        assertTrue(orderBy in 1..<limit, "expected ORDER BY before LIMIT:\n${p.cypher}")
    }

    @Test
    fun `searchK equal to topK still emits a limit and binds both`() {
        val p = plan(topK = 40, searchK = 40)

        assertEquals(40, p.bindings[VectorSearchPlanner.TOP_K_PARAM])
        assertEquals(40, p.bindings[VectorSearchPlanner.ROW_LIMIT_PARAM])
    }

    @Test
    fun `the query vector and index name are unaffected by over-fetching`() {
        val p = plan(topK = 40, searchK = 200)

        assertEquals(vector, p.bindings[VectorSearchPlanner.QUERY_PARAM])
        assertTrue(p.cypher.contains("Probe_embedding_vector"), p.cypher)
    }
}
