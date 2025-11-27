package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.Value

class TransformPostProcessor<S, T>(private val type: Class<T>) : ResultPostProcessor<S, T> {

    private val objectMapper = Neo4jObjectMapper.instance

    // Cache for subtype mappings to avoid repeated reflection
    private val subtypeMap: Map<String, Class<*>>? by lazy {
        buildSubtypeMap(type)
    }

    override fun apply(results: List<S>): List<T> {
        return results.map { result ->
            val data = when (result) {
                is MapValue -> result.asMap()
                is Value -> result.asObject()
                else -> result
            }

            // Try to determine concrete subtype based on Neo4j labels
            val targetType = determineConcreteType(data) ?: type

            @Suppress("UNCHECKED_CAST")
            objectMapper.convertValue(data, targetType) as T
        }
    }

    /**
     * Builds a map of subtype names to their classes based on @JsonSubTypes annotation.
     * Returns null if the type doesn't use Jackson polymorphic deserialization.
     */
    private fun buildSubtypeMap(clazz: Class<*>): Map<String, Class<*>>? {
        val jsonSubTypes = clazz.getAnnotation(JsonSubTypes::class.java) ?: return null

        return jsonSubTypes.value.associate { subType ->
            subType.name to subType.value.java
        }
    }

    /**
     * Determines the concrete type to deserialize to based on Neo4j labels or type property.
     * Returns null if no specific subtype can be determined.
     */
    private fun determineConcreteType(data: Any?): Class<*>? {
        if (subtypeMap == null || data !is Map<*, *>) {
            return null
        }

        val typeInfo = type.getAnnotation(JsonTypeInfo::class.java)

        // First, try to use the type property if it exists
        val typeProperty = typeInfo?.property ?: "type"
        val typeValue = data[typeProperty] as? String
        if (typeValue != null) {
            return subtypeMap?.get(typeValue)
        }

        // If no type property, try to match Neo4j labels
        val labels = data["labels"]
        if (labels is List<*>) {
            // Find the first label that matches a known subtype
            for (label in labels) {
                val labelStr = label as? String ?: continue
                val matchingType = subtypeMap?.get(labelStr)
                if (matchingType != null) {
                    return matchingType
                }
            }
        }

        return null
    }

    override fun toString(): String = "transform(${type.simpleName})"
}
