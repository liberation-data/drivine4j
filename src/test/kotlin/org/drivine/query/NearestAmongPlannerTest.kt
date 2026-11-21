package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.VectorIndex
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.SearchStore
import org.drivine.cypher.api.VectorAmong
import org.drivine.cypher.api.VectorPlan
import org.drivine.cypher.api.VectorRequest
import org.drivine.cypher.api.VectorSearch
import org.drivine.schema.SimilarityFunction
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

/**
 * The Cypher statement behind `loadNearestAmong` on a graph engine: the shared module's (`VectorSearch`, drivine-cypher-spec#100),
 * asked by [VectorSearchPlanner.planAmong] with the fragment's index, labels and id.
 */
class NearestAmongPlannerTest {

    /** A fragment with an embedding but no `@NodeId`, so none of its nodes can be named in a set. */
    @NodeFragment(labels = ["Anonymous"])
    data class AnonymousNode(
        val title: String,
        @VectorIndex val embedding: List<Float>?,
    )

    @Test
    fun `the shared module writes the statement from the fragment's index, labels and id`() {
        val plan = VectorSearchPlanner.planAmong(DocNode::class.java, null, listOf("A", "B"), 2, 0.5, 3, Neo4j5Grammar(ApocSortMapsEmitter()))
        val asked = VectorRequest(
            labels = listOf("Doc"), property = "embedding", similarity = SimilarityFunction.COSINE, topK = 2, searchK = 3,
            index = org.drivine.cypher.api.VectorIndex(name = "Doc_embedding_vector"), among = VectorAmong(listOf("A", "B"), "id", 0.5),
        )
        val module = VectorSearch.plan(SearchStore.NEO4J, GraphOptions(""), asked) as VectorPlan
        assertEquals(module.cypher, plan.statement)
        assertEquals(module.parameters, plan.bindings)
        // One statement: each id unwound to its seed, which asks the index for its nearest by its own embedding.
        assertTrue(plan.statement.contains("MATCH (seed:Doc)\nWHERE seed.id = _seedId AND seed.embedding IS NOT NULL\n"), plan.statement)
        assertTrue(plan.statement.contains("CALL db.index.vector.queryNodes('Doc_embedding_vector', "), plan.statement)
        assertTrue(listOf("A", "B") in plan.bindings.values && 3 in plan.bindings.values && 0.5 in plan.bindings.values, "${plan.bindings}")
    }

    @Test
    fun `a view searches its root fragment, and no threshold binds none`() {
        val plan = VectorSearchPlanner.planAmong(DocView::class.java, null, listOf("A"), 2, null, 3, MemgraphGrammar(CallSubqueryEmitter()))

        assertTrue(plan.statement.contains("MATCH (seed:Doc)"), plan.statement)
        assertFalse(plan.statement.contains("m.score >="), plan.statement)
        assertTrue(plan.bindings.values.none { it is Double }, "${plan.bindings}")
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
