package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.VectorIndex
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The counts a scored plan carries so a short result can be reported.
 *
 * A filtered vector search returns roughly `k × selectivity` rows because the filter runs after the
 * index yields. Without these, a caller sees a short list and cannot tell dilution from a genuinely
 * small result.
 */
class VectorYieldReportingTest {

    @NodeFragment(labels = ["Probe"])
    data class ProbeNode(
        @NodeId val id: String,
        @VectorIndex val embedding: List<Float>?,
    )

    @NodeFragment(labels = ["Doc"])
    data class TextNode(
        @NodeId val id: String,
        @FullTextIndex val body: String?,
    )

    private val grammar = Neo4j5Grammar(ApocSortMapsEmitter())
    private val vector = listOf(0.1f, 0.2f, 0.3f)

    private fun plan(topK: Int, searchK: Int? = null) =
        VectorSearchPlanner.plan(ProbeNode::class.java, null, vector, topK, null, grammar, searchK)

    @Test
    fun `a plan records the rows the caller asked for`() {
        assertEquals(40, plan(topK = 40).requestedRows)
    }

    @Test
    fun `without over-fetching the index was asked for the same number`() {
        assertEquals(40, plan(topK = 40).indexK)
    }

    @Test
    fun `with over-fetching the two counts differ`() {
        // This is the pair that makes a short result interpretable: asked the index for 200, wanted 40.
        val p = plan(topK = 40, searchK = 200)

        assertEquals(40, p.requestedRows)
        assertEquals(200, p.indexK)
    }

    @Test
    fun `the counts survive a filtered plan`() {
        // The filtered path is the one that actually dilutes, so it is the one that must carry them.
        val p = VectorSearchPlanner.plan(
            ProbeNode::class.java, null, vector, topK = 25, threshold = null,
            grammar = grammar, searchK = 100,
        )

        assertEquals(25, p.requestedRows)
        assertEquals(100, p.indexK)
    }

    @Test
    fun `a full-text plan carries no row counts`() {
        // Full-text shares the executor but not this concern; null means "do not report".
        val p = FullTextSearchPlanner.plan(TextNode::class.java, null, "hello", 10, 0.0, grammar)

        assertNull(p.requestedRows)
        assertNull(p.indexK)
    }
}
