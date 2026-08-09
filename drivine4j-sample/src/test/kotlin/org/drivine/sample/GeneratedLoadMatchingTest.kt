package org.drivine.sample

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the codegen emits the filtered `loadMatching(query, topK, threshold, spec)` wrapper — and
 * only for types whose (root) fragment carries a `@FullTextIndex` (the gating decision). Asserts on
 * the actual KSP-generated sources for the full-text view + fragment (`SampleFullTextView` /
 * `SampleArticleNode`) and a non-full-text view (`RaisedAndAssignedIssue`). The full-text mirror of
 * [GeneratedLoadNearestTest].
 */
class GeneratedLoadMatchingTest {

    private fun generatedSource(queryDslName: String): String {
        val file = File(".").walkTopDown()
            .firstOrNull { it.name == "$queryDslName.kt" && it.path.contains("generated") }
            ?: error("generated $queryDslName.kt not found under build/generated — has KSP run?")
        return file.readText()
    }

    @Test
    fun `a full-text view gets a generated loadMatching extension`() {
        val src = generatedSource("SampleFullTextViewQueryDsl")
        val flat = src.replace(Regex("\\s+"), " ") // KotlinPoet may line-wrap
        assertTrue(
            flat.contains("fun <reified T : SampleFullTextView> GraphObjectManager.loadMatching"),
            "expected a generated loadMatching extension:\n$src",
        )
        assertTrue(
            flat.contains("loadMatching(T::class.java, SampleFullTextViewQueryDsl.INSTANCE, query, topK, threshold, spec)"),
            src,
        )
    }

    @Test
    fun `a full-text fragment gets a generated loadMatching extension`() {
        val src = generatedSource("SampleArticleNodeQueryDsl")
        val flat = src.replace(Regex("\\s+"), " ")
        assertTrue(
            flat.contains("fun <reified T : SampleArticleNode> GraphObjectManager.loadMatching"),
            "expected a generated loadMatching extension on the fragment DSL:\n$src",
        )
        assertTrue(
            flat.contains("loadMatching(T::class.java, SampleArticleNodeQueryDsl.INSTANCE, query, topK, threshold, spec)"),
            src,
        )
    }

    @Test
    fun `a non-full-text view gets no loadMatching extension (gating)`() {
        val src = generatedSource("RaisedAndAssignedIssueQueryDsl")
        assertFalse(src.contains("loadMatching"), "non-full-text view must not get a loadMatching extension")
    }
}