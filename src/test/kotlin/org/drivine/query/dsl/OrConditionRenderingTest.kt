package org.drivine.query.dsl

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.mapped.fragment.Issue
import sample.mapped.fragment.Person
import sample.mapped.view.RaisedAndAssignedIssue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A relationship field whose own name holds an underscore, which also parts a nested alias from its parent. */
@GraphView
data class IssueReviewedBy(
    @Root val issue: Issue,

    @GraphRelationship(type = "REVIEWED_BY", direction = Direction.OUTGOING)
    val reviewed_by: List<Person>,
)

/**
 * The branches of an `or` are rendered by the loop that renders a `where` and a `not`: a path is read
 * as a relationship's by the same rule wherever the condition stands.
 */
class OrConditionRenderingTest {

    private val grammar = CypherDialect.NEO4J_5.grammar()

    private fun eq(path: String, value: Any) = WhereCondition.PropertyCondition(path, ComparisonOperator.EQUALS, value)
    private fun isNull(path: String) = WhereCondition.PropertyCondition(path, ComparisonOperator.IS_NULL, null)

    private fun where(conditions: List<WhereCondition>, view: Class<*>): String =
        CypherGenerator.buildWhereClause(conditions, GraphViewModel.from(view), grammar).whereClause!!

    @Test
    fun `a relationship named with an underscore is a relationship inside an or as it is outside one`() {
        val alone = where(listOf(eq("reviewed_by.name", "Ada")), IssueReviewedBy::class.java)
        val either = where(
            listOf(WhereCondition.OrCondition(listOf(eq("reviewed_by.name", "Ada"), eq("issue.state", "open")))),
            IssueReviewedBy::class.java,
        )

        assertTrue("REVIEWED_BY" in alone, alone)
        assertTrue("REVIEWED_BY" in either, either)
        // The target is no variable of the statement: it is not compared as the root's property would be.
        assertFalse(Regex("(?<![\\w(])reviewed_by\\.name = ").containsMatchIn(either.substringBefore("EXISTS")), either)
    }

    @Test
    fun `each relationship branch of an or is a predicate of its own`() {
        val either = where(
            listOf(WhereCondition.OrCondition(listOf(eq("assignedTo.name", "Ada"), eq("assignedTo.name", "Grace")))),
            RaisedAndAssignedIssue::class.java,
        )

        assertEquals(2, Regex("ASSIGNED_TO").findAll(either).count(), either)
        assertTrue(either.startsWith("(") && either.endsWith(")") && " OR " in either, either)
    }

    @Test
    fun `a null check in an or takes no parameter index from the branch after it`() {
        val conditions = listOf(WhereCondition.OrCondition(listOf(isNull("issue.title"), eq("issue.state", "open"))))
        val either = where(conditions, RaisedAndAssignedIssue::class.java)
        val bindings = CypherGenerator.extractBindings(conditions, GraphViewModel.from(RaisedAndAssignedIssue::class.java))

        assertEquals(1, bindings.size)
        assertTrue("\$${bindings.keys.single()}" in either, either)
    }
}
