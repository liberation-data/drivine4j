package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.MemgraphGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl

/**
 * Feature 1 — quantified predicates (`any{}` / `none{}`) over a to-many `@GraphRelationship`.
 *
 * `PropositionView` has `mentions: List<Mention>` (to-many). The quantifier blocks build a
 * [WhereCondition.RelationshipCondition]; `none{}` flips its `negate` flag, which the generator
 * renders as a portable `NOT (...)` around the positive existence check.
 */
class QuantifiedRelationshipTest {

    private val viewModel = GraphViewModel.from(PropositionView::class.java)

    private fun conditionsOf(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): List<WhereCondition> {
        val querySpec = GraphQuerySpec(PropositionViewQueryDsl.INSTANCE)
        querySpec.spec()
        return querySpec.conditions
    }

    // ----- DSL builds the right condition -----

    @Test
    fun `any builds a non-negated relationship condition scoped to the target`() {
        val conditions = conditionsOf { where { query.mentions.any { resolvedId eq "ent-1" } } }
        val rel = conditions.single() as WhereCondition.RelationshipCondition

        assertEquals("mentions", rel.relationshipName)
        assertFalse(rel.negate)
        val target = rel.targetConditions.single() as WhereCondition.PropertyCondition
        assertEquals("mentions.resolvedId", target.propertyPath)
        assertEquals("ent-1", target.value)
    }

    @Test
    fun `none builds a negated relationship condition`() {
        val conditions = conditionsOf { where { query.mentions.none { resolvedId eq "ent-1" } } }
        val rel = conditions.single() as WhereCondition.RelationshipCondition
        assertEquals("mentions", rel.relationshipName)
        assertTrue(rel.negate)
    }

    @Test
    fun `any with several conditions correlates them to one related node`() {
        val conditions = conditionsOf { where { query.mentions.any { resolvedId eq "ent-1"; role eq "SUBJECT" } } }
        val rel = conditions.single() as WhereCondition.RelationshipCondition
        assertEquals(2, rel.targetConditions.size)
        assertFalse(rel.negate)
    }

    @Test
    fun `any with inList renders an IN predicate`() {
        val conditions = conditionsOf { where { query.mentions.any { resolvedId inList listOf("a", "b") } } }
        val rel = conditions.single() as WhereCondition.RelationshipCondition
        val target = rel.targetConditions.single() as WhereCondition.PropertyCondition
        assertEquals(ComparisonOperator.IN, target.operator)
    }

    // ----- Generator renders per grammar -----

    @Test
    fun `Neo4j renders any as EXISTS and none as NOT EXISTS`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())

        val any = CypherGenerator.buildWhereClause(
            conditionsOf { where { query.mentions.any { resolvedId eq "ent-1" } } }, viewModel, grammar,
        ).whereClause!!
        assertTrue(any.contains("EXISTS {"))
        assertTrue(any.contains("(proposition)-[:HAS_MENTION]->(mentions)"))
        assertTrue(any.contains("mentions.resolvedId ="))
        assertFalse(any.trimStart().startsWith("NOT "))

        val none = CypherGenerator.buildWhereClause(
            conditionsOf { where { query.mentions.none { resolvedId eq "ent-1" } } }, viewModel, grammar,
        ).whereClause!!
        assertTrue(none.startsWith("NOT (EXISTS {"))
    }

    @Test
    fun `Memgraph renders any as size comprehension and none as its negation`() {
        val grammar = MemgraphGrammar(CallSubqueryEmitter())

        val any = CypherGenerator.buildWhereClause(
            conditionsOf { where { query.mentions.any { resolvedId eq "ent-1" } } }, viewModel, grammar,
        ).whereClause!!
        assertTrue(any.contains("size(["))
        assertTrue(any.trimEnd().endsWith("> 0"))

        val none = CypherGenerator.buildWhereClause(
            conditionsOf { where { query.mentions.none { resolvedId eq "ent-1" } } }, viewModel, grammar,
        ).whereClause!!
        assertTrue(none.startsWith("NOT (size(["))
    }
}