package org.drivine.query.grammar

import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit coverage for each grammar's [CypherGrammar.vectorSearchHead] — the engine-specific divergence
 * point for vector search. Every head must establish the root alias and a normalized,
 * higher-is-closer score alias, and engines without a native vector index must throw.
 */
class VectorSearchGrammarTest {

    private fun spec(similarity: SimilarityFunction = SimilarityFunction.COSINE) = VectorQuerySpec(
        label = "Doc",
        property = "embedding",
        indexName = "Doc_embedding_vector",
        similarity = similarity,
        topKParam = "topK",
        vectorParam = "queryVector",
    )

    @Test
    fun `Neo4j queries by index name and carries the native score through`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())
        assertTrue(grammar.supportsVectorSearch)

        val head = grammar.vectorSearchHead(spec(), rootAlias = "doc", scoreAlias = "_score")

        assertEquals(
            """
            CALL db.index.vector.queryNodes('Doc_embedding_vector', ${'$'}topK, ${'$'}queryVector)
            YIELD node, score
            WITH node AS doc, score AS _score
            """.trimIndent(),
            head,
        )
    }

    /**
     * Over-fetching only pays off if the trim re-ranks by EXACT similarity.
     *
     * Measured on a 9k-vector, 1536-dim cosine index with one dense cluster: the true top 40 are
     * all present inside a beam of 200, yet trimming that beam by the index's OWN yielded score
     * still returns only 38 of them - recall 0.95, not 1.0. Re-ranking the same beam by
     * `vector.similarity.cosine` returns all 40. So the yielded score does not order identically to
     * exact cosine, and a post-filter LIMIT over it discards true matches the beam already found.
     *
     * That makes the rescore the load-bearing half of `searchK`: without it, over-fetching buys row
     * COUNT on the filtered path but not row CORRECTNESS.
     */
    @Test
    fun `Neo4j re-ranks by exact similarity when the caller over-fetches`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

        val head = grammar.vectorSearchHead(
            spec().copy(rowLimitParam = "rowLimit"),
            rootAlias = "doc",
            scoreAlias = "_score",
        )

        assertEquals(
            """
            CALL db.index.vector.queryNodes('Doc_embedding_vector', ${'$'}topK, ${'$'}queryVector)
            YIELD node
            WITH node AS doc, vector.similarity.cosine(node.embedding, ${'$'}queryVector) AS _score
            """.trimIndent(),
            head,
        )
    }

    @Test
    fun `Neo4j euclidean over-fetch re-ranks with its own similarity function`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

        val head = grammar.vectorSearchHead(
            spec(SimilarityFunction.EUCLIDEAN).copy(rowLimitParam = "rowLimit"),
            "doc",
            "_score",
        )

        assertTrue(
            head.contains("vector.similarity.euclidean(node.embedding, \$queryVector) AS _score"),
            "the rescore must use the index's own similarity function, was: $head",
        )
    }

    @Test
    fun `an untuned Neo4j search is byte-identical to what it always emitted`() {
        // The no-op promise: turning the knob off must not change the shape of the query, or
        // "searchK = null" stops being a genuine no-op and every untuned caller inherits a rewrite.
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

        val head = grammar.vectorSearchHead(spec(), "doc", "_score")

        assertTrue(head.contains("YIELD node, score"), "untuned must keep carrying the native score")
        assertFalse(head.contains("vector.similarity"), "untuned must not rescore, was: $head")
    }

    @Test
    fun `FalkorDB queries by label and property, wraps vecf32, and normalizes distance to similarity`() {
        val grammar = FalkorDbCypherGrammar(CallSubqueryEmitter())
        assertTrue(grammar.supportsVectorSearch)

        val cosine = grammar.vectorSearchHead(spec(SimilarityFunction.COSINE), "doc", "_score")
        assertTrue(cosine.contains("CALL db.idx.vector.queryNodes('Doc', 'embedding', \$topK, vecf32(\$queryVector))"))
        // cosine distance d -> similarity 1 - d (higher = closer)
        assertTrue(cosine.contains("WITH node AS doc, 1.0 - score AS _score"))

        val euclidean = grammar.vectorSearchHead(spec(SimilarityFunction.EUCLIDEAN), "doc", "_score")
        assertTrue(euclidean.contains("WITH node AS doc, 1.0 / (1.0 + score) AS _score"))
    }

    @Test
    fun `Memgraph queries by index name and uses the native similarity column`() {
        val grammar = MemgraphGrammar(CallSubqueryEmitter())
        assertTrue(grammar.supportsVectorSearch)

        val head = grammar.vectorSearchHead(spec(), "doc", "_score")
        assertTrue(head.contains("CALL vector_search.search('Doc_embedding_vector', \$topK, \$queryVector)"))
        assertTrue(head.contains("YIELD node, similarity"))
        assertTrue(head.contains("WITH node AS doc, similarity AS _score"))
    }

    @Test
    fun `Neptune has no native vector index and throws`() {
        val grammar = NeptuneCypherGrammar(CallSubqueryEmitter())
        assertFalse(grammar.supportsVectorSearch)
        assertThrows<UnsupportedOperationException> {
            grammar.vectorSearchHead(spec(), "doc", "_score")
        }
    }

    @Test
    fun `base openCypher grammar throws by default`() {
        val grammar = OpenCypherGrammar(CallSubqueryEmitter())
        assertFalse(grammar.supportsVectorSearch)
        assertThrows<UnsupportedOperationException> {
            grammar.vectorSearchHead(spec(), "doc", "_score")
        }
    }

    // ----- Write side: vectorPropertyLiteral (the save-side mirror of the vecf32 read wrapping) -----

    @Test
    fun `FalkorDB wraps a vector property write in vecf32, and reports that it wraps`() {
        val grammar = FalkorDbCypherGrammar(CallSubqueryEmitter())
        assertEquals("vecf32(\$embedding)", grammar.vectorPropertyLiteral("embedding"))
        assertTrue(grammar.wrapsVectorLiteral)
    }

    @Test
    fun `Neo4j and Memgraph write a vector property plainly and do not wrap`() {
        listOf(Neo4j5Grammar(ApocSortMapsEmitter()), MemgraphGrammar(CallSubqueryEmitter())).forEach { grammar ->
            assertEquals("\$embedding", grammar.vectorPropertyLiteral("embedding"))
            assertFalse(grammar.wrapsVectorLiteral, "${grammar::class.simpleName} should not wrap")
        }
    }
}