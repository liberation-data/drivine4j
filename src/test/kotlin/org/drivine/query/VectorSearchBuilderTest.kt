package org.drivine.query

import org.drivine.DrivineException
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.vector.DocNode
import sample.vector.DocView
import sample.vector.DualEmbeddingNode

/**
 * Unit coverage for the vector-search query generation: index resolution from `@VectorIndex`, and
 * the shape of the Cypher [GraphViewQueryBuilder.buildVectorQuery] emits — CALL head, post-filter
 * WHERE, scored RETURN wrapper, and ORDER BY similarity.
 */
class VectorSearchBuilderTest {

    private val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

    // ----- Index resolution -----

    @Test
    fun `infers the sole VectorIndex property on the root fragment`() {
        val spec = VectorIndexResolver.resolve(DocNode::class.java, property = null, topKParam = "k", vectorParam = "v")
        assertEquals("Doc", spec.label)
        assertEquals("embedding", spec.property)
        assertEquals("Doc_embedding_vector", spec.indexName)
        assertEquals(SimilarityFunction.COSINE, spec.similarity)
    }

    @Test
    fun `throws when a named property is not VectorIndexed`() {
        val e = assertThrows<DrivineException> {
            VectorIndexResolver.resolve(DocNode::class.java, property = "title", topKParam = "k", vectorParam = "v")
        }
        assertTrue(e.message!!.contains("not annotated with @VectorIndex"))
    }

    @Test
    fun `throws when several embeddings exist and none is named`() {
        val e = assertThrows<DrivineException> {
            VectorIndexResolver.resolve(DualEmbeddingNode::class.java, property = null, topKParam = "k", vectorParam = "v")
        }
        assertTrue(e.message!!.contains("multiple @VectorIndex"))
    }

    @Test
    fun `disambiguates by property name and carries that property's similarity`() {
        val body = VectorIndexResolver.resolve(DualEmbeddingNode::class.java, "bodyEmbedding", "k", "v")
        assertEquals("bodyEmbedding", body.property)
        assertEquals("DualDoc_bodyEmbedding_vector", body.indexName)
        assertEquals(SimilarityFunction.EUCLIDEAN, body.similarity)

        val title = VectorIndexResolver.resolve(DualEmbeddingNode::class.java, "titleEmbedding", "k", "v")
        assertEquals(SimilarityFunction.COSINE, title.similarity)
    }

    // ----- Cypher shape -----

    @Test
    fun `vector query swaps MATCH for the CALL head, post-filters, and orders by score`() {
        val spec = VectorIndexResolver.resolve(DocNode::class.java, null, "_vectorTopK", "_vectorQuery")
        val cypher = GraphViewQueryBuilder.forView(DocView::class.java, grammar)
            .buildVectorQuery(spec, thresholdParam = null)

        // CALL head replaces MATCH, binding root + score
        assertTrue(cypher.startsWith("CALL db.index.vector.queryNodes('Doc_embedding_vector'"))
        assertTrue(cypher.contains("WITH node AS doc, score AS _score"))
        // required relationship (author) is filtered on its projected value AFTER the projection
        // (a pre-projection inline existence pattern trips FalkorDB on vector-index nodes)
        assertTrue(cypher.contains("WHERE author IS NOT NULL"))
        // the filter comes after the projection WITH, not before it
        assertTrue(cypher.indexOf("WHERE author IS NOT NULL") > cypher.indexOf("AS author"))
        // scored wrapper + ordering
        assertTrue(cypher.contains("value: {"))
        assertTrue(cypher.contains("score: _score"))
        assertTrue(cypher.contains("} AS row"))
        assertTrue(cypher.trimEnd().endsWith("ORDER BY _score DESC"))
        // MATCH must not appear — the index produces the roots
        assertTrue(!cypher.contains("MATCH ("))
    }

    @Test
    fun `threshold adds a score floor to the WHERE`() {
        val spec = VectorIndexResolver.resolve(DocNode::class.java, null, "_vectorTopK", "_vectorQuery")
        val cypher = GraphViewQueryBuilder.forView(DocView::class.java, grammar)
            .buildVectorQuery(spec, thresholdParam = "_vectorThreshold")

        assertTrue(cypher.contains("_score >= \$_vectorThreshold"))
    }
}