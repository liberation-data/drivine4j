package org.drivine.studio

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.OrderDirection

/**
 * A `loadAll` as data: what the browser sends. It mirrors the query DSL — [where] is the `where { }`
 * block, [orderBy] the `orderBy { }` block — with properties named as the DSL names them, by their
 * Kotlin or Java name.
 */
data class StudioQuery(
    /** The fully qualified name of a `@GraphView` or `@NodeFragment` in the catalogue. */
    val type: String,
    /** AND-ed, as the statements of a `where { }` block are. */
    val where: List<StudioCondition> = emptyList(),
    val orderBy: List<StudioOrder> = emptyList(),
    val limit: Int? = null,
    val skip: Int? = null,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(StudioCondition.Property::class, name = "property"),
    JsonSubTypes.Type(StudioCondition.AnyOf::class, name = "anyOf"),
    JsonSubTypes.Type(StudioCondition.Not::class, name = "not"),
    JsonSubTypes.Type(StudioCondition.Related::class, name = "related"),
)
sealed interface StudioCondition {
    /** `property op value` on the node in scope: the root, or inside [Related] the related node. */
    data class Property(val property: String, val op: ComparisonOperator, val value: Any? = null) : StudioCondition

    /** `anyOf { }`: at least one holds. */
    data class AnyOf(val conditions: List<StudioCondition>) : StudioCondition

    /** `not { }`: the conditions, AND-ed, do not hold. */
    data class Not(val conditions: List<StudioCondition>) : StudioCondition

    /** `relationship.any { }` or, with [none], `relationship.none { }`. Only on a view. */
    data class Related(
        val relationship: String,
        val none: Boolean = false,
        val conditions: List<StudioCondition>,
    ) : StudioCondition
}

/** `property.asc()` or `property.desc()` on the root. */
data class StudioOrder(val property: String, val direction: OrderDirection = OrderDirection.ASC)

/** A [StudioQuery] the catalogue does not allow. Its message is for the person who wrote the query. */
class StudioQueryException(message: String) : RuntimeException(message)
