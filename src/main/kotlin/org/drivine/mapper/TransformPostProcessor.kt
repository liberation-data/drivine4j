package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.neo4j.driver.internal.value.MapValue
import org.neo4j.driver.Value
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

class TransformPostProcessor<S, T>(
    private val type: Class<T>,
    private val subtypeRegistry: SubtypeRegistry? = null
) : ResultPostProcessor<S, T> {

    private val objectMapper = Neo4jObjectMapper.instance

    // Cache for subtype mappings to avoid repeated reflection
    private val subtypeMap: Map<String, Class<*>>? by lazy {
        // First check the dynamic registry, then fall back to @JsonSubTypes annotation
        subtypeRegistry?.getSubtypes(type) ?: buildSubtypeMap(type)
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

            // For GraphViews with sealed class relationships, pre-convert nested objects
            // and then construct the parent with constructor injection
            if (hasPolymorphicRelationships(targetType)) {
                @Suppress("UNCHECKED_CAST")
                constructWithPolymorphicRelationships(data as Map<String, Any?>, targetType) as T
            } else {
                @Suppress("UNCHECKED_CAST")
                objectMapper.convertValue(data, targetType) as T
            }
        }
    }

    /**
     * Checks if the target type has any polymorphic (sealed class) relationship fields.
     */
    private fun hasPolymorphicRelationships(targetClass: Class<*>): Boolean {
        if (!targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)) {
            return false
        }
        val viewModel = org.drivine.model.GraphViewModel.from(targetClass)
        return viewModel.relationships.any { it.elementType.kotlin.isSealed }
    }

    /**
     * Constructs a GraphView object with polymorphic relationships properly resolved.
     * Uses Kotlin reflection to directly construct the data class with pre-converted objects.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> constructWithPolymorphicRelationships(data: Map<String, Any?>, targetClass: Class<R>): R {
        val viewModel = org.drivine.model.GraphViewModel.from(targetClass)
        val kotlinClass = targetClass.kotlin

        // Find the primary constructor
        val constructor = kotlinClass.primaryConstructor
            ?: throw IllegalArgumentException("Class ${targetClass.name} has no primary constructor")

        // Build parameter map for constructor
        val parameterValues = mutableMapOf<kotlin.reflect.KParameter, Any?>()

        constructor.parameters.forEach { param ->
            val paramName = param.name ?: return@forEach

            // Check if this is a relationship field with polymorphic type
            val rel = viewModel.relationships.find { it.fieldName == paramName }
            val fieldData = data[paramName]

            val value = if (rel != null && rel.elementType.kotlin.isSealed && fieldData != null) {
                // Pre-convert polymorphic relationship
                if (rel.isCollection && fieldData is List<*>) {
                    fieldData.map { item -> convertPolymorphicItem(item, rel.elementType) }
                } else {
                    convertPolymorphicItem(fieldData, rel.elementType)
                }
            } else if (fieldData != null) {
                // Convert non-polymorphic fields with Jackson
                val paramType = param.type.classifier as? kotlin.reflect.KClass<*>
                if (paramType != null) {
                    objectMapper.convertValue(fieldData, paramType.java)
                } else {
                    fieldData
                }
            } else {
                // Handle null values (may be nullable or have default)
                null
            }

            // Only add if value is not null or parameter is nullable
            if (value != null || param.type.isMarkedNullable) {
                parameterValues[param] = value
            }
        }

        return constructor.callBy(parameterValues)
    }

    /**
     * Converts a single polymorphic item by resolving its concrete type from labels.
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertPolymorphicItem(data: Any?, baseType: Class<*>): Any? {
        if (data !is Map<*, *>) {
            return data
        }

        val mapData = data as Map<String, Any?>
        val labels = mapData["labels"]

        if (labels is List<*>) {
            val labelStrings = labels.filterIsInstance<String>()
            val concreteType = resolveConcreteTypeByLabels(baseType, labelStrings)

            if (concreteType != null) {
                return objectMapper.convertValue(mapData, concreteType)
            }
        }

        // If can't resolve, try base type (will fail for sealed classes)
        return objectMapper.convertValue(mapData, baseType)
    }

    /**
     * Pre-processes a data map to add @type hints for polymorphic deserialization.
     * This enables Jackson to deserialize nested sealed classes correctly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun preprocessPolymorphicData(data: Any?, targetClass: Class<*>): Any? {
        if (data !is Map<*, *>) {
            return data
        }

        // Create a mutable copy
        val result = (data as Map<String, Any?>).toMutableMap()

        // Check if this class has sealed subclasses we need to resolve
        val isGraphView = targetClass.isAnnotationPresent(org.drivine.annotation.GraphView::class.java)

        if (isGraphView) {
            val viewModel = org.drivine.model.GraphViewModel.from(targetClass)

            // Process each relationship field
            viewModel.relationships.forEach { rel ->
                val fieldData = result[rel.fieldName]
                if (fieldData != null) {
                    val elementType = rel.elementType
                    val isSealed = elementType.kotlin.isSealed

                    if (rel.isCollection && fieldData is List<*>) {
                        // Process collection of relationship targets
                        result[rel.fieldName] = fieldData.map { item ->
                            preprocessNestedPolymorphic(item, elementType, isSealed)
                        }
                    } else if (fieldData is Map<*, *>) {
                        // Process single relationship target
                        result[rel.fieldName] = preprocessNestedPolymorphic(fieldData, elementType, isSealed)
                    }
                }
            }
        }

        return result
    }

    /**
     * Pre-processes a nested object to resolve its concrete type based on labels.
     * For sealed classes, directly converts the data to the concrete type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun preprocessNestedPolymorphic(data: Any?, elementType: Class<*>, isSealed: Boolean): Any? {
        if (data !is Map<*, *>) {
            return data
        }

        val mapData = data as Map<String, Any?>

        // If this is a sealed class, try to resolve and convert to the concrete type
        if (isSealed) {
            val labels = mapData["labels"]
            if (labels is List<*>) {
                val labelStrings = labels.filterIsInstance<String>()
                val concreteType = resolveConcreteTypeByLabels(elementType, labelStrings)

                if (concreteType != null && concreteType != elementType) {
                    // Pre-process nested data if the concrete type is also a GraphView
                    val processedData = preprocessPolymorphicData(mapData, concreteType)

                    // Directly convert to the concrete type
                    return objectMapper.convertValue(processedData, concreteType)
                }
            }
        }

        // If not a sealed class, recursively process nested data and convert
        val processedData = preprocessPolymorphicData(mapData, elementType)
        return objectMapper.convertValue(processedData, elementType)
    }

    /**
     * Resolves a concrete type for a sealed class based on Neo4j labels.
     * Uses composite key (sorted, comma-joined labels) to find the matching subtype.
     */
    private fun resolveConcreteTypeByLabels(baseType: Class<*>, labels: List<String>): Class<*>? {
        val compositeKey = SubtypeRegistry.labelsToKey(labels)

        // First try the subtype registry (covers all registered types)
        if (subtypeRegistry != null) {
            subtypeRegistry.resolveByLabels(baseType, labels)?.let { return it }
        }

        // For sealed classes, resolve directly from sealed subclasses
        val kotlinClass = baseType.kotlin
        if (kotlinClass.isSealed) {
            kotlinClass.sealedSubclasses.forEach { subclass ->
                val subclassJava = subclass.java
                val nodeFragment = subclassJava.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
                if (nodeFragment != null) {
                    val subLabels = nodeFragment.labels.toList()
                    val subCompositeKey = SubtypeRegistry.labelsToKey(subLabels)
                    if (subCompositeKey == compositeKey) {
                        return subclassJava
                    }
                }
            }
        }

        return null
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
     *
     * For label-based polymorphism, uses composite key matching (sorted, comma-joined labels)
     * to find the most specific subtype. For example, ["WebUser", "Anonymous"] matches
     * a subtype registered with key "Anonymous,WebUser" before falling back to individual labels.
     */
    private fun determineConcreteType(data: Any?): Class<*>? {
        if (data !is Map<*, *>) {
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
            @Suppress("UNCHECKED_CAST")
            val labelStrings = labels.filterIsInstance<String>()

            // Use SubtypeRegistry's composite label resolution if available
            if (subtypeRegistry != null) {
                subtypeRegistry.resolveByLabels(type, labelStrings)?.let { return it }
            }

            // Fall back to subtypeMap with composite key, then individual labels
            val localSubtypeMap = subtypeMap
            if (localSubtypeMap != null) {
                // Try composite key first (most specific match)
                val compositeKey = SubtypeRegistry.labelsToKey(labelStrings)
                localSubtypeMap[compositeKey]?.let { return it }

                // Fall back to individual label matching
                for (label in labelStrings) {
                    localSubtypeMap[label]?.let { return it }
                }
            }
        }

        return null
    }

    /**
     * Creates a new TransformPostProcessor with the given SubtypeRegistry.
     * Used internally by the ResultMapper to inject the registry at query time.
     */
    fun withSubtypeRegistry(registry: SubtypeRegistry): TransformPostProcessor<S, T> {
        return TransformPostProcessor(type, registry)
    }

    override fun toString(): String = "transform(${type.simpleName})"
}
