package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.junit.jupiter.api.Test
import sample.mapped.view.RaisedAndAssignedIssue
import sample.proposition.PropositionView
import kotlin.test.assertEquals

/**
 * A `where` is rendered by [CypherGenerator.buildWhereClause] and bound by
 * [CypherGenerator.extractBindings], each numbering the parameters for itself. Whatever a
 * relationship block holds — an `or`, a null check, a label, a nested relationship — the two must
 * arrive at the same names, and no two conditions at one.
 */
class ParameterIndexTest {

    private val proposition = GraphViewModel.from(PropositionView::class.java)
    private val issue = GraphViewModel.from(RaisedAndAssignedIssue::class.java)
    private val grammars = listOf(CypherDialect.NEO4J_5, CypherDialect.FALKORDB, CypherDialect.MEMGRAPH).map { it.grammar() }

    private fun eq(path: String, value: Any) = WhereCondition.PropertyCondition(path, ComparisonOperator.EQUALS, value)
    private fun isNull(path: String) = WhereCondition.PropertyCondition(path, ComparisonOperator.IS_NULL, null)

    /** The parameters a rendering of [conditions] names, which must be the ones bound, each once. */
    private fun assertAligned(conditions: List<WhereCondition>, viewModel: GraphViewModel, projected: Boolean = false) {
        val bindings = CypherGenerator.extractBindings(conditions, viewModel)
        val values = conditions.flatMap(::boundValues)
        assertEquals(values.size, bindings.size, "one parameter for each bound condition: $bindings")
        grammars(projected).forEach { grammar ->
            val result = CypherGenerator.buildWhereClause(conditions, viewModel, grammar, projectedCollectionMode = projected)
            val rendered = (result.prologs + result.whereClause).joinToString("\n")
            val named = Regex("\\$(param_\\w+)").findAll(rendered).map { it.groupValues[1] }.toList()
            assertEquals(bindings.keys.sorted(), named.sorted(), "${grammar::class.simpleName}: $rendered")
        }
    }

    private fun grammars(projected: Boolean): List<CypherGrammar> = if (projected) grammars.take(1) else grammars

    private fun boundValues(condition: WhereCondition): List<Any?> = when (condition) {
        is WhereCondition.PropertyCondition ->
            if (condition.operator == ComparisonOperator.IS_NULL || condition.operator == ComparisonOperator.IS_NOT_NULL) emptyList()
            else listOf(condition.value)
        is WhereCondition.RelationshipCondition -> condition.targetConditions.flatMap(::boundValues)
        is WhereCondition.OrCondition -> condition.conditions.flatMap(::boundValues)
        is WhereCondition.NotCondition -> condition.conditions.flatMap(::boundValues)
        is WhereCondition.ListMembershipCondition -> listOf(condition.value)
        is WhereCondition.AnyLabelCondition -> listOf(condition.labels)
        is WhereCondition.LabelCondition -> emptyList()
    }

    @Test
    fun `an or inside a relationship block takes an index for each of its parameters`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                "mentions",
                listOf(
                    WhereCondition.OrCondition(listOf(eq("mentions.role", "SUBJECT"), eq("mentions.role", "OBJECT"))),
                    eq("mentions.resolvedId", "ent-1"),
                ),
            ),
            eq("proposition.status", "active"),
            eq("proposition.contextId", "ctx-a"),
        )

        assertAligned(conditions, proposition)
        assertEquals(
            mapOf(
                "param_mentions_role_0" to "SUBJECT",
                "param_mentions_role_1" to "OBJECT",
                "param_mentions_resolvedId_2" to "ent-1",
                "param_proposition_status_3" to "active",
                "param_proposition_contextId_4" to "ctx-a",
            ),
            CypherGenerator.extractBindings(conditions, proposition),
        )
    }

    @Test
    fun `a null check inside a relationship block takes no index`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition("mentions", listOf(isNull("mentions.resolvedId"), eq("mentions.role", "SUBJECT"))),
            eq("proposition.status", "active"),
        )

        assertAligned(conditions, proposition)
        assertAligned(conditions, proposition, projected = true)
    }

    @Test
    fun `a label inside a relationship block takes no index`() {
        val conditions = listOf(
            WhereCondition.LabelCondition("mentions", listOf("Mention")),
            WhereCondition.RelationshipCondition(
                "mentions",
                listOf(WhereCondition.LabelCondition("mentions", listOf("Mention")), eq("mentions.role", "SUBJECT")),
            ),
            eq("proposition.status", "active"),
        )

        assertAligned(conditions, proposition)
    }

    @Test
    fun `a nested relationship named before a direct property keeps the index of its place`() {
        val conditions = listOf(
            eq("raisedBy_worksFor.name", "Acme"),
            eq("raisedBy.name", "Ada"),
            isNull("raisedBy.bio"),
            eq("raisedBy_worksFor.name", "Initech"),
            eq("issue.state", "open"),
        )

        assertAligned(conditions, issue)
    }

    @Test
    fun `an or of relationship blocks takes an index for each parameter of each`() {
        val conditions = listOf(
            WhereCondition.OrCondition(
                listOf(
                    WhereCondition.RelationshipCondition("mentions", listOf(isNull("mentions.resolvedId"), eq("mentions.role", "SUBJECT"))),
                    WhereCondition.RelationshipCondition(
                        "mentions",
                        listOf(WhereCondition.OrCondition(listOf(eq("mentions.role", "A"), eq("mentions.role", "B"))), eq("mentions.id", "m1")),
                    ),
                    eq("proposition.level", 1),
                )
            ),
            eq("proposition.status", "active"),
        )

        assertAligned(conditions, proposition)
    }
}
