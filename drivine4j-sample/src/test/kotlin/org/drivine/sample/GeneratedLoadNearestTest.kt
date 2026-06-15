package org.drivine.sample

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the codegen emits the filtered `loadNearest(vector, topK, threshold, spec)` view extension
 * — and only for views whose root fragment is `@VectorIndex`-ed (the gating decision). Asserts on the
 * actual KSP-generated sources for `SampleVectorView` (vector-indexed) and `RaisedAndAssignedIssue`
 * (not).
 */
class GeneratedLoadNearestTest {

    private fun generatedSource(queryDslName: String): String {
        val file = File(".").walkTopDown()
            .firstOrNull { it.name == "$queryDslName.kt" && it.path.contains("generated") }
            ?: error("generated $queryDslName.kt not found under build/generated — has KSP run?")
        return file.readText()
    }

    @Test
    fun `a vector-indexed view gets a generated loadNearest extension`() {
        val src = generatedSource("SampleVectorViewQueryDsl")
        val flat = src.replace(Regex("\\s+"), " ") // KotlinPoet may line-wrap
        assertTrue(
            flat.contains("fun <reified T : SampleVectorView> GraphObjectManager.loadNearest"),
            "expected a generated loadNearest extension:\n$src",
        )
        // delegates to the manager method with the view's INSTANCE injected
        assertTrue(
            flat.contains("loadNearest(T::class.java, SampleVectorViewQueryDsl.INSTANCE, vector, topK, threshold, spec)"),
            src,
        )
    }

    @Test
    fun `a non-vector view gets no loadNearest extension (gating)`() {
        val src = generatedSource("RaisedAndAssignedIssueQueryDsl")
        assertFalse(src.contains("loadNearest"), "non-vector view must not get a loadNearest extension")
    }

    @Test
    fun `every view gets a generated count extension that injects INSTANCE`() {
        // count isn't gated — it applies to any view, vector-indexed or not.
        val src = generatedSource("RaisedAndAssignedIssueQueryDsl")
        val flat = src.replace(Regex("\\s+"), " ") // KotlinPoet may line-wrap the delegation
        assertTrue(flat.contains("GraphObjectManager.count"), "expected a generated count extension:\n$src")
        assertTrue(flat.contains("count(T::class.java, RaisedAndAssignedIssueQueryDsl.INSTANCE, spec)"), src)
    }
}
