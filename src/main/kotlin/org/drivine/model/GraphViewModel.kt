package org.drivine.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.annotation.SortedBy
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Represents metadata about a class annotated with @GraphView.
 * A GraphView combines a root fragment with one or more relationships.
 *
 * TODO: Refactor the companion object's from() methods. The Kotlin and Java reflection
 *  paths share significant logic (relationship fragment detection, recursive detection,
 *  model construction) that should be extracted into shared helpers.
 */
data class GraphViewModel(
    /**
     * The fully qualified class name of the view class.
     */
    val className: String,

    /**
     * The Class object for the view class.
     */
    val clazz: Class<*>,

    /**
     * The root fragment field - must point to a @GraphFragment annotated class.
     * This is the field WITHOUT a @GraphRelationship annotation.
     */
    val rootFragment: RootFragmentField,

    /**
     * The relationship fields annotated with @GraphRelationship.
     */
    val relationships: List<RelationshipModel>
) {
    companion object {
        /**
         * Creates a GraphViewModel from a class annotated with @GraphView.
         *
         * @param clazz The class to analyze
         * @return GraphViewModel containing metadata about the class
         * @throws IllegalArgumentException if the class is not annotated with @GraphView
         */
        fun from(clazz: Class<*>): GraphViewModel {
            clazz.getAnnotation(GraphView::class.java)
                ?: throw IllegalArgumentException("Class ${clazz.name} is not annotated with @GraphView")

            // Try Kotlin reflection first, fall back to Java reflection if needed
            val kClass = clazz.kotlin
            val properties = kClass.memberProperties

            // Check if this is a Java class by looking for @Root annotation on fields
            // (Kotlin classes use properties, Java classes use fields)
            val hasRootAnnotationOnField = clazz.declaredFields.any { field ->
                !field.isSynthetic && field.isAnnotationPresent(Root::class.java)
            }

            if (hasRootAnnotationOnField || properties.isEmpty()) {
                // This is a Java class or Kotlin reflection isn't working - use Java reflection
                return fromJavaClass(clazz)
            }

            // Use Kotlin reflection for Kotlin classes
            // Find the root fragment - the field WITHOUT @GraphRelationship that points to a @GraphFragment class
            val rootFragmentProperty = properties.find { prop ->
                val hasRelationshipAnnotation = prop.findAnnotation<GraphRelationship>() != null
                if (hasRelationshipAnnotation) {
                    false
                } else {
                    val returnType = prop.returnType.classifier as? KClass<*>
                    returnType?.java?.isAnnotationPresent(NodeFragment::class.java) == true
                }
            } ?: throw IllegalArgumentException("No root fragment field found in ${clazz.name}. " +
                    "Expected a field without @GraphRelationship that points to a @GraphFragment class.")

            val rootFragmentType = (rootFragmentProperty.returnType.classifier as KClass<*>).java

            val rootFragment = RootFragmentField(
                fieldName = rootFragmentProperty.name,
                fragmentType = rootFragmentType
            )

            // Find all relationship fields
            val relationships = properties
                .mapNotNull { prop ->
                    val relationshipAnnotation = prop.findAnnotation<GraphRelationship>()
                    if (relationshipAnnotation != null) {
                        val fieldType = (prop.returnType.classifier as? KClass<*>)?.java ?: Any::class.java

                        // Determine element type and whether it's a collection
                        val (elementType, isCollection) = extractElementType(prop.returnType.toString(), fieldType)

                        // Detect nullability from Kotlin type
                        val isNullable = prop.returnType.isMarkedNullable

                        // Check if element type is annotated with @GraphRelationshipFragment (relationship object pattern)
                        val isRelationshipFragment = elementType.isAnnotationPresent(RelationshipFragment::class.java)

                        // Check for @SortedBy annotation (client-side sorting)
                        val sortedByAnnotation = prop.findAnnotation<SortedBy>()

                        if (isRelationshipFragment) {
                            // Extract relationship fragment metadata
                            val fragmentKClass = elementType.kotlin
                            val fragmentProperties = fragmentKClass.memberProperties

                            // Find the target field - must be annotated with @GraphFragment or @GraphView
                            val targetProperty = fragmentProperties.find { fragProp ->
                                val targetType = (fragProp.returnType.classifier as? KClass<*>)?.java
                                targetType?.isAnnotationPresent(NodeFragment::class.java) == true ||
                                targetType?.isAnnotationPresent(GraphView::class.java) == true
                            } ?: throw IllegalArgumentException(
                                "Relationship fragment '${elementType.simpleName}' in field '${prop.name}' " +
                                "must have exactly one field pointing to a @GraphFragment or @GraphView. " +
                                "Example: val target: Person"
                            )

                            val targetNodeType = (targetProperty.returnType.classifier as KClass<*>).java

                            // All other properties are relationship properties
                            val relationshipProperties = fragmentProperties
                                .filter { it.name != targetProperty.name }
                                .map { it.name }

                            RelationshipModel(
                                fieldName = prop.name,
                                type = relationshipAnnotation.type,
                                direction = relationshipAnnotation.direction,
                                fieldType = fieldType,
                                elementType = elementType,
                                isCollection = isCollection,
                                isNullable = isNullable,
                                isRelationshipFragment = true,
                                targetFieldName = targetProperty.name,
                                targetNodeType = targetNodeType,
                                relationshipProperties = relationshipProperties,
                                sortBy = sortedByAnnotation?.property,
                                sortAscending = sortedByAnnotation?.ascending ?: true
                            )
                        } else {
                            // Direct target reference (existing behavior)
                            // Detect self-referential recursive relationships
                            val isRecursive = elementType.isAnnotationPresent(GraphView::class.java) &&
                                elementType == clazz

                            RelationshipModel(
                                fieldName = prop.name,
                                type = relationshipAnnotation.type,
                                direction = relationshipAnnotation.direction,
                                fieldType = fieldType,
                                elementType = elementType,
                                isCollection = isCollection,
                                isNullable = isNullable,
                                sortBy = sortedByAnnotation?.property,
                                sortAscending = sortedByAnnotation?.ascending ?: true,
                                maxDepth = relationshipAnnotation.maxDepth,
                                isRecursive = isRecursive
                            )
                        }
                    } else {
                        null
                    }
                }

            return GraphViewModel(
                className = clazz.name,
                clazz = clazz,
                rootFragment = rootFragment,
                relationships = relationships
            )
        }

        /**
         * Creates a GraphViewModel from a Kotlin class annotated with @GraphView.
         */
        fun from(kClass: KClass<*>): GraphViewModel = from(kClass.java)

        /**
         * Extracts the element type from a field type.
         * For collections like List<Person>, returns (Person, true).
         * For single values like Person, returns (Person, false).
         */
        private fun extractElementType(typeString: String, fieldType: Class<*>): Pair<Class<*>, Boolean> {
            // Check if it's a collection by looking at the type string
            val isCollection = typeString.contains("List<") ||
                               typeString.contains("Set<") ||
                               typeString.contains("Collection<")

            if (isCollection) {
                // Try to extract the generic parameter
                val genericStart = typeString.indexOf('<')
                val genericEnd = typeString.lastIndexOf('>')
                if (genericStart > 0 && genericEnd > genericStart) {
                    val elementTypeName = typeString.substring(genericStart + 1, genericEnd)
                    val elementClass = resolveClassName(elementTypeName)
                    if (elementClass != null) {
                        return Pair(elementClass, true)
                    }
                }
                return Pair(Any::class.java, true)
            }

            return Pair(fieldType, false)
        }

        /**
         * Resolves a class name to a Class object, handling both top-level and inner classes.
         * Kotlin uses '.' for nested classes, but JVM uses '$'.
         * For example: "org.example.Outer.Inner" needs to become "org.example.Outer$Inner"
         *
         * @param className The class name from Kotlin reflection (using '.' for nested classes)
         * @return The Class object, or null if not found
         */
        private fun resolveClassName(className: String): Class<*>? {
            // First, try the name as-is (works for top-level classes)
            try {
                return Class.forName(className)
            } catch (e: ClassNotFoundException) {
                // Continue to try inner class resolution
            }

            // For inner classes, progressively replace '.' with '$' from right to left
            // Package names are lowercase, class names start with uppercase
            // So we look for '.' followed by uppercase letter and replace those with '$'
            val parts = className.split(".")
            if (parts.size < 2) return null

            // Find where package ends and class names begin
            // Assume package parts are lowercase, class names are uppercase
            val firstClassIndex = parts.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
            if (firstClassIndex < 0) return null

            // Try different combinations of '$' for nested classes
            for (dollarCount in 1 until (parts.size - firstClassIndex)) {
                val packagePart = parts.take(firstClassIndex).joinToString(".")
                val classParts = parts.drop(firstClassIndex)

                // Split class parts: first (dollarCount) separators become '$', rest stay '.'
                val classNameWithDollars = buildString {
                    classParts.forEachIndexed { index, part ->
                        if (index > 0) {
                            // Use '$' for inner classes (from the end)
                            append(if (index >= classParts.size - dollarCount) "$" else ".")
                        }
                        append(part)
                    }
                }

                val fullName = if (packagePart.isEmpty()) classNameWithDollars
                               else "$packagePart.$classNameWithDollars"

                try {
                    return Class.forName(fullName)
                } catch (e: ClassNotFoundException) {
                    // Try next combination
                }
            }

            return null
        }

        /**
         * Extracts element type from a Java Field using Java reflection.
         * This handles Java's type erasure by using ParameterizedType.
         */
        private fun extractElementTypeFromJavaField(field: Field): Pair<Class<*>, Boolean> {
            val fieldType = field.type

            // Check if it's a collection type
            val isCollection = Collection::class.java.isAssignableFrom(fieldType) ||
                             List::class.java.isAssignableFrom(fieldType) ||
                             Set::class.java.isAssignableFrom(fieldType)

            if (isCollection) {
                // Extract the generic type parameter
                val genericType = field.genericType
                if (genericType is ParameterizedType) {
                    val actualTypeArguments = genericType.actualTypeArguments
                    if (actualTypeArguments.isNotEmpty()) {
                        val elementType = actualTypeArguments[0] as? Class<*> ?: Any::class.java
                        return Pair(elementType, true)
                    }
                }
                // Couldn't determine element type
                return Pair(Any::class.java, true)
            }

            return Pair(fieldType, false)
        }

        /**
         * Creates a GraphViewModel using Java reflection (for Java classes).
         * This is used when Kotlin reflection doesn't have property information.
         */
        private fun fromJavaClass(clazz: Class<*>): GraphViewModel {
            val fields = clazz.declaredFields.filter { field ->
                // Skip synthetic fields generated by compilers
                !field.isSynthetic
            }

            // Find the root fragment - field with @Root annotation
            val rootFragmentField = fields.find { field ->
                field.isAnnotationPresent(Root::class.java)
            } ?: throw IllegalArgumentException("No root fragment field found in ${clazz.name}. " +
                    "Expected a field annotated with @Root that points to a @NodeFragment class.")

            val rootFragmentType = rootFragmentField.type
            if (!rootFragmentType.isAnnotationPresent(NodeFragment::class.java)) {
                throw IllegalArgumentException("Root field '${rootFragmentField.name}' in ${clazz.name} " +
                        "must point to a class annotated with @NodeFragment")
            }

            val rootFragment = RootFragmentField(
                fieldName = rootFragmentField.name,
                fragmentType = rootFragmentType
            )

            // Find all relationship fields
            val relationships = fields
                .mapNotNull { field ->
                    val relationshipAnnotation = field.getAnnotation(GraphRelationship::class.java)
                    if (relationshipAnnotation != null) {
                        val fieldType = field.type

                        // Extract element type using Java reflection
                        val (elementType, isCollection) = extractElementTypeFromJavaField(field)

                        // Check if element type is annotated with @GraphRelationshipFragment
                        val isRelationshipFragment = elementType.isAnnotationPresent(RelationshipFragment::class.java)

                        // Check for @SortedBy annotation (client-side sorting)
                        val sortedByAnnotation = field.getAnnotation(SortedBy::class.java)

                        if (isRelationshipFragment) {
                            // Extract relationship fragment metadata using Java reflection
                            val fragmentFields = elementType.declaredFields.filter { !it.isSynthetic }

                            // Find the target field - must be annotated with @NodeFragment or @GraphView
                            val targetField = fragmentFields.find { fragField ->
                                val targetType = fragField.type
                                targetType.isAnnotationPresent(NodeFragment::class.java) ||
                                targetType.isAnnotationPresent(GraphView::class.java)
                            } ?: throw IllegalArgumentException(
                                "Relationship fragment '${elementType.simpleName}' in field '${field.name}' " +
                                "must have exactly one field pointing to a @NodeFragment or @GraphView. " +
                                "Example: Person target;"
                            )

                            val targetNodeType = targetField.type

                            // All other fields are relationship properties
                            val relationshipProperties = fragmentFields
                                .filter { it.name != targetField.name }
                                .map { it.name }

                            RelationshipModel(
                                fieldName = field.name,
                                type = relationshipAnnotation.type,
                                direction = relationshipAnnotation.direction,
                                fieldType = fieldType,
                                elementType = elementType,
                                isCollection = isCollection,
                                isRelationshipFragment = true,
                                targetFieldName = targetField.name,
                                targetNodeType = targetNodeType,
                                relationshipProperties = relationshipProperties,
                                sortBy = sortedByAnnotation?.property,
                                sortAscending = sortedByAnnotation?.ascending ?: true
                            )
                        } else {
                            // Direct target reference
                            // Detect self-referential recursive relationships
                            val isRecursive = elementType.isAnnotationPresent(GraphView::class.java) &&
                                elementType == clazz

                            RelationshipModel(
                                fieldName = field.name,
                                type = relationshipAnnotation.type,
                                direction = relationshipAnnotation.direction,
                                fieldType = fieldType,
                                elementType = elementType,
                                isCollection = isCollection,
                                sortBy = sortedByAnnotation?.property,
                                sortAscending = sortedByAnnotation?.ascending ?: true,
                                maxDepth = relationshipAnnotation.maxDepth,
                                isRecursive = isRecursive
                            )
                        }
                    } else {
                        null
                    }
                }

            return GraphViewModel(
                className = clazz.name,
                clazz = clazz,
                rootFragment = rootFragment,
                relationships = relationships
            )
        }
    }
}

