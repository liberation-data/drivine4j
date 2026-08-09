package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.POLYMORPHIC_LABELS_KEY
import org.neo4j.driver.Value
import org.neo4j.driver.internal.value.MapValue
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * Post-processor that transforms Neo4j query results into domain objects.
 *
 * Handles:
 * - Polymorphic deserialization (sealed classes, interfaces with registered subtypes)
 * - Client-side sorting (@SortedBy annotation)
 * - Nested GraphView construction
 */
class TransformPostProcessor<S, T>(
    private val type: Class<T>,
    private val subtypeRegistry: SubtypeRegistry? = null,
) : ResultPostProcessor<S, T> {

    private val objectMapper = Neo4jObjectMapper.instance
    private val collectionSorter = CollectionSorter()

    // Cache for subtype mappings to avoid repeated reflection
    private val subtypeMap: Map<String, Class<*>>? by lazy {
        // First check the dynamic registry, then fall back to @JsonSubTypes annotation
        subtypeRegistry?.getSubtypes(type) ?: buildSubtypeMapFromAnnotation(type)
    }

    // =============================================================================
    // Public API
    // =============================================================================

    override fun apply(results: List<S>): List<T> {
        return results.map { result -> transformResult(result) }
    }

    /**
     * Creates a new TransformPostProcessor with the given SubtypeRegistry.
     * Used internally by the ResultMapper to inject the registry at query time.
     */
    fun withSubtypeRegistry(registry: SubtypeRegistry): TransformPostProcessor<S, T> {
        return TransformPostProcessor(type, registry)
    }

    override fun toString(): String = "transform(${type.simpleName})"

    // =============================================================================
    // Result Transformation
    // =============================================================================

    /**
     * Transforms a single query result into the target type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun transformResult(result: S): T {
        val rawData = extractDataFromResult(result)
        val targetType = determineConcreteType(rawData) ?: type

        // Rename @GraphProperty on-disk keys back to field names and reassemble any @PropertyBag
        // fields from their flat prefixed keys, before deserializing.
        val data = reconstructFragmentData(rawData, targetType)

        return if (requiresSpecialHandling(targetType)) {
            constructWithSpecialHandling(data as Map<String, Any?>, targetType) as T
        } else {
            objectMapper.convertValue(data, targetType) as T
        }
    }

    /**
     * Prepares a raw result map for Jackson: renames `@GraphProperty` on-disk keys back to field
     * names, and reassembles `@PropertyBag` fields from their flat prefixed node properties. Both are
     * needed only on the `.*` path (polymorphic / bag fragments), where the map is keyed by node
     * *property* names; the concrete-fragment projection already aliases to field names, so these are
     * no-ops there. For a view, recurses into the root fragment and each relationship target (single
     * or collection, fragment or nested view). Leaves untouched data alone, so a no-op for the common
     * case.
     */
    @Suppress("UNCHECKED_CAST")
    private fun reconstructFragmentData(data: Any?, type: Class<*>): Any? {
        if (data !is Map<*, *>) return data
        val map = (data as Map<String, Any?>).toMutableMap()

        if (type.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = GraphViewModel.from(type)
            val rootName = viewModel.rootFragment.fieldName
            map[rootName] = reconstructFragmentData(map[rootName], viewModel.rootFragment.fragmentType)
            viewModel.relationships.forEach { rel ->
                when (val value = map[rel.fieldName]) {
                    is List<*> -> map[rel.fieldName] = value.map { reconstructFragmentData(it, rel.elementType) }
                    else -> map[rel.fieldName] = reconstructFragmentData(value, rel.elementType)
                }
            }
            return map
        }

        if (type.isAnnotationPresent(NodeFragment::class.java)) {
            val fragmentModel = try {
                FragmentModel.from(type)
            } catch (e: Exception) {
                return map
            }
            // @GraphProperty: rename each overridden on-disk key back to its field name. On the concrete
            // projection the map is already field-keyed, so containsKey(propertyName) is false — no-op.
            fragmentModel.fields.forEach { field ->
                if (field.propertyName != field.name && map.containsKey(field.propertyName)) {
                    map[field.name] = map.remove(field.propertyName)
                }
            }
            if (fragmentModel.propertyBags.isEmpty()) return map
            fragmentModel.propertyBags.forEach { bag ->
                val bagMap = mutableMapOf<String, Any?>()
                val claimedKeys = mutableListOf<String>()
                map.forEach { (key, value) ->
                    if (bag.owns(key)) {
                        bagMap[bag.entryKey(key)] = value
                        claimedKeys.add(key)
                    }
                }
                claimedKeys.forEach { map.remove(it) }
                map[bag.fieldName] = bagMap
            }
            return map
        }

        return map
    }

    /**
     * Extracts the raw data map from various Neo4j result types.
     */
    private fun extractDataFromResult(result: S): Any? {
        return when (result) {
            is MapValue -> result.asMap()
            is Value -> result.asObject()
            else -> result
        }
    }

    /**
     * Whether a view must be built by reflection ([constructWithSpecialHandling]) rather than plain
     * Jackson `convertValue`. Needed when the view contains a polymorphic fragment that must be
     * dispatched to a concrete subtype — as a **root fragment** (e.g. a recursive tree whose node is a
     * sealed interface) or a **relationship target** — a client-side sorted collection, or a
     * nested/recursive view that itself needs it. Cycle-guarded for self-referential views.
     */
    private fun requiresSpecialHandling(targetClass: Class<*>): Boolean =
        requiresSpecialHandling(targetClass, mutableSetOf())

    private fun requiresSpecialHandling(targetClass: Class<*>, visited: MutableSet<Class<*>>): Boolean {
        if (!targetClass.isAnnotationPresent(GraphView::class.java)) return false
        if (!visited.add(targetClass)) return false // already visiting (recursive view) — no new reason here
        val viewModel = GraphViewModel.from(targetClass)
        if (needsPolymorphicHandling(viewModel.rootFragment.fragmentType)) return true
        if (viewModel.relationships.any { needsPolymorphicHandling(it.elementType) }) return true
        if (viewModel.relationships.any { it.sortBy != null }) return true
        return viewModel.relationships.any {
            it.elementType.isAnnotationPresent(GraphView::class.java) && requiresSpecialHandling(it.elementType, visited)
        }
    }

    // =============================================================================
    // Special Handling Construction
    // =============================================================================

    /**
     * Constructs a GraphView object with special handling for:
     * - Polymorphic relationships (sealed classes, interfaces with subtypes)
     * - Client-side sorted collections (@SortedBy annotation)
     *
     * Uses Kotlin reflection to directly construct the data class with pre-converted and sorted objects.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> constructWithSpecialHandling(data: Map<String, Any?>, targetClass: Class<R>): R {
        val viewModel = GraphViewModel.from(targetClass)
        val kotlinClass = targetClass.kotlin

        val constructor = kotlinClass.primaryConstructor
            ?: throw IllegalArgumentException("Class ${targetClass.name} has no primary constructor")

        val parameterValues = buildConstructorParameters(data, viewModel, constructor)

        return constructor.callBy(parameterValues)
    }

    /**
     * Builds the parameter map for constructor injection.
     */
    private fun buildConstructorParameters(
        data: Map<String, Any?>,
        viewModel: GraphViewModel,
        constructor: kotlin.reflect.KFunction<*>
    ): Map<KParameter, Any?> {
        val parameterValues = mutableMapOf<KParameter, Any?>()

        constructor.parameters.forEach { param ->
            val paramName = param.name ?: return@forEach
            val rel = viewModel.relationships.find { it.fieldName == paramName }
            val fieldData = data[paramName]
            val isRootFragment = viewModel.rootFragment.fieldName == paramName

            val value = convertFieldValue(param, rel, fieldData, isRootFragment, viewModel)
            val sortedValue = applySortingIfConfigured(value, rel)

            if (sortedValue != null || param.type.isMarkedNullable) {
                parameterValues[param] = sortedValue
            }
        }

        return parameterValues
    }

    /**
     * Converts a field value based on its type and relationship configuration.
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertFieldValue(
        param: KParameter,
        rel: RelationshipModel?,
        fieldData: Any?,
        isRootFragment: Boolean,
        viewModel: GraphViewModel,
    ): Any? {
        return when {
            // Polymorphic relationship target
            rel != null && needsPolymorphicHandling(rel.elementType) && fieldData != null -> {
                convertPolymorphicRelationship(rel, fieldData)
            }

            // Nested / recursive view relationship whose own tree needs special handling — recurse
            // (so a nested view's polymorphic root fragment is dispatched too).
            rel != null && fieldData != null && isSpecialView(rel.elementType) -> {
                convertNestedViewRelationship(rel, fieldData)
            }

            // Non-polymorphic collection relationship
            rel != null && rel.isCollection && fieldData is List<*> -> {
                convertCollectionRelationship(rel, fieldData)
            }

            // Single relationship
            rel != null && fieldData != null -> {
                objectMapper.convertValue(fieldData, rel.elementType)
            }

            // Polymorphic root fragment field — dispatch to its concrete subtype by labels.
            isRootFragment && fieldData != null && needsPolymorphicHandling(viewModel.rootFragment.fragmentType) -> {
                convertPolymorphicItem(fieldData, viewModel.rootFragment.fragmentType)
            }

            // Non-relationship field
            fieldData != null -> {
                convertNonRelationshipField(param, fieldData)
            }

            // Null value
            else -> null
        }
    }

    /** Whether a relationship-target view must itself be built by reflection (nested / recursive). */
    private fun isSpecialView(type: Class<*>): Boolean =
        type.isAnnotationPresent(GraphView::class.java) && requiresSpecialHandling(type)

    /** Builds a nested/recursive view relationship, recursing through [constructWithSpecialHandling]. */
    @Suppress("UNCHECKED_CAST")
    private fun convertNestedViewRelationship(rel: RelationshipModel, fieldData: Any): Any? {
        fun build(item: Any?): Any? = when (item) {
            is Map<*, *> -> constructWithSpecialHandling(
                reconstructFragmentData(item, rel.elementType) as Map<String, Any?>, rel.elementType
            )
            null -> null
            else -> objectMapper.convertValue(item, rel.elementType)
        }
        return if (rel.isCollection && fieldData is List<*>) fieldData.map { build(it) } else build(fieldData)
    }

    /**
     * Converts a polymorphic relationship (sealed class or interface with subtypes).
     */
    private fun convertPolymorphicRelationship(rel: RelationshipModel, fieldData: Any): Any? {
        return if (rel.isCollection && fieldData is List<*>) {
            fieldData.map { item -> convertPolymorphicItem(item, rel.elementType) }
        } else {
            convertPolymorphicItem(fieldData, rel.elementType)
        }
    }

    /**
     * Converts a collection relationship by converting each element individually.
     */
    private fun convertCollectionRelationship(rel: RelationshipModel, fieldData: List<*>): List<Any?> {
        return fieldData.map { item ->
            item?.let { objectMapper.convertValue(it, rel.elementType) }
        }
    }

    /**
     * Converts a non-relationship field using the parameter's type.
     */
    private fun convertNonRelationshipField(param: KParameter, fieldData: Any): Any? {
        val paramType = param.type.classifier as? KClass<*>
        return if (paramType != null) {
            objectMapper.convertValue(fieldData, paramType.java)
        } else {
            fieldData
        }
    }

    /**
     * Applies client-side sorting if configured on the relationship.
     */
    private fun applySortingIfConfigured(value: Any?, rel: RelationshipModel?): Any? {
        if (rel?.sortBy != null && value is List<*> && value.isNotEmpty()) {
            return collectionSorter.sort(value, rel.sortBy, rel.sortAscending)
        }
        return value
    }

    // =============================================================================
    // Polymorphic Type Resolution
    // =============================================================================

    /**
     * Checks if a type needs polymorphic handling.
     * Returns true for sealed classes and interfaces with @NodeFragment or registered subtypes.
     */
    private fun needsPolymorphicHandling(type: Class<*>): Boolean {
        val kotlinClass = type.kotlin

        if (kotlinClass.isSealed) {
            return true
        }

        if (type.isInterface) {
            val hasNodeFragment = type.isAnnotationPresent(NodeFragment::class.java)
            val hasRegisteredSubtypes = subtypeRegistry?.hasSubtypes(type) == true
            return hasNodeFragment || hasRegisteredSubtypes
        }

        return false
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
        // A nested comprehension carries labels under __labels (collision-safe); the top-level and
        // relationship-target projections use `labels`. Accept either.
        val labels = mapData[POLYMORPHIC_LABELS_KEY] ?: mapData["labels"]

        if (labels is List<*>) {
            val labelStrings = labels.filterIsInstance<String>()
            val concreteType = resolveConcreteTypeByLabels(baseType, labelStrings)

            if (concreteType != null) {
                // Reconstruct @GraphProperty / @PropertyBag keys using the *concrete* subtype's model
                // (the base model doesn't know a subtype's fields), then deserialize.
                return objectMapper.convertValue(reconstructFragmentData(mapData, concreteType), concreteType)
            }
        }

        // If can't resolve, try base type (will fail for sealed classes)
        return objectMapper.convertValue(mapData, baseType)
    }

    /**
     * Resolves a concrete type for a polymorphic base type based on Neo4j labels.
     * Uses composite key (sorted, comma-joined labels) to find the matching subtype.
     */
    private fun resolveConcreteTypeByLabels(baseType: Class<*>, labels: List<String>): Class<*>? {
        // First try the subtype registry (covers all registered types)
        subtypeRegistry?.resolveByLabels(baseType, labels)?.let { return it }

        // For sealed classes, resolve directly from sealed subclasses
        return resolveFromSealedSubclasses(baseType, labels)
    }

    /**
     * Resolves a concrete type from sealed subclasses based on labels.
     */
    private fun resolveFromSealedSubclasses(baseType: Class<*>, labels: List<String>): Class<*>? {
        val kotlinClass = baseType.kotlin
        if (!kotlinClass.isSealed) {
            return null
        }

        val compositeKey = SubtypeRegistry.labelsToKey(labels)

        kotlinClass.sealedSubclasses.forEach { subclass ->
            val subclassJava = subclass.java
            val nodeFragment = subclassJava.getAnnotation(NodeFragment::class.java)
            if (nodeFragment != null) {
                val subLabels = nodeFragment.labels.toList()
                val subCompositeKey = SubtypeRegistry.labelsToKey(subLabels)
                if (subCompositeKey == compositeKey) {
                    return subclassJava
                }
            }
        }

        return null
    }

    /**
     * Determines the concrete type to deserialize to based on Neo4j labels or type property.
     * Returns null if no specific subtype can be determined.
     */
    private fun determineConcreteType(data: Any?): Class<*>? {
        if (data !is Map<*, *>) {
            return null
        }

        // First try type property
        resolveFromTypeProperty(data)?.let { return it }

        // Then try Neo4j labels
        return resolveFromLabels(data)
    }

    /**
     * Resolves concrete type from the type property in the data.
     */
    private fun resolveFromTypeProperty(data: Map<*, *>): Class<*>? {
        val typeInfo = type.getAnnotation(JsonTypeInfo::class.java)
        val typeProperty = typeInfo?.property ?: "type"
        val typeValue = data[typeProperty] as? String
        return typeValue?.let { subtypeMap?.get(it) }
    }

    /**
     * Resolves concrete type from Neo4j labels in the data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveFromLabels(data: Map<*, *>): Class<*>? {
        val labels = data["labels"]
        if (labels !is List<*>) {
            return null
        }

        val labelStrings = labels.filterIsInstance<String>()

        // Use SubtypeRegistry's composite label resolution if available
        subtypeRegistry?.resolveByLabels(type, labelStrings)?.let { return it }

        // Fall back to subtypeMap
        return resolveFromSubtypeMap(labelStrings)
    }

    /**
     * Resolves concrete type from the local subtype map.
     */
    private fun resolveFromSubtypeMap(labelStrings: List<String>): Class<*>? {
        val localSubtypeMap = subtypeMap ?: return null

        // Try composite key first (most specific match)
        val compositeKey = SubtypeRegistry.labelsToKey(labelStrings)
        localSubtypeMap[compositeKey]?.let { return it }

        // Fall back to individual label matching
        for (label in labelStrings) {
            localSubtypeMap[label]?.let { return it }
        }

        return null
    }

    /**
     * Builds a map of subtype names to their classes based on @JsonSubTypes annotation.
     */
    private fun buildSubtypeMapFromAnnotation(clazz: Class<*>): Map<String, Class<*>>? {
        val jsonSubTypes = clazz.getAnnotation(JsonSubTypes::class.java) ?: return null
        return jsonSubTypes.value.associate { subType ->
            subType.name to subType.value.java
        }
    }

    // =============================================================================
    // Nested Polymorphic Preprocessing (for complex nested structures)
    // =============================================================================

    /**
     * Pre-processes a data map to add @type hints for polymorphic deserialization.
     * This enables Jackson to deserialize nested sealed classes correctly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun preprocessPolymorphicData(data: Any?, targetClass: Class<*>): Any? {
        if (data !is Map<*, *>) {
            return data
        }

        val result = (data as Map<String, Any?>).toMutableMap()
        val isGraphView = targetClass.isAnnotationPresent(GraphView::class.java)

        if (isGraphView) {
            val viewModel = GraphViewModel.from(targetClass)
            preprocessRelationshipFields(result, viewModel)
        }

        return result
    }

    /**
     * Pre-processes relationship fields in the data map.
     */
    @Suppress("UNCHECKED_CAST")
    private fun preprocessRelationshipFields(result: MutableMap<String, Any?>, viewModel: GraphViewModel) {
        viewModel.relationships.forEach { rel ->
            val fieldData = result[rel.fieldName] ?: return@forEach
            val elementType = rel.elementType
            val isPolymorphic = needsPolymorphicHandling(elementType)

            result[rel.fieldName] = when {
                rel.isCollection && fieldData is List<*> -> {
                    fieldData.map { item -> preprocessNestedPolymorphic(item, elementType, isPolymorphic) }
                }
                fieldData is Map<*, *> -> {
                    preprocessNestedPolymorphic(fieldData, elementType, isPolymorphic)
                }
                else -> fieldData
            }
        }
    }

    /**
     * Pre-processes a nested object to resolve its concrete type based on labels.
     */
    @Suppress("UNCHECKED_CAST")
    private fun preprocessNestedPolymorphic(data: Any?, elementType: Class<*>, isPolymorphic: Boolean): Any? {
        if (data !is Map<*, *>) {
            return data
        }

        val mapData = data as Map<String, Any?>

        if (isPolymorphic) {
            return preprocessPolymorphicNested(mapData, elementType)
        }

        // If not polymorphic, recursively process nested data and convert
        val processedData = preprocessPolymorphicData(mapData, elementType)
        return objectMapper.convertValue(processedData, elementType)
    }

    /**
     * Pre-processes a polymorphic nested object by resolving its concrete type.
     */
    private fun preprocessPolymorphicNested(mapData: Map<String, Any?>, elementType: Class<*>): Any? {
        val labels = mapData["labels"]
        if (labels is List<*>) {
            val labelStrings = labels.filterIsInstance<String>()
            val concreteType = resolveConcreteTypeByLabels(elementType, labelStrings)

            if (concreteType != null && concreteType != elementType) {
                val processedData = preprocessPolymorphicData(mapData, concreteType)
                return objectMapper.convertValue(processedData, concreteType)
            }
        }

        // Fall back to element type
        val processedData = preprocessPolymorphicData(mapData, elementType)
        return objectMapper.convertValue(processedData, elementType)
    }
}