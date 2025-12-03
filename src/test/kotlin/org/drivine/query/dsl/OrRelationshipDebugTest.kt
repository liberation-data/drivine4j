package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.junit.jupiter.api.Test
import sample.mapped.view.RaisedAndAssignedIssue
import kotlin.test.assertTrue

/**
 * Debug test to understand OR conditions with relationships.
 */
class OrRelationshipDebugTest {

    @Test
    fun `debug OR with relationship properties`() {
        val viewModel = GraphViewModel.from(RaisedAndAssignedIssue::class.java)

        // Create an OR condition with two relationship property conditions
        val conditions = listOf(
            WhereCondition.OrCondition(
                listOf(
                    WhereCondition.PropertyCondition("assignedTo.name", ComparisonOperator.EQUALS, "Alice"),
                    WhereCondition.PropertyCondition("assignedTo.name", ComparisonOperator.EQUALS, "Bob")
                )
            )
        )

        val whereClause = CypherGenerator.buildWhereClause(conditions, viewModel)
        println("Generated WHERE clause: $whereClause")

        // Should generate: (EXISTS { ... } OR EXISTS { ... })
        assertTrue(whereClause.contains("EXISTS"), "Should contain EXISTS")
        assertTrue(whereClause.contains("OR"), "Should contain OR")

        val bindings = CypherGenerator.extractBindings(conditions)
        println("Bindings: $bindings")

        // Should have two parameters
        assertTrue(bindings.size == 2, "Should have 2 parameters, got ${bindings.size}")
    }
}