package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl

/**
 * 0.0.56 — the `hasItem` list-membership predicate: a caller value contained in a list-valued node
 * property, rendering `$param IN <alias>.<property>` (the mirror of `inList`). Asserts the DSL builds
 * a [WhereCondition.ListMembershipCondition], the generator renders the `$param IN path` shape and
 * binds the value, it composes (AND) with other predicates, and it renders identically in the
 * vector-search path's projected-collection mode (the root is a map, so it's plain property access).
 */
class ListMembershipDslTest {

    private val viewModel = GraphViewModel.from(PropositionView::class.java)
    private val neo4j = Neo4j5Grammar(ApocSortMapsEmitter())

    private fun conditionsOf(spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): List<WhereCondition> {
        val querySpec = GraphQuerySpec(PropositionViewQueryDsl.INSTANCE)
        querySpec.spec()
        return querySpec.conditions
    }

    private fun whereOf(
        projectedCollectionMode: Boolean = false,
        spec: GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit,
    ): String = CypherGenerator.buildWhereClause(conditionsOf(spec), viewModel, neo4j, projectedCollectionMode).whereClause!!

    // ----- DSL builds the right condition -----

    @Test
    fun `hasItem builds a ListMembershipCondition on the list property`() {
        val condition = conditionsOf { where { query.proposition.grounding hasItem "chunk-1" } }
            .single() as WhereCondition.ListMembershipCondition
        assertEquals("proposition.grounding", condition.propertyPath)
        assertEquals("chunk-1", condition.value)
    }

    // ----- Generator renders `$param IN path` and binds the value -----

    @Test
    fun `renders value IN list-property with the value on the left`() {
        val where = whereOf { where { query.proposition.grounding hasItem "chunk-1" } }
        // `$param IN proposition.grounding` — caller value on the left, list-property on the right.
        assertTrue(Regex("""\$\w+ IN proposition\.grounding""").containsMatchIn(where), where)

        val bindings = CypherGenerator.extractBindings(
            conditionsOf { where { query.proposition.grounding hasItem "chunk-1" } }, viewModel,
        )
        assertEquals(listOf("chunk-1"), bindings.values.toList())
    }

    // ----- Composition -----

    @Test
    fun `composes (AND) with a property predicate`() {
        val where = whereOf {
            where {
                query.proposition.contextId eq "c"
                query.proposition.grounding hasItem "chunk-1"
            }
        }
        assertTrue(where.contains("proposition.contextId ="), where)
        assertTrue(where.contains(" AND "), where)
        assertTrue(where.contains("IN proposition.grounding"), where)
    }

    @Test
    fun `composes (AND) with a relationship any quantifier`() {
        val where = whereOf {
            where {
                query.proposition.grounding hasItem "chunk-1"
                query.mentions.any { resolvedId eq "ent-1" }
            }
        }
        assertTrue(where.contains("IN proposition.grounding"), where)
        assertTrue(where.contains("EXISTS {"), where)
        assertTrue(where.contains(" AND "), where)
    }

    // ----- Vector path: projected-collection mode renders the same scalar shape (no pattern) -----

    @Test
    fun `projected-collection mode renders the same value IN path (root is a map)`() {
        val where = whereOf(projectedCollectionMode = true) {
            where { query.proposition.grounding hasItem "chunk-1" }
        }
        assertTrue(Regex("""\$\w+ IN proposition\.grounding""").containsMatchIn(where), where)
        assertFalse(where.contains("EXISTS"), where)
    }
}