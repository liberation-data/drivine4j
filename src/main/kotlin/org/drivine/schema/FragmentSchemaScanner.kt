package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.RangeIndex
import org.drivine.annotation.Unique
import org.drivine.annotation.VectorIndex
import org.drivine.model.FragmentModel
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * Reads schema annotations ([VectorIndex], [RangeIndex], [Unique]) off a [NodeFragment] class
 * into [SchemaItemSpec]s.
 *
 * Works for both Kotlin and Java fragments: Kotlin reflection is tried first (constructor-property
 * annotations land on the property, not the backing field), with Java field reflection as the
 * fallback — mirroring [FragmentModel]'s approach.
 */
internal object FragmentSchemaScanner {

    fun scan(fragmentClass: Class<*>, dimensionProvider: VectorDimensionProvider?): List<SchemaItemSpec> {
        fragmentClass.getAnnotation(NodeFragment::class.java)
            ?: throw DrivineException(
                "Cannot scan ${fragmentClass.name} for schema annotations: " +
                    "it is not annotated with @NodeFragment"
            )

        val label = primaryLabel(fragmentClass)
        // A field's index/constraint must target its on-disk property name (@GraphProperty), since
        // that is what is stored and queried. Identity when there is no override.
        val onDisk = onDiskNameResolver(fragmentClass)
        val specs = mutableListOf<SchemaItemSpec>()
        specs += classLevelSpecs(fragmentClass, label, onDisk)
        specs += propertyLevelSpecs(fragmentClass, label, dimensionProvider, onDisk)
        return specs
    }

    /** Maps a fragment field name to its on-disk property name (`@GraphProperty`), identity otherwise. */
    private fun onDiskNameResolver(fragmentClass: Class<*>): (String) -> String {
        val byField = try {
            FragmentModel.from(fragmentClass).fields.associate { it.name to it.propertyName }
        } catch (e: Exception) {
            emptyMap()
        }
        return { field -> byField[field] ?: field }
    }

    /** The anchor label schema items are declared against — first of [FragmentModel.labelsFor]. */
    private fun primaryLabel(fragmentClass: Class<*>): String =
        FragmentModel.labelsFor(fragmentClass).firstOrNull() ?: fragmentClass.simpleName

    // ----- Class-level (composite) declarations -----

    private fun classLevelSpecs(fragmentClass: Class<*>, label: String, onDisk: (String) -> String): List<SchemaItemSpec> {
        val specs = mutableListOf<SchemaItemSpec>()

        fragmentClass.getAnnotationsByType(RangeIndex::class.java).forEach { annotation ->
            if (annotation.properties.isEmpty()) {
                throw DrivineException(
                    "Class-level @RangeIndex on ${fragmentClass.simpleName} must declare properties"
                )
            }
            specs += RangeIndexSpec(label, annotation.properties.map(onDisk), annotation.name.ifEmpty { null })
        }

        fragmentClass.getAnnotationsByType(FullTextIndex::class.java).forEach { annotation ->
            if (annotation.properties.isEmpty()) {
                throw DrivineException(
                    "Class-level @FullTextIndex on ${fragmentClass.simpleName} must declare properties"
                )
            }
            specs += FullTextIndexSpec(
                label,
                annotation.properties.map(onDisk),
                annotation.name.ifEmpty { null },
                annotation.analyzer.ifEmpty { null },
            )
        }

        fragmentClass.getAnnotationsByType(Unique::class.java).forEach { annotation ->
            if (annotation.properties.isEmpty()) {
                throw DrivineException(
                    "Class-level @Unique on ${fragmentClass.simpleName} must declare properties"
                )
            }
            specs += UniquenessConstraintSpec(label, annotation.properties.map(onDisk), annotation.name.ifEmpty { null })
        }

        return specs
    }

    // ----- Property-level declarations -----

