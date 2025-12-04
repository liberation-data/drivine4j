package org.drivine.query.dsl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for context parameters syntax - the cleanest DSL syntax powered by Kotlin 2.2+
 *
 * With context parameters, conditions automatically register themselves without any
 * explicit this(), +, or other wrapper syntax!
 */
class ContextParametersSyntaxTest {

    @Test
    fun `context parameters allow natural syntax without this()`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        // The dream syntax - completely natural!
        spec.where {
            query.name eq "Alice"
            query.age gt 18
            query.status eq "active"
        }

        assertEquals(3, spec.conditions.size)

        val condition1 = spec.conditions[0] as WhereCondition.PropertyCondition
        assertEquals("test.name", condition1.propertyPath)
        assertEquals(ComparisonOperator.EQUALS, condition1.operator)
        assertEquals("Alice", condition1.value)

        val condition2 = spec.conditions[1] as WhereCondition.PropertyCondition
        assertEquals("test.age", condition2.propertyPath)
        assertEquals(ComparisonOperator.GREATER_THAN, condition2.operator)
        assertEquals(18, condition2.value)

        val condition3 = spec.conditions[2] as WhereCondition.PropertyCondition
        assertEquals("test.status", condition3.propertyPath)
        assertEquals(ComparisonOperator.EQUALS, condition3.operator)
        assertEquals("active", condition3.value)
    }

    @Test
    fun `context parameters work with string operations`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            query.name.contains("Alice")
            query.name.startsWith("A")
            query.status.endsWith("active")
        }

        assertEquals(3, spec.conditions.size)

        val condition1 = spec.conditions[0] as WhereCondition.PropertyCondition
        assertEquals(ComparisonOperator.CONTAINS, condition1.operator)

        val condition2 = spec.conditions[1] as WhereCondition.PropertyCondition
        assertEquals(ComparisonOperator.STARTS_WITH, condition2.operator)

        val condition3 = spec.conditions[2] as WhereCondition.PropertyCondition
        assertEquals(ComparisonOperator.ENDS_WITH, condition3.operator)
    }

    @Test
    fun `context parameters work with anyOf blocks`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            query.age gt 18  // AND
            anyOf {
                query.status eq "open"
                query.status eq "reopened"
            }
        }

        assertEquals(2, spec.conditions.size)

        val ageCondition = spec.conditions[0] as WhereCondition.PropertyCondition
        assertEquals("test.age", ageCondition.propertyPath)

        val orCondition = spec.conditions[1] as WhereCondition.OrCondition
        assertEquals(2, orCondition.conditions.size)
    }

    @Test
    fun `context parameters work with all comparison operators`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            query.age eq 18
            query.age neq 17
            query.age gt 16
            query.age gte 17
            query.age lt 20
            query.age lte 19
        }

        assertEquals(6, spec.conditions.size)

        assertEquals(ComparisonOperator.EQUALS, (spec.conditions[0] as WhereCondition.PropertyCondition).operator)
        assertEquals(ComparisonOperator.NOT_EQUALS, (spec.conditions[1] as WhereCondition.PropertyCondition).operator)
        assertEquals(ComparisonOperator.GREATER_THAN, (spec.conditions[2] as WhereCondition.PropertyCondition).operator)
        assertEquals(ComparisonOperator.GREATER_THAN_OR_EQUAL, (spec.conditions[3] as WhereCondition.PropertyCondition).operator)
        assertEquals(ComparisonOperator.LESS_THAN, (spec.conditions[4] as WhereCondition.PropertyCondition).operator)
        assertEquals(ComparisonOperator.LESS_THAN_OR_EQUAL, (spec.conditions[5] as WhereCondition.PropertyCondition).operator)
    }

    @Test
    fun `context parameters work with IN operator`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            query.status `in` listOf("open", "closed", "pending")
        }

        assertEquals(1, spec.conditions.size)
        val condition = spec.conditions[0] as WhereCondition.PropertyCondition
        assertEquals(ComparisonOperator.IN, condition.operator)
    }

    @Test
    fun `nested anyOf blocks work with context parameters`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            anyOf {
                query.status eq "open"
                anyOf {
                    query.age gt 18
                    query.name eq "Alice"
                }
            }
        }

        assertEquals(1, spec.conditions.size)
        val outerOr = spec.conditions[0] as WhereCondition.OrCondition
        assertEquals(2, outerOr.conditions.size)

        // Second condition should be the nested OR
        val nestedOr = outerOr.conditions[1] as WhereCondition.OrCondition
        assertEquals(2, nestedOr.conditions.size)
    }

    @Test
    fun `context parameters work with orderBy`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.orderBy {
            query.age.desc()
            query.name.asc()
        }

        assertEquals(2, spec.orders.size)

        val order1 = spec.orders[0]
        assertEquals("test.age", order1.propertyPath)
        assertEquals(OrderDirection.DESC, order1.direction)

        val order2 = spec.orders[1]
        assertEquals("test.name", order2.propertyPath)
        assertEquals(OrderDirection.ASC, order2.direction)
    }

    @Test
    fun `context parameters work with both where and orderBy together`() {
        val queryObject = TestQuery()
        val spec = GraphQuerySpec(queryObject)

        spec.where {
            query.status eq "active"
            query.age gt 18
        }

        spec.orderBy {
            query.age.desc()
        }

        assertEquals(2, spec.conditions.size)
        assertEquals(1, spec.orders.size)
    }

    // Test helper classes
    class TestQuery {
        val name = StringPropertyReference("test", "name")
        val age = PropertyReference<Int>("test", "age")
        val status = StringPropertyReference("test", "status")
    }
}