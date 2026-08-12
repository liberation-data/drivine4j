package org.drivine.query

import org.drivine.DrivineException
import org.drivine.annotation.FullTextIndex
import org.drivine.annotation.GraphProperty
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.FullTextQuerySpec
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * Resolves which full-text index a `loadMatching` call should search, from the `@FullTextIndex`
 * annotations already declared on the root fragment — the same annotations the schema feature uses
 * to *create* the index (see [org.drivine.schema.FragmentSchemaScanner]).
 *
 * A fragment with a single full-text index needs no argument; it is inferred. When a fragment
 * carries several the caller names one of its covered properties (a field name) to disambiguate —
 * the mirror of how [VectorIndexResolver] disambiguates multiple embeddings.
 *
 * `@FullTextIndex` supports two placements: property-level (a single-property index on the annotated
 * property) and class-level (`@Repeatable`, a multi-property index over `properties`). Both are
 * enumerated here. Label and default-name derivation mirror [org.drivine.schema.FullTextIndexSpec]
 * exactly (`${label}_${onDiskProperties joined by _}_fulltext`), so the resolved name matches what
 * was created on engines that key indexes by name (Neo4j / Memgraph).
 */
internal object FullTextIndexResolver {

    private data class FullTextIndexInfo(
        /** The Kotlin/declared field names the caller may pass to disambiguate. */
        val fieldNames: List<String>,
        /** On-disk property names (`@GraphProperty`), used for the default index-name derivation. */
        val onDiskNames: List<String>,
        /** Explicit annotation name, or empty to derive one. */
        val name: String,
    )

    fun resolve(
        fragmentClass: Class<*>,
        property: String?,
        queryParam: String,
        topKParam: String,
    ): FullTextQuerySpec {
        val candidates = fullTextIndexes(fragmentClass)
        if (candidates.isEmpty()) {
            throw DrivineException(
                "Cannot run a full-text search on ${fragmentClass.simpleName}: " +
                    "no @FullTextIndex declared on the root fragment."
            )
        }

        val chosen = when {
            property != null -> candidates.find { property in it.fieldNames }
                ?: throw DrivineException(
                    "Property '$property' on ${fragmentClass.simpleName} is not covered by any @FullTextIndex. " +
                        "Full-text-indexed properties: ${candidates.flatMap { it.fieldNames }.distinct().joinToString()}."
                )

            candidates.size == 1 -> candidates.single()

            else -> throw DrivineException(
                "${fragmentClass.simpleName} has multiple @FullTextIndex indexes " +
                    "(${candidates.joinToString { it.fieldNames.joinToString("+") }}); " +
                    "pass one of the covered properties to search explicitly."
            )
        }

        val label = FragmentModel.labelsFor(fragmentClass).firstOrNull() ?: fragmentClass.simpleName
        // The index was created on the on-disk property names (see FragmentSchemaScanner), so the
        // derived name must use them too — otherwise a @GraphProperty-renamed field can't be found.
        // ifBlank, not ifEmpty: this must resolve exactly as SchemaItemSpec.effectiveName does on the
        // create side, or a search looks for an index name that was never created.
        val indexName = chosen.name.ifBlank { "${label}_${chosen.onDiskNames.joinToString("_")}_fulltext" }

        return FullTextQuerySpec(
            label = label,
            indexName = indexName,
            queryParam = queryParam,
            topKParam = topKParam,
        )
    }

    /** Class-level (multi-property) indexes first, then property-level (single-property) ones. */
    private fun fullTextIndexes(clazz: Class<*>): List<FullTextIndexInfo> {
        val onDisk = onDiskNameResolver(clazz)
        val result = mutableListOf<FullTextIndexInfo>()

        // Class-level @FullTextIndex is @Repeatable — one multi-property index each.
        clazz.getAnnotationsByType(FullTextIndex::class.java).forEach { annotation ->
            if (annotation.properties.isNotEmpty()) {
                result += FullTextIndexInfo(
                    fieldNames = annotation.properties.toList(),
                    onDiskNames = annotation.properties.map(onDisk),
                    name = annotation.name,
                )
            }
        }

        // Property-level @FullTextIndex — a single-property index on each annotated property.
        propertyLevelFields(clazz).forEach { (fieldName, annotation) ->
            result += FullTextIndexInfo(
                fieldNames = listOf(fieldName),
                onDiskNames = listOf(onDisk(fieldName)),
                name = annotation.name,
            )
        }

        return result
    }

    /** Field name → on-disk property name (`@GraphProperty` value, else the field name itself). */
    private fun onDiskNameResolver(clazz: Class<*>): (String) -> String {
        val overrides = mutableMapOf<String, String>()
        runCatching {
            clazz.kotlin.memberProperties.forEach { prop ->
                val gp = prop.annotations.filterIsInstance<GraphProperty>().firstOrNull()
                    ?: prop.javaField?.getAnnotation(GraphProperty::class.java)
                if (gp != null) overrides[prop.name] = gp.value
            }
        }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                field.getAnnotation(GraphProperty::class.java)?.let { overrides.putIfAbsent(field.name, it.value) }
            }
            current = current.superclass
        }
        return { fieldName -> overrides[fieldName] ?: fieldName }
    }

    /** Property-level `@FullTextIndex` (empty `properties`), from Kotlin properties then Java fields. */
    private fun propertyLevelFields(clazz: Class<*>): List<Pair<String, FullTextIndex>> {
        val result = mutableListOf<Pair<String, FullTextIndex>>()
        runCatching {
            clazz.kotlin.memberProperties.forEach { prop ->
                val annotation = prop.annotations.filterIsInstance<FullTextIndex>().firstOrNull()
                    ?: prop.javaField?.annotations?.filterIsInstance<FullTextIndex>()?.firstOrNull()
                if (annotation != null && annotation.properties.isEmpty()) {
                    result += prop.name to annotation
                }
            }
        }
        if (result.isNotEmpty()) return result

        // Java fallback — walk declared fields up the hierarchy.
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                field.getAnnotation(FullTextIndex::class.java)?.let { annotation ->
                    if (annotation.properties.isEmpty() && result.none { it.first == field.name }) {
                        result += field.name to annotation
                    }
                }
            }
            current = current.superclass
        }
        return result
    }
}