    private fun propertyLevelSpecs(
        fragmentClass: Class<*>,
        label: String,
        dimensionProvider: VectorDimensionProvider?,
        onDisk: (String) -> String,
    ): List<SchemaItemSpec> {
        val specs = mutableListOf<SchemaItemSpec>()

        annotatedProperties(fragmentClass).forEach { (fieldName, annotations) ->
            // Error messages name the Kotlin field; the spec targets its on-disk property name.
            val property = onDisk(fieldName)
            annotations.forEach { annotation ->
                val spec: SchemaItemSpec? = when (annotation) {
                    is VectorIndex -> {
                        val dimensions = dimensionProvider?.dimensionsFor(label, property)
                            ?: throw DrivineException(
                                "@VectorIndex on ${fragmentClass.simpleName}.$fieldName requires a " +
                                    "VectorDimensionProvider — vector dimensions come from the embedding " +
                                    "model at runtime, not the annotation"
                            )
                        VectorIndexSpec(
                            label = label,
                            property = property,
                            dimensions = dimensions,
                            similarity = annotation.similarity,
                            name = annotation.name.ifEmpty { null },
                            // Annotation ints cannot be null; anything under 1 means "engine default"
                            hnswM = annotation.hnswM.takeIf { it > 0 },
                            hnswEfConstruction = annotation.hnswEfConstruction.takeIf { it > 0 },
                        )
                    }

                    is RangeIndex -> {
                        requireNoProperties(annotation.properties, "@RangeIndex", fragmentClass, fieldName)
                        RangeIndexSpec(label, property, annotation.name.ifEmpty { null })
                    }

                    is FullTextIndex -> {
                        requireNoProperties(annotation.properties, "@FullTextIndex", fragmentClass, fieldName)
                        FullTextIndexSpec(
                            label,
                            property,
                            annotation.name.ifEmpty { null },
                            annotation.analyzer.ifEmpty { null },
                        )
                    }

                    is Unique -> {
                        requireNoProperties(annotation.properties, "@Unique", fragmentClass, fieldName)
                        UniquenessConstraintSpec(label, property, annotation.name.ifEmpty { null })
                    }

                    else -> null
                }
                if (spec != null) {
                    specs += spec
                }
            }
        }

        return specs
    }

    private fun requireNoProperties(
        properties: Array<String>,
        annotationName: String,
        fragmentClass: Class<*>,
        propertyName: String,
    ) {
        if (properties.isNotEmpty()) {
            throw DrivineException(
                "Property-level $annotationName on ${fragmentClass.simpleName}.$propertyName must not declare " +
                    "properties — use a class-level $annotationName for composite declarations"
            )
        }
    }

    /**
     * Collects schema annotations per property. Kotlin reflection first (covers properties and
     * their backing fields), then Java field reflection as fallback.
     */
    private fun annotatedProperties(clazz: Class<*>): Map<String, List<Annotation>> {
        kotlinAnnotatedProperties(clazz)?.let { return it }
        return javaAnnotatedFields(clazz)
    }

    private fun kotlinAnnotatedProperties(clazz: Class<*>): Map<String, List<Annotation>>? = try {
        clazz.kotlin.memberProperties
            .associate { property ->
                val annotations = (property.annotations + (property.javaField?.annotations?.toList() ?: emptyList()))
                    .distinct()
                    .filter { isSchemaAnnotation(it) }
                property.name to annotations
            }
            .filterValues { it.isNotEmpty() }
    } catch (e: Throwable) {
        // Java class or unsupported construct — fall back to Java reflection
        null
    }

    private fun javaAnnotatedFields(clazz: Class<*>): Map<String, List<Annotation>> {
        val result = mutableMapOf<String, List<Annotation>>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                val annotations = field.annotations.filter { isSchemaAnnotation(it) }
                if (annotations.isNotEmpty() && !result.containsKey(field.name)) {
                    result[field.name] = annotations
                }
            }
            current = current.superclass
        }
        return result
    }

    private fun isSchemaAnnotation(annotation: Annotation): Boolean =
        annotation is VectorIndex || annotation is RangeIndex ||
            annotation is FullTextIndex || annotation is Unique
}