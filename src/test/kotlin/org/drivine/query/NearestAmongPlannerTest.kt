package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.VectorIndex
import org.drivine.query.grammar.MemgraphGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.grammar.NeptuneCypherGrammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.vector.DocNode
import sample.vector.DocView

/** The Cypher statement behind `loadNearestAmong`: what [VectorSearchPlanner.planAmong] writes and binds. */
class NearestAmongPlannerTest {

    /** A fragment with an embedding but no `@NodeId`, so none of its nodes can be named in a set. */
    @NodeFragment(labels = ["Anonymous"])
    data class AnonymousNode(
        val title: String,
        @VectorIndex val embedding: List<Float>?,
    )

    @Test
    fun `one statement unwinds the ids, searches from each seed's embedding, and narrows to the set`() {
        val plan = VectorSearchPlanner.planAmong(DocNode::class.java, null, listOf("A", "B"), 2, 0.5, 3, Neo4j5Grammar(ApocSortMapsEmitter()))

        assertEquals(
            """
            UNWIND ${'$'}_amongIds AS _seedId
            MATCH (seed:Doc)
            WHERE seed.id = _seedId AND seed.embedding IS NOT NULL
            CALL db.index.vector.queryNodes('Doc_embedding_vector', ${'$'}_vectorTopK, seed.embedding)
            YIELD node
            WITH _seedId, node AS n, vector.similarity.cosine(node.embedding, seed.embedding) AS _score
            WITH _seedId, n, _score
            ORDER BY _score DESC
            WITH _seedId, collect({id: n.id, score: _score}) AS _found
            RETURN {seed: _seedId, near: [m IN _found WHERE m.id IN ${'$'}_amongIds AND m.id <> _seedId AND m.score >= ${'$'}_vectorThreshold][0..${'$'}_amongTopK]} AS row
            """.trimIndent(),
            plan.statement,
        )
        assertEquals(
            mapOf("_vectorTopK" to 3, "_amongIds" to listOf("A", "B"), "_amongTopK" to 2, "_vectorThreshold" to 0.5),
            plan.bindings,
        )
    }

    @Test
    fun `a view searches its root fragment, and no threshold binds none`() {
        val plan = VectorSearchPlanner.planAmong(DocView::class.java, null, listOf("A"), 2, null, 3, MemgraphGrammar(CallSubqueryEmitter()))

        assertTrue(plan.statement.contains("MATCH (seed:Doc)"), plan.statement)
        assertFalse(plan.statement.contains("_vectorThreshold"), plan.statement)
        assertFalse("_vectorThreshold" in plan.bindings)
    }

    @Test
    fun `a class that is neither a view nor a fragment is refused`() {
        val failure = assertThrows<IllegalArgumentException> {
            VectorSearchPlanner.planAmong(String::class.java, null, listOf("A"), 2, null, 3, Neo4j5Grammar(ApocSortMapsEmitter()))
        }
        assertTrue(failure.message!!.contains("loadNearestAmong requires a @GraphView or @NodeFragment"), failure.message)
    }

    @Test
    fun `a fragment with no NodeId is refused, as no id can name its nodes`() {
        val failure = assertThrows<IllegalArgumentException> {
            VectorSearchPlanner.planAmong(AnonymousNode::class.java, null, listOf("A"), 2, null, 3, Neo4j5Grammar(ApocSortMapsEmitter()))
        }
        assertTrue(failure.message!!.contains("has no @NodeId field"), failure.message)
    }

    @Test
    fun `Neptune has no vector index, so the search is refused as loadNearest is`() {
        assertThrows<UnsupportedOperationException> {
            VectorSearchPlanner.planAmong(DocNode::class.java, null, listOf("A"), 2, null, 3, NeptuneCypherGrammar(CallSubqueryEmitter()))
        }
    }
}
