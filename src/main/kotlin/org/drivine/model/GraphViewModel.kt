package org.drivine.model

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphRelationshipFragment
import org.drivine.annotation.GraphView
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Represents metadata about a class annotated with @GraphView.
 * A GraphView combines a root fragment with one or more relationships.
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

            val kClass = clazz.kotlin
            val properties = kClass.memberProperties

            // Find the root fragment - the field WITHOUT @GraphRelationship that points to a @GraphFragment class
            val rootFragmentProperty = properties.find { prop ->
                val hasRelationshipAnnotation = prop.findAnnotation<GraphRelationship>() != null
                if (hasRelationshipAnnotation) {
                    false
                } else {
                    val returnType = prop.returnType.classifier as? KClass<*>
                    returnType?.java?.isAnnotationPresent(GraphFragment::class.java) == true
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

                        // Check if element type is annotated with @GraphRelationshipFragment (relationship object pattern)
                        if (elementType.isAnnotationPresent(GraphRelationshipFragment::class.java)) {
                            throw UnsupportedOperationException(
                                "Relationship fragments (classes annotated with @GraphRelationshipFragment) are not yet supported. " +
                                "Field '${prop.name}' in ${clazz.name} uses relationship fragment '${elementType.simpleName}'. " +
                                "Use direct target references for now, e.g.: val ${prop.name}: List<Person>"
                            )
                        }

                        RelationshipModel(
                            fieldName = prop.name,
                            type = relationshipAnnotation.type,
                            direction = relationshipAnnotation.direction,
                            fieldType = fieldType,
                            elementType = elementType,
                            isCollection = isCollection
                        )
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
                try {
                    // Parse from type string like "kotlin.collections.List<sample.mapped.fragment.Person>"
                    val genericStart = typeString.indexOf('<')
                    val genericEnd = typeString.lastIndexOf('>')
                    if (genericStart > 0 && genericEnd > genericStart) {
                        val elementTypeName = typeString.substring(genericStart + 1, genericEnd)
                        val elementClass = Class.forName(elementTypeName)
                        return Pair(elementClass, true)
                    }
                } catch (e: ClassNotFoundException) {
                    // Fall back to Any if we can't find the class
                }
                return Pair(Any::class.java, true)
            }

            return Pair(fieldType, false)
        }
    }
}

