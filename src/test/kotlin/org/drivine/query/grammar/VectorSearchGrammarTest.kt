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
}