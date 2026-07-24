package org.drivine.model

import org.drivine.annotation.CompositeProperty
import org.drivine.annotation.GraphTransient
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.annotation.VectorIndex
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType

/**
 * Represents metadata about a class annotated with @GraphFragment.
 * This model captures the structure needed for mapping between graph nodes and domain objects.
 */
data class FragmentModel(
    /**
     * The fully qualified class name of the fragment class.
     * Example: "sample.mapped.fragment.Person"
     */
    val className: String,

    /**
     * The Class object for the fragment class.
     * Useful for instantiation and Java interop.
     */
    val clazz: Class<*>,

    /**
     * The labels associated with this fragment from the @GraphFragment annotation.
     * Example: ["Person"] or ["Person", "GithubPerson"]
     */
    val labels: List<String>,

    /**
     * The fields/properties of this fragment class with their types. Includes declared and inherited
     * fields, but **excludes** `@PropertyBag` fields (those are in [propertyBags]) — so the normal
     * SET and projection paths treat the bag's prefixed properties, not the map field itself.
     */
    val fields: List<FragmentField>,

    /**
     * The name of the field annotated with @GraphNodeId, if present.
     * This field represents the Neo4j node ID.
     */
    val nodeIdField: String?,

    /**
     * The `@PropertyBag` / `@CompositeProperty` fields on this fragment — open maps persisted as flat
     * prefixed node properties. Empty for the common case.
     */
    val propertyBags: List<PropertyBagModel> = emptyList(),
) {
    /**
     * Names of `@VectorIndex` (embedding) fields — the fields whose value must be written as the
     * engine's native vector type on save. Empty for the common (non-vector) fragment.
     */
    val vectorFieldNames: Set<String>
        get() = fields.filter { it.vectorIndexed }.map { it.name }.toSet()

    companion object {
        /**
         * Creates a FragmentModel from a class annotated with @GraphFragment.
         *
         * @param clazz The class to analyze
         * @return FragmentModel containing metadata about the class
         * @throws IllegalArgumentException if the class is not annotated with @GraphFragment
         */
        fun from(clazz: Class<*>): FragmentModel {
            clazz.getAnnotation(NodeFragment::class.java)
                ?: throw IllegalArgumentException("Class ${clazz.name} is not annotated with @GraphFragment")

            val labels = labelsFor(clazz)
            val allFields = extractFields(clazz)
            val nodeIdField = findNodeIdField(clazz)

            // Partition @PropertyBag fields out of the regular fields: they are persisted/loaded as
            // flat prefixed properties, not as a single map-valued property.
            val propertyBags = allFields.mapNotNull { field ->
                field.propertyBag?.let { spec ->
                    val prefix = spec.prefix.ifEmpty { field.name }
                    PropertyBagModel(fieldName = field.name, storedPrefix = "$prefix${spec.delimiter}")
                }
            }
            validateNonOverlappingPrefixes(propertyBags, clazz)

            return FragmentModel(
                className = clazz.name,
                clazz = clazz,
                labels = labels,
                fields = allFields.filter { it.propertyBag == null },
                nodeIdField = nodeIdField,
                propertyBags = propertyBags,
            )
        }

        /**
         * Rejects fragments whose property bags have overlapping prefixes — if one bag's prefix is a
         * delimiter-prefix of another's, a stored key matches both on load and would be claimed
         * twice. Fail loudly at model build rather than silently mis-assigning entries.
         */
        private fun validateNonOverlappingPrefixes(bags: List<PropertyBagModel>, clazz: Class<*>) {
            for (a in bags) {
                for (b in bags) {
                    if (a !== b && b.storedPrefix.startsWith(a.storedPrefix)) {
                        throw IllegalArgumentException(
                            "@PropertyBag prefixes on ${clazz.simpleName} overlap: '${a.storedPrefix}' " +
                                "(field '${a.fieldName}') is a prefix of '${b.storedPrefix}' (field '${b.fieldName}'). " +
                                "Use distinct, non-nested prefixes."
                        )
                    }
                }
            }
        }

        /**
         * Creates a FragmentModel from a Kotlin class annotated with @GraphFragment.
         */
        fun from(kClass: KClass<*>): FragmentModel = from(kClass.java)

        /**
         * Resolves the complete set of @NodeFragment labels a class
         * persists with: the class's own labels unioned with those
         * declared on its superclasses and (transitively) implemented
         * interfaces.
         *
         * The class's own labels lead, followed by inherited labels in
         * breadth-first order, with duplicates removed. So a concrete
         * subtype of `@NodeFragment(labels = ["Signal"]) interface Signal`
         * persists as `:EmailSignal:Signal` without having to repeat
         * "Signal" in its own annotation — which is what makes
         * `MATCH (n:Signal)` find every subtype.
         *
         * Before this traversal existed only the concrete class's own
         * annotation was inspected, so interface/superclass labels were
         * silently dropped at save time and `pm.registerSubtype` only
         * wired load-time polymorphism, never the persisted label set.
         */
        fun labelsFor(clazz: Class<*>): List<String> {
            val labels = LinkedHashSet<String>()
            val visited = mutableSetOf<Class<*>>()
            val queue = ArrayDeque<Class<*>>()
            queue.add(clazz)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                current.getAnnotation(NodeFragment::class.java)
                    ?.let { labels.addAll(it.labels) }
                current.superclass
                    ?.takeIf { it != Any::class.java }
                    ?.let { queue.add(it) }
                current.interfaces.forEach { queue.add(it) }
            }
            return labels.toList()
        }

        /**
         * Extracts all fields from a class, including inherited fields.
         * Handles both Java and Kotlin classes.
         */
        private fun extractFields(clazz: Class<*>): List<FragmentField> {
            // Try Kotlin reflection first
            return try {
                extractKotlinFields(clazz)
            } catch (e: Exception) {
                // Fall back to Java reflection for Java classes or if Kotlin reflection fails
                extractJavaFields(clazz)
            }
        }

        /**
         * Extracts fields using Kotlin reflection.
         * Provides better type information including nullability.
         */
        private fun extractKotlinFields(clazz: Class<*>): List<FragmentField> {
            val kClass = clazz.kotlin
            return kClass.memberProperties
                .filterNot { it.isGraphTransient() }
                .filterNot { it.isStaticBackingField() }
                .map { property ->
                    val returnType = property.returnType
                    val javaType = returnType.javaType as? Class<*> ?: Any::class.java

                    FragmentField(
                        name = property.name,
                        type = javaType,
                        kotlinType = returnType.classifier as? KClass<*>,
                        nullable = returnType.isMarkedNullable,
                        typeString = returnType.toString(),
                        propertyBag = property.propertyBagSpec(),
                        vectorIndexed = property.isVectorIndexed(),
                    )
                }.sortedBy { it.name }
        }

        /** Whether a Kotlin property (or its backing field) carries `@VectorIndex`. */
        private fun KProperty1<*, *>.isVectorIndexed(): Boolean =
            findAnnotation<VectorIndex>() != null || javaField?.isAnnotationPresent(VectorIndex::class.java) == true

        /** Reads `@PropertyBag` / `@CompositeProperty` off a Kotlin property (or its backing field). */
        private fun KProperty1<*, *>.propertyBagSpec(): PropertyBagSpec? {
            findAnnotation<PropertyBag>()?.let { return PropertyBagSpec(it.prefix, it.delimiter) }
            findAnnotation<CompositeProperty>()?.let { return PropertyBagSpec(it.prefix, it.delimiter) }
            val field = javaField
            field?.getAnnotation(PropertyBag::class.java)?.let { return PropertyBagSpec(it.prefix, it.delimiter) }
            field?.getAnnotation(CompositeProperty::class.java)?.let { return PropertyBagSpec(it.prefix, it.delimiter) }
            return null
        }

        /**
         * True if the property is annotated [GraphTransient] on the
         * property itself, its getter, its setter, or its backing
         * field. Lets callers model computed / lazy / cache
         * properties on a `@NodeFragment` class without those
         * accessors becoming MERGE columns on save.
         */
        private fun KProperty1<*, *>.isGraphTransient(): Boolean {
            if (findAnnotation<GraphTransient>() != null) return true
            if (getter.findAnnotation<GraphTransient>() != null) return true
            if (this is KMutableProperty1<*, *> &&
                setter.findAnnotation<GraphTransient>() != null
            ) return true
            val backingField = javaField
            if (backingField?.isAnnotationPresent(GraphTransient::class.java) == true) return true
            return false
        }

        /**
         * Skip properties whose backing field is JVM-static — those
         * are companion-object refs (`Companion`) and other static
         * artefacts that Kotlin reflection surfaces via
         * `memberProperties` but that aren't instance-level state.
         */
        private fun KProperty1<*, *>.isStaticBackingField(): Boolean {
            val f = javaField ?: return false
            return Modifier.isStatic(f.modifiers)
        }

        /**
         * Extracts fields using Java reflection.
         * Used for Java classes or as a fallback.
         */
        private fun extractJavaFields(clazz: Class<*>): List<FragmentField> {
            val fields = mutableListOf<FragmentField>()
            var currentClass: Class<*>? = clazz

            while (currentClass != null && currentClass != Any::class.java) {
                currentClass.declaredFields
                    .filterNot { it.isSynthetic }
                    .forEach { field ->
                        val bag = field.getAnnotation(PropertyBag::class.java)?.let { PropertyBagSpec(it.prefix, it.delimiter) }
                            ?: field.getAnnotation(CompositeProperty::class.java)?.let { PropertyBagSpec(it.prefix, it.delimiter) }
                        fields.add(
                            FragmentField(
                                name = field.name,
                                type = field.type,
                                kotlinType = null,
                                nullable = true, // Java nullability cannot be reliably determined
                                typeString = field.genericType.typeName,
                                propertyBag = bag,
                                vectorIndexed = field.isAnnotationPresent(VectorIndex::class.java),
                            )
                        )
                    }
                currentClass = currentClass.superclass
            }

            return fields.sortedBy { it.name }
        }

        /**
         * Finds the field annotated with @GraphNodeId.
         * Returns the field name if found, null otherwise.
         * Checks both property-level annotations and getter-level annotations (@get:NodeId).
         */
        private fun findNodeIdField(clazz: Class<*>): String? {
            // Try Kotlin reflection first
            return try {
                val kClass = clazz.kotlin
                kClass.memberProperties.find { property ->
                    // Check property-level annotation first
                    property.findAnnotation<NodeId>() != null ||
                        // Also check getter annotation (for @get:NodeId on interface properties)
                        property.getter.findAnnotation<NodeId>() != null
                }?.name
            } catch (e: Exception) {
                // Fall back to Java reflection
                findNodeIdFieldJava(clazz)
            }
        }

        /**
         * Finds the field annotated with @GraphNodeId using Java reflection.
         */
        private fun findNodeIdFieldJava(clazz: Class<*>): String? {
            var currentClass: Class<*>? = clazz

            while (currentClass != null && currentClass != Any::class.java) {
                val field = currentClass.declaredFields
                    .filterNot { it.isSynthetic }
                    .find { it.isAnnotationPresent(NodeId::class.java) }

                if (field != null) {
                    return field.name
                }

                currentClass = currentClass.superclass
            }

            return null
        }
    }
}
