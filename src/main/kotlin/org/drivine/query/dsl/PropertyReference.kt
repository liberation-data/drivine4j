package org.drivine.query.dsl

import org.drivine.annotation.NodeFragment

/**
 * Base interface for generated Properties classes.
 * Exposes the node alias for use in type-based filtering (instanceOf).
 *
 * All generated XxxProperties classes implement this interface,
 * enabling the DSL to filter by node type:
 *
 * ```kotlin
 * where {
 *     webUser.instanceOf<AnonymousWebUser>()
 * }
 * ```
 */
interface NodeReference {
    /**
     * The Cypher alias for this node reference.
     * For example, "webUser" in a relationship target, or "core" for a root fragment.
     */
    val nodeAlias: String
}

/**
 * Extension function to filter by node type using the @NodeFragment annotation.
 *
 * Example:
 * ```kotlin
 * where {
 *     webUser.instanceOf<AnonymousWebUser>()  // Filters to only AnonymousWebUser
 * }
 * ```
 *
 * This extracts the labels from the @NodeFragment annotation on the type
 * and generates a Cypher label check: `WHERE webUser:WebUser:Anonymous`
 *
 * @param T The NodeFragment subtype to filter by
 */
context(builder: WhereBuilder<*>)
inline fun <reified T : Any> NodeReference.instanceOf() {
    val labels = extractLabelsFromNodeFragment(T::class.java)
    require(labels.isNotEmpty()) {
        "Type ${T::class.simpleName} does not have a @NodeFragment annotation with labels. " +
        "instanceOf() can only be used with types annotated with @NodeFragment."
    }
    builder.conditions.add(
        WhereCondition.LabelCondition(
            alias = this.nodeAlias,
            labels = labels
        )
    )
}

/**
 * Extracts labels from a @NodeFragment annotation on the given class.
 *
 * @param clazz The class to extract labels from
 * @return List of labels, or empty list if no @NodeFragment annotation found
 */
fun extractLabelsFromNodeFragment(clazz: Class<*>): List<String> {
    val annotation = clazz.getAnnotation(NodeFragment::class.java)
    return annotation?.labels?.toList() ?: emptyList()
}

/**
 * Represents a reference to a property in a GraphFragment or GraphView.
 * Enables type-safe property access in the query DSL.
 *
 * Example: issue.state where "issue" is the alias and "state" is the property name.
 *
 * With context parameters (Kotlin 2.2+), conditions are automatically registered
 * when used within a where block, eliminating the need for this() or other wrappers.
 */
open class PropertyReference<T>(
    internal val alias: String,
    internal val propertyName: String
) {
    /**
     * Equality condition: property = value
     * Automatically registers itself when used in a where block (via context parameters).
     */
    context(builder: WhereBuilder<*>)
    infix fun eq(value: T?) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun neq(value: T?) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun gt(value: T) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun gte(value: T) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun lt(value: T) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun lte(value: T) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun `in`(values: Collection<T>) {
        builder.conditions.add(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IN,
                value = values
            )
        )
    }

    /**
     * IS NULL condition: property IS NULL
     * Checks if the property is null or absent in the graph.
     */
    context(builder: WhereBuilder<*>)
    fun isNull() {
        builder.conditions.add(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NULL,
                value = null
            )
        )
    }

    /**
     * IS NOT NULL condition: property IS NOT NULL
     * Checks if the property exists and has a non-null value.
     */
    context(builder: WhereBuilder<*>)
    fun isNotNull() {
        builder.conditions.add(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NOT_NULL,
                value = null
            )
        )
    }

    /**
     * Ascending order specification.
     * When used in an orderBy block with context parameters, automatically registers itself.
     */
    context(builder: OrderBuilder<*>)
    fun asc() {
        builder.orders.add(
            OrderSpec(
                propertyPath = "$alias.$propertyName",
                direction = OrderDirection.ASC
            )
        )
    }

    /**
     * Descending order specification.
     * When used in an orderBy block with context parameters, automatically registers itself.
     */
    context(builder: OrderBuilder<*>)
    fun desc() {
        builder.orders.add(
            OrderSpec(
                propertyPath = "$alias.$propertyName",
                direction = OrderDirection.DESC
            )
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
    context(builder: WhereBuilder<*>)
    infix fun contains(value: String) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun startsWith(value: String) {
        builder.conditions.add(
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
    context(builder: WhereBuilder<*>)
    infix fun endsWith(value: String) {
        builder.conditions.add(
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