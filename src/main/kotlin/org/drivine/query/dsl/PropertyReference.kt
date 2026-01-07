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

    /**
     * Java-friendly instanceOf filter for polymorphic type filtering.
     *
     * Filters results to only include nodes that have all labels defined
     * in the @NodeFragment annotation of the given class.
     *
     * Example (Java):
     * ```java
     * graphObjectManager.query(GuideUserWithPolymorphicWebUser.class)
     *     .filterWith(GuideUserWithPolymorphicWebUserQueryDsl.class)
     *     .where(dsl -> dsl.getWebUser().instanceOf(AnonymousWebUser.class))
     *     .loadAll();
     * ```
     *
     * @param clazz The @NodeFragment annotated class to filter by
     * @return PropertyConditionBuilder for use with JavaQueryBuilder
     * @throws IllegalArgumentException if the class doesn't have a @NodeFragment annotation
     */
    fun instanceOf(clazz: Class<*>): PropertyConditionBuilder {
        val labels = extractLabelsFromNodeFragment(clazz)
        require(labels.isNotEmpty()) {
            "Type ${clazz.simpleName} does not have a @NodeFragment annotation with labels. " +
            "instanceOf() can only be used with types annotated with @NodeFragment."
        }
        return PropertyConditionBuilder(
            WhereCondition.LabelCondition(
                alias = this.nodeAlias,
                labels = labels
            )
        )
    }
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
 * **Kotlin usage** (with context parameters - conditions auto-register):
 * ```kotlin
 * where {
 *     issue.state eq "open"
 * }
 * ```
 *
 * **Java usage** (methods return PropertyConditionBuilder):
 * ```java
 * graphObjectManager.query(PersonCareer.class)
 *     .where(q -> q.person().name().eq("Alice"))
 *     .loadAll();
 * ```
 */
open class PropertyReference<T>(
    internal val alias: String,
    internal val propertyName: String
) {
    // ==================== Java-friendly methods (return PropertyConditionBuilder) ====================
    // These methods return a builder that can be used with JavaQueryBuilder.
    // They have different signatures than the context parameter versions (Unit vs PropertyConditionBuilder).

    /**
     * Equality condition: property = value
     * Returns a PropertyConditionBuilder for use with Java query builder.
     */
    fun eq(value: T?): PropertyConditionBuilder {
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
    fun neq(value: T?): PropertyConditionBuilder {
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
    fun gt(value: T): PropertyConditionBuilder {
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
    fun gte(value: T): PropertyConditionBuilder {
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
    fun lt(value: T): PropertyConditionBuilder {
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
    fun lte(value: T): PropertyConditionBuilder {
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
    fun isIn(values: Collection<T>): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IN,
                value = values
            )
        )
    }

    /**
     * IS NULL condition: property IS NULL
     */
    fun isNull(): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NULL,
                value = null
            )
        )
    }

    /**
     * IS NOT NULL condition: property IS NOT NULL
     */
    fun isNotNull(): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$alias.$propertyName",
                operator = ComparisonOperator.IS_NOT_NULL,
                value = null
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

    // ==================== Kotlin context parameter methods ====================
    // These methods auto-register conditions when used within a where/orderBy block.
    // They have different signatures (take WhereBuilder context, return Unit).

    /**
     * Equality condition with context parameter (Kotlin DSL).
     * Automatically registers itself when used in a where block.
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("eqContext")
    infix fun eq(value: T?) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.EQUALS, value))
    }

    /**
     * Not equals condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("neqContext")
    infix fun neq(value: T?) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.NOT_EQUALS, value))
    }

    /**
     * Greater than condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("gtContext")
    infix fun gt(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.GREATER_THAN, value))
    }

    /**
     * Greater than or equal condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("gteContext")
    infix fun gte(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.GREATER_THAN_OR_EQUAL, value))
    }

    /**
     * Less than condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("ltContext")
    infix fun lt(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.LESS_THAN, value))
    }

    /**
     * Less than or equal condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("lteContext")
    infix fun lte(value: T) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.LESS_THAN_OR_EQUAL, value))
    }

    /**
     * IN condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("inContext")
    infix fun `in`(values: Collection<T>) {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IN, values))
    }

    /**
     * IS NULL condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("isNullContext")
    fun isNull() {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IS_NULL, null))
    }

    /**
     * IS NOT NULL condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("isNotNullContext")
    fun isNotNull() {
        builder.conditions.add(makePropertyCondition(ComparisonOperator.IS_NOT_NULL, null))
    }

    /**
     * Ascending order with context parameter (Kotlin DSL).
     */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("ascContext")
    fun asc() {
        builder.orders.add(OrderSpec("$alias.$propertyName", OrderDirection.ASC))
    }

    /**
     * Descending order with context parameter (Kotlin DSL).
     */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("descContext")
    fun desc() {
        builder.orders.add(OrderSpec("$alias.$propertyName", OrderDirection.DESC))
    }

    // Helper to create PropertyCondition
    private fun makePropertyCondition(operator: ComparisonOperator, value: Any?): WhereCondition.PropertyCondition {
        return WhereCondition.PropertyCondition(
            propertyPath = "$alias.$propertyName",
            operator = operator,
            value = value
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

    // ==================== Java-friendly methods ====================

    /**
     * CONTAINS condition: property CONTAINS value
     * Returns PropertyConditionBuilder for Java usage.
     */
    fun contains(value: String): PropertyConditionBuilder {
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
    fun startsWith(value: String): PropertyConditionBuilder {
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
    fun endsWith(value: String): PropertyConditionBuilder {
        return PropertyConditionBuilder(
            WhereCondition.PropertyCondition(
                propertyPath = "$stringAlias.$stringPropertyName",
                operator = ComparisonOperator.ENDS_WITH,
                value = value
            )
        )
    }

    // ==================== Kotlin context parameter methods ====================

    /**
     * CONTAINS condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("containsContext")
    infix fun contains(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.CONTAINS,
            value = value
        ))
    }

    /**
     * STARTS WITH condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("startsWithContext")
    infix fun startsWith(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.STARTS_WITH,
            value = value
        ))
    }

    /**
     * ENDS WITH condition with context parameter (Kotlin DSL).
     */
    context(builder: WhereBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("endsWithContext")
    infix fun endsWith(value: String) {
        builder.conditions.add(WhereCondition.PropertyCondition(
            propertyPath = "$stringAlias.$stringPropertyName",
            operator = ComparisonOperator.ENDS_WITH,
            value = value
        ))
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