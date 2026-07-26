package org.drivine.query.dsl

import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Unit coverage for model-aware key resolution ([ResolvableNodeReference.resolveKey] + [field] /
 * [predicateOn]): a declared field resolves by Kotlin name **or** on-disk name; an unmatched key
 * resolves through a single `@PropertyBag` prefix; zero or multiple bags make an unmatched key throw.
 */
class KeyResolutionTest {

    /** Mixed: a promoted @GraphProperty field (`sectionId` → `section_id`) + one @PropertyBag. */
    private class Mixed : ResolvableNodeReference {
        override val nodeAlias = "n"
        override val fieldKeyPaths = mapOf("sectionId" to "section_id", "section_id" to "section_id")
        override val bagPrefixes = listOf("metadata.")
    }

    private class NoBag : ResolvableNodeReference {
        override val nodeAlias = "n"
        override val fieldKeyPaths = mapOf("id" to "id")
        override val bagPrefixes = emptyList<String>()
    }

    private class TwoBag : ResolvableNodeReference {
        override val nodeAlias = "n"
        override val fieldKeyPaths = mapOf("id" to "id")
        override val bagPrefixes = listOf("meta.", "attrs.")
    }

    private val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

    @Test
    fun `a declared field resolves by both its Kotlin name and its on-disk name`() {
        assertEquals("section_id", Mixed().resolveKey("sectionId"))
        assertEquals("section_id", Mixed().resolveKey("section_id"))
    }

    @Test
    fun `an unmatched key resolves through the single bag prefix`() {
        assertEquals("metadata.source", Mixed().resolveKey("source"))
    }

    @Test
    fun `no field match and no bag throws`() {
        val e = assertFailsWith<IllegalArgumentException> { NoBag().resolveKey("nope") }
        assertTrue(e.message!!.contains("no @PropertyBag"), e.message)
    }

    @Test
    fun `no field match and multiple bags throws ambiguous`() {
        val e = assertFailsWith<IllegalArgumentException> { TwoBag().resolveKey("nope") }
        assertTrue(e.message!!.contains("ambiguous"), e.message)
    }

    @Test
    fun `field() renders the resolved stored path (bag key backtick-quoted)`() {
        val spec = GraphQuerySpec(Mixed())
        spec.where { query.field("source") eq "wiki" }
        val where = CypherGenerator.buildWhereClause(spec.conditions, null, grammar).whereClause
        assertEquals("n.`metadata.source` = \$param_n_metadata_source_0", where)
    }

    @Test
    fun `predicateOn() renders the resolved stored path (promoted field by Kotlin name)`() {
        val spec = GraphQuerySpec(Mixed())
        spec.where { query.predicateOn("sectionId", ComparisonOperator.EQUALS, "s1") }
        val where = CypherGenerator.buildWhereClause(spec.conditions, null, grammar).whereClause
        assertEquals("n.section_id = \$param_n_section_id_0", where)
    }
}
