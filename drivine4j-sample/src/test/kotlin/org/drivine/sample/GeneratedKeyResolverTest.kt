package org.drivine.sample

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the KSP codegen emits the model-aware key resolver into a `<Fragment>QueryDsl`: it
 * implements `ResolvableNodeReference` and its `fieldKeyPaths` / `bagPrefixes` map a promoted
 * `@GraphProperty` field (by both Kotlin and on-disk name) and the `@PropertyBag` prefix. Asserts on the
 * actual generated source for `SampleResolverNode`.
 */
class GeneratedKeyResolverTest {

    private fun generatedSource(queryDslName: String): String {
        val file = File(".").walkTopDown()
            .firstOrNull { it.name == "$queryDslName.kt" && it.path.contains("generated") }
            ?: error("generated $queryDslName.kt not found under build/generated — has KSP run?")
        return file.readText()
    }

    @Test
    fun `a mixed GraphProperty + PropertyBag fragment emits a ResolvableNodeReference resolver`() {
        val src = generatedSource("SampleResolverNodeQueryDsl")
        val flat = src.replace(Regex("\\s+"), " ")

        assertTrue(flat.contains(": ResolvableNodeReference"), "must implement ResolvableNodeReference:\n$src")

        // A promoted @GraphProperty field resolves by BOTH its Kotlin name and its on-disk name.
        assertTrue(flat.contains("\"sectionId\" to \"section_id\""), "Kotlin-name → on-disk mapping missing:\n$src")
        assertTrue(flat.contains("\"section_id\" to \"section_id\""), "on-disk self-mapping missing:\n$src")
        assertTrue(flat.contains("\"id\" to \"id\""), "plain field mapping missing:\n$src")

        // The @PropertyBag prefix is the sole bag prefix; the bag field is NOT a resolvable field key.
        assertTrue(flat.contains("bagPrefixes: List<String> = listOf(\"metadata.\")"), "bag prefix missing:\n$src")
        assertTrue(!flat.contains("\"metadata\" to"), "the bag field must not be a fieldKeyPaths entry:\n$src")
    }
}
