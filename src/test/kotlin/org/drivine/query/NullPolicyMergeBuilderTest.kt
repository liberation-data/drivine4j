package org.drivine.query

import org.drivine.manager.NullPolicy
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.nullpolicy.VecSaveNode
import kotlin.test.assertEquals

/**
 * Unit coverage for null-write policy in [FragmentMergeBuilder]. The policy is uniform for every field
 * (no special-casing — an embedding is just a property): [NullPolicy.IGNORE] skips nulls (merge-patch),
 * [NullPolicy.CLEAR] writes `SET x = null` to clear them. Non-null vectors still wrap via the grammar.
 */
class NullPolicyMergeBuilderTest {

    private val model = FragmentModel.from(VecSaveNode::class.java)
    private val mapper = Neo4jObjectMapper.instance
    private val falkor = FalkorDbCypherGrammar(CallSubqueryEmitter())

    /** Detached (dirtyFields = null → all fields), so every field is a candidate to write. */
    private fun build(obj: VecSaveNode, policy: NullPolicy) =
        FragmentMergeBuilder(model, mapper, falkor).buildMergeStatement(obj, dirtyFields = null, nullPolicy = policy)

    @Test
    fun `IGNORE skips a null scalar and a null embedding alike (merge-patch)`() {
        val stmt = build(VecSaveNode(id = "x", title = null, embedding = null), NullPolicy.IGNORE)
        assertFalse(stmt.statement.contains("title"), stmt.statement)
        assertFalse(stmt.statement.contains("embedding"), stmt.statement)
        assertFalse(stmt.statement.contains("SET"), "all-null IGNORE writes no SET: ${stmt.statement}")
        assertEquals(setOf("id"), stmt.bindings.keys)
    }

    @Test
    fun `CLEAR clears a null scalar and a null embedding alike`() {
        val stmt = build(VecSaveNode(id = "x", title = null, embedding = null), NullPolicy.CLEAR)
        // both cleared with a plain SET — the embedding is NOT wrapped in vecf32 (vecf32(null) is invalid);
        // we only wrap non-null values.
        assertTrue(stmt.statement.contains("n.title = \$title"), stmt.statement)
        assertTrue(stmt.statement.contains("n.embedding = \$embedding"), stmt.statement)
        assertFalse(stmt.statement.contains("vecf32"), stmt.statement)
        assertTrue(stmt.bindings["title"] == null && stmt.bindings["embedding"] == null)
    }

    @Test
    fun `a non-null embedding is wrapped in vecf32 under either policy`() {
        val vec = listOf(1.0f, 0.0f)
        listOf(NullPolicy.IGNORE, NullPolicy.CLEAR).forEach { policy ->
            val stmt = build(VecSaveNode(id = "x", title = "A", embedding = vec), policy)
            assertTrue(stmt.statement.contains("n.embedding = vecf32(\$embedding)"), "$policy: ${stmt.statement}")
            assertEquals(vec, stmt.bindings["embedding"])
            assertTrue(stmt.statement.contains("n.title = \$title"), stmt.statement)
        }
    }

    @Test
    fun `a null bag value clears under CLEAR but is skipped under IGNORE`() {
        val bagModel = FragmentModel.from(sample.dynamicfilter.RecordNode::class.java)
        val obj = sample.dynamicfilter.RecordNode(id = "x", title = "t", metadata = mapOf("source" to null))
        fun bagStmt(policy: NullPolicy) =
            FragmentMergeBuilder(bagModel, mapper).buildMergeStatement(obj, dirtyFields = null, nullPolicy = policy)

        assertTrue(bagStmt(NullPolicy.CLEAR).statement.contains("`metadata.source`"))
        assertFalse(bagStmt(NullPolicy.IGNORE).statement.contains("`metadata.source`"))
    }

    @Test
    fun `the default policy is IGNORE`() {
        // No explicit policy → merge-patch: the null embedding is left untouched.
        val stmt = FragmentMergeBuilder(model, mapper, falkor)
            .buildMergeStatement(VecSaveNode(id = "x", title = "A", embedding = null), dirtyFields = null)
        assertFalse(stmt.statement.contains("embedding"), stmt.statement)
        assertTrue(stmt.statement.contains("n.title = \$title"), stmt.statement)
    }
}
