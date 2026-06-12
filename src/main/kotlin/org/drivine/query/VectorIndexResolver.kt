package org.drivine.query

import org.drivine.DrivineException
import org.drivine.annotation.VectorIndex
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.VectorQuerySpec
import org.drivine.schema.SimilarityFunction
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * Resolves which vector index a `loadNearest` call should search, from the `@VectorIndex`
 * annotations already declared on the root fragment — the same annotations the schema feature
 * uses to *create* the index (see [org.drivine.schema.FragmentSchemaScanner]).
 *
 * The common case — a fragment with a single embedding — needs no property argument; it is
 * inferred. When a fragment carries several embeddings the caller names one to disambiguate.
 *
 * Label and default-name derivation mirror [org.drivine.schema.VectorIndexSpec] exactly, so the
 * resolved name matches what was created on engines that key indexes by name (Neo4j / Memgraph).
 */
internal object VectorIndexResolver {

    private data class VectorProperty(
        val property: String,
        val similarity: SimilarityFunction,
        val name: String,
    )

    fun resolve(
        fragmentClass: Class<*>,
        property: String?,
        topKParam: String,
        vectorParam: String,
    ): VectorQuerySpec {
        val candidates = vectorProperties(fragmentClass)
        if (candidates.isEmpty()) {
            throw DrivineException(
                "Cannot run a vector search on ${fragmentClass.simpleName}: " +
                    "no @VectorIndex property declared on the root fragment."
            )
        }

        val chosen = when {
            property != null -> candidates.find { it.property == property }
                ?: throw DrivineException(
                    "Property '$property' on ${fragmentClass.simpleName} is not annotated with @VectorIndex. " +
                        "Annotated embedding properties: ${candidates.joinToString { it.property }}."
                )

            candidates.size == 1 -> candidates.single()

            else -> throw DrivineException(
                "${fragmentClass.simpleName} has multiple @VectorIndex properties " +
                    "(${candidates.joinToString { it.property }}); pass the property to search explicitly."
            )
        }

        val label = FragmentModel.labelsFor(fragmentClass).firstOrNull() ?: fragmentClass.simpleName
        val indexName = chosen.name.ifEmpty { "${label}_${chosen.property}_vector" }

        return VectorQuerySpec(
            label = label,
            property = chosen.property,
            indexName = indexName,
            similarity = chosen.similarity,
            topKParam = topKParam,
            vectorParam = vectorParam,
        )
    }

    /** Kotlin reflection first (constructor-property annotations), Java fields as fallback. */
    private fun vectorProperties(clazz: Class<*>): List<VectorProperty> {
        kotlinVectorProperties(clazz)?.let { if (it.isNotEmpty()) return it }
        return javaVectorFields(clazz)
    }

    private fun kotlinVectorProperties(clazz: Class<*>): List<VectorProperty>? = try {
        clazz.kotlin.memberProperties.mapNotNull { prop ->
            val annotation = prop.annotations.filterIsInstance<VectorIndex>().firstOrNull()
                ?: prop.javaField?.annotations?.filterIsInstance<VectorIndex>()?.firstOrNull()
            annotation?.let { VectorProperty(prop.name, it.similarity, it.name) }
        }
    } catch (e: Throwable) {
        // Java class or unsupported construct — fall back to Java field reflection
        null
    }

    private fun javaVectorFields(clazz: Class<*>): List<VectorProperty> {
        val result = mutableListOf<VectorProperty>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                field.getAnnotation(VectorIndex::class.java)?.let { annotation ->
                    if (result.none { it.property == field.name }) {
                        result.add(VectorProperty(field.name, annotation.similarity, annotation.name))
                    }
                }
            }
            current = current.superclass
        }
        return result
    }
}