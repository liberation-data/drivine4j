package org.drivine.query

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.vector.DocNode

/**
 * Unit coverage for the write-side vector wrapping in [FragmentMergeBuilder]: a `@VectorIndex` field
 * is emitted through the grammar's `vectorPropertyLiteral`, so FalkorDB stores the embedding as its
 * native vector type. The binding is unchanged; only the SET-clause RHS differs.
 */
class VectorMergeBuilderTest {

    private val mapper = Neo4jObjectMapper.instance
    private val model = FragmentModel.from(DocNode::class.java)

    @Test
    fun `model recognizes the VectorIndex field`() {
        assertTrue(model.fields.any { it.name == "embedding" && it.vectorIndexed })
        assertEquals(setOf("embedding"), model.vectorFieldNames)
    }

    @Test
    fun `FalkorDB wraps the embedding in vecf32, binding unchanged`() {
        val builder = FragmentMergeBuilder(model, mapper, FalkorDbCypherGrammar(CallSubqueryEmitter()))
        val embedding = listOf(1.0f, 0.0f, 0.0f, 0.0f)

        val stmt = builder.buildMergeStatement(DocNode("A", "Alpha", embedding), dirtyFields = null)

        assertTrue(stmt.statement.contains("n.embedding = vecf32(\$embedding)"), stmt.statement)
        // A non-vector field is written plainly
        assertTrue(stmt.statement.contains("n.title = \$title"), stmt.statement)
        // The binding is the raw value, unchanged by the wrapping
        assertEquals(embedding, stmt.bindings["embedding"])
    }

    @Test
    fun `Neo4j writes the embedding plainly - byte-identical to before`() {
        val builder = FragmentMergeBuilder(model, mapper, Neo4j5Grammar(ApocSortMapsEmitter()))
        val stmt = builder.buildMergeStatement(
            DocNode("A", "Alpha", listOf(1.0f, 0.0f, 0.0f, 0.0f)), dirtyFields = null
        )

        assertTrue(stmt.statement.contains("n.embedding = \$embedding"), stmt.statement)
        assertFalse(stmt.statement.contains("vecf32"), stmt.statement)
    }

    @Test
    fun `a null embedding is never wrapped - vecf32(null) is invalid`() {
        val builder = FragmentMergeBuilder(model, mapper, FalkorDbCypherGrammar(CallSubqueryEmitter()))
        val stmt = builder.buildMergeStatement(DocNode("A", "Alpha", embedding = null), dirtyFields = null)

        // Plain assignment (which clears the property), matching normal null semantics
        assertTrue(stmt.statement.contains("n.embedding = \$embedding"), stmt.statement)
        assertFalse(stmt.statement.contains("vecf32"), stmt.statement)
    }

    @Test
    fun `without a grammar the embedding is written plainly`() {
        val builder = FragmentMergeBuilder(model, mapper) // no grammar
        val stmt = builder.buildMergeStatement(
            DocNode("A", "Alpha", listOf(1.0f, 0.0f, 0.0f, 0.0f)), dirtyFields = null
        )
        assertTrue(stmt.statement.contains("n.embedding = \$embedding"), stmt.statement)
        assertFalse(stmt.statement.contains("vecf32"), stmt.statement)
    }
}
