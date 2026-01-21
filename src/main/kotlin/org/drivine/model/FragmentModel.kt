package org.drivine.model

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
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
     * The fields/properties of this fragment class with their types.
     * Includes both declared fields and inherited fields.
     */
    val fields: List<FragmentField>,

    /**
     * The name of the field annotated with @GraphNodeId, if present.
     * This field represents the Neo4j node ID.
     */
    val nodeIdField: String?
) {
    companion object {
        /**
         * Creates a FragmentModel from a class annotated with @GraphFragment.
         *
         * @param clazz The class to analyze
         * @return FragmentModel containing metadata about the class
         * @throws IllegalArgumentException if the class is not annotated with @GraphFragment
         */
        fun from(clazz: Class<*>): FragmentModel {
            val annotation = clazz.getAnnotation(NodeFragment::class.java)
                ?: throw IllegalArgumentException("Class ${clazz.name} is not annotated with @GraphFragment")

            val labels = annotation.labels.toList()
            val fields = extractFields(clazz)
            val nodeIdField = findNodeIdField(clazz)

            return FragmentModel(
                className = clazz.name,
                clazz = clazz,
                labels = labels,
                fields = fields,
                nodeIdField = nodeIdField
            )
        }

        /**
         * Creates a FragmentModel from a Kotlin class annotated with @GraphFragment.
         */
        fun from(kClass: KClass<*>): FragmentModel = from(kClass.java)

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
            return kClass.memberProperties.map { property ->
                val returnType = property.returnType
                val javaType = returnType.javaType as? Class<*> ?: Any::class.java

                FragmentField(
                    name = property.name,
                    type = javaType,
                    kotlinType = returnType.classifier as? KClass<*>,
                    nullable = returnType.isMarkedNullable,
                    typeString = returnType.toString()
                )
            }.sortedBy { it.name }
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
                        fields.add(
                            FragmentField(
                                name = field.name,
                                type = field.type,
                                kotlinType = null,
                                nullable = true, // Java nullability cannot be reliably determined
                                typeString = field.genericType.typeName
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
