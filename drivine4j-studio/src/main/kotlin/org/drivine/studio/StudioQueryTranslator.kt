package org.drivine.studio

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.ComparisonOperator.*
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.WhereCondition

/** A [StudioQuery] checked against the catalogue and turned into what the query DSL would have built. */
data class TranslatedQuery(
    val type: Class<*>,
    val where: List<WhereCondition>,
    val orderBy: List<OrderSpec>,
    val limit: Int,
    val skip: Int?,
)

/**
 * Turns a [StudioQuery] into the conditions and orders the query DSL builds. Every name is looked up
 * in the catalogue and every value is converted to its property's type, so nothing the browser sends
 * reaches a statement except as a known property name or a bound parameter.
 */
class StudioQueryTranslator(
    private val catalog: StudioCatalog,
    private val objectMapper: ObjectMapper,
    private val defaultLimit: Int = 50,
    private val maxLimit: Int = 500,
) {
    fun translate(query: StudioQuery): TranslatedQuery {
        val view = catalog.view(query.type)
        val fragment = catalog.fragment(query.type)
        val root = when {
            view != null -> Scope(view.root.field, fragmentOf(view.root.fragment))
            fragment != null -> Scope(StudioCatalog.FRAGMENT_ALIAS, fragment)
            else -> throw StudioQueryException("${query.type} is not a view or fragment in the catalogue")
        }
        val limit = query.limit ?: defaultLimit
        if (limit !in 1..maxLimit) throw StudioQueryException("limit must be between 1 and $maxLimit, was $limit")
        if (query.skip != null && query.skip < 0) throw StudioQueryException("skip must be 0 or more, was ${query.skip}")

        return TranslatedQuery(
            type = Class.forName(query.type, true, Thread.currentThread().contextClassLoader),
            where = query.where.map { condition(it, root, view) },
            orderBy = query.orderBy.map { OrderSpec("${root.alias}.${root.property(it.property).name}", it.direction) },
            limit = limit,
            skip = query.skip,
        )
    }

    private fun condition(condition: StudioCondition, scope: Scope, view: ViewDescriptor?): WhereCondition =
        when (condition) {
            is StudioCondition.Property -> property(condition, scope)
            is StudioCondition.AnyOf -> WhereCondition.OrCondition(nonEmpty("anyOf", condition.conditions).map { condition(it, scope, view) })
            is StudioCondition.Not -> WhereCondition.NotCondition(nonEmpty("not", condition.conditions).map { condition(it, scope, view) })
            is StudioCondition.Related -> {
                if (view == null) throw StudioQueryException("A condition on '${condition.relationship}' needs a view: a fragment has no relationships")
                val relationship = view.relationships.firstOrNull { it.field == condition.relationship }
                    ?: throw StudioQueryException("${view.name} has no relationship '${condition.relationship}'. It has: ${view.relationships.joinToString { it.field }}")
                val target = relationship.filterOn
                    ?: throw StudioQueryException("The studio cannot yet filter on '${relationship.field}'")
                val inner = Scope(relationship.field, fragmentOf(target))
                // Inside a relationship the scope is the related node, and it has no relationships of its own here.
                WhereCondition.RelationshipCondition(
                    relationshipName = relationship.field,
                    targetConditions = nonEmpty(relationship.field, condition.conditions).map { condition(it, inner, null) },
                    negate = condition.none,
                )
            }
        }

    private fun property(condition: StudioCondition.Property, scope: Scope): WhereCondition {
        val property = scope.property(condition.property)
        val path = "${scope.alias}.${property.name}"
        val value: Any? = when (condition.op) {
            IS_NULL, IS_NOT_NULL -> null
            IN, NOT_IN -> {
                val values = condition.value as? Collection<*>
                    ?: throw StudioQueryException("${property.name} ${condition.op} needs a list of values")
                values.map { convert(it, property) }
            }
            CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, CONTAINS_IGNORE_CASE, EQUALS_IGNORE_CASE -> {
                if (property.javaType != String::class.java.name) {
                    throw StudioQueryException("${condition.op} is for text; ${property.name} is ${property.type}")
                }
                condition.value as? String ?: throw StudioQueryException("${property.name} ${condition.op} needs text")
            }
            // The value is an element of the list the property holds, not a value of the property's own type.
            HAS_ELEMENT -> condition.value ?: throw StudioQueryException("${property.name} HAS_ELEMENT needs a value")
            EQUALS, NOT_EQUALS -> condition.value?.let { convert(it, property) }
            GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                convert(condition.value ?: throw StudioQueryException("${property.name} ${condition.op} needs a value"), property)
        }
        return WhereCondition.PropertyCondition(path, condition.op, value)
    }

    private fun convert(value: Any?, property: PropertyDescriptor): Any? {
        if (value == null) return null
        val type = Class.forName(property.javaType.let { PRIMITIVES[it] ?: it }, true, Thread.currentThread().contextClassLoader)
        return try {
            objectMapper.convertValue(value, type)
        } catch (e: IllegalArgumentException) {
            throw StudioQueryException("${property.name} is ${property.type}; '$value' is not one")
        }
    }

    private fun fragmentOf(className: String): FragmentDescriptor =
        catalog.fragment(className) ?: throw StudioQueryException("$className is not in the catalogue")

    private fun <T> nonEmpty(what: String, conditions: List<T>): List<T> =
        conditions.ifEmpty { throw StudioQueryException("'$what' needs at least one condition") }

    private class Scope(val alias: String, val fragment: FragmentDescriptor) {
        fun property(name: String): PropertyDescriptor =
            fragment.properties.firstOrNull { it.name == name }
                ?: throw StudioQueryException("${fragment.name} has no property '$name'. It has: ${fragment.properties.joinToString { it.name }}")
    }

    private companion object {
        val PRIMITIVES = mapOf(
            "long" to "java.lang.Long", "int" to "java.lang.Integer", "double" to "java.lang.Double",
            "float" to "java.lang.Float", "boolean" to "java.lang.Boolean", "short" to "java.lang.Short",
            "byte" to "java.lang.Byte", "char" to "java.lang.Character",
        )
    }
}
