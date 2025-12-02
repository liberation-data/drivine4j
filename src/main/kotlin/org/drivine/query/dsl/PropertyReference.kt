package org.drivine.query.dsl

/**
 * Represents a reference to a property in a GraphFragment or GraphView.
 * Enables type-safe property access in the query DSL.
 *
 * Example: issue.state where "issue" is the alias and "state" is the property name.
 */
open class PropertyReference<T>(
    internal val alias: String,
    internal val propertyName: String
) {
    /**
     * Equality condition: property = value
     */
    infix fun eq(value: T?): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.EQUALS,
                value = value
            )
        )
    }

    /**
     * Not equals condition: property <> value
     */
    infix fun neq(value: T?): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.NOT_EQUALS,
                value = value
            )
        )
    }

    /**
     * Greater than condition: property > value
     */
    infix fun gt(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.GREATER_THAN,
                value = value
            )
        )
    }

    /**
     * Greater than or equal condition: property >= value
     */
    infix fun gte(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                value = value
            )
        )
    }

    /**
     * Less than condition: property < value
     */
    infix fun lt(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.LESS_THAN,
                value = value
            )
        )
    }

    /**
     * Less than or equal condition: property <= value
     */
    infix fun lte(value: T): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.LESS_THAN_OR_EQUAL,
                value = value
            )
        )
    }

    /**
     * IN condition: property IN [values]
     */
    infix fun `in`(values: Collection<T>): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IN,
                value = values
            )
        )
    }

    /**
     * Ascending order specification.
     */
    fun asc(): OrderSpec {
        return OrderSpec(
            propertyPath = "$alias.$propertyName",
            direction = OrderDirection.ASC
        )
    }

    /**
     * Descending order specification.
     */
    fun desc(): OrderSpec {
        return OrderSpec(
            propertyPath = "$alias.$propertyName",
            direction = OrderDirection.DESC
        )
    }
}

/**
 * String-specific property reference with additional string operations.
 */
class StringPropertyReference(
    private val stringAlias: String,
    private val stringPropertyName: String
) : PropertyReference<String>(stringAlias, stringPropertyName) {

    /**
     * CONTAINS condition: property CONTAINS value
     */
    infix fun contains(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.CONTAINS,
                value = value
            )
        )
    }

    /**
     * STARTS WITH condition: property STARTS WITH value
     */
    infix fun startsWith(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.STARTS_WITH,
                value = value
            )
        )
    }

    /**
     * ENDS WITH condition: property ENDS WITH value
     */
    infix fun endsWith(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.ENDS_WITH,
                value = value
            )
        )
    }
}

/**
 * Intermediate builder that holds a condition.
 * Automatically added to WhereBuilder when created in the DSL context.
 */
class PropertyConditionBuilder(internal val condition: WhereCondition) {
    /**
     * Allows chaining with `and` for explicit multiple conditions.
     */
    infix fun and(other: PropertyConditionBuilder): PropertyConditionChain {
        return PropertyConditionChain(listOf(this.condition, other.condition))
    }
}

/**
 * Represents a chain of conditions connected with AND.
 */
class PropertyConditionChain(internal val conditions: List<WhereCondition>) {
    infix fun and(other: PropertyConditionBuilder): PropertyConditionChain {
        return PropertyConditionChain(conditions + other.condition)
    }
}