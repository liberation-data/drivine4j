package org.drivine.query.dsl

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Each Kotlin context-parameter operator (compiled as `<name>Context`) must not share its name with a
 * plain member. From Kotlin 2.3 a context parameter no longer breaks the tie between two same-named
 * members, so `orderBy { x.asc() }` with both an `asc(): OrderSpec` and a context `asc()` stopped
 * compiling for consumers. This build compiles on 2.2, which still breaks the tie, so the rule is
 * checked here rather than by the compiler.
 */
class OneNamePerLanguageTest {

    private fun clashes(type: Class<*>): List<String> {
        val kotlinNames = type.methods.map { it.name }.filter { it.endsWith("Context") }.map { it.removeSuffix("Context") }
        val plainNames = type.methods.map { it.name }.toSet()
        return kotlinNames.filter { it in plainNames }
    }

    @Test
    fun `no Java operator on a property reference shares a name with a Kotlin one`() {
        assertTrue(clashes(PropertyReference::class.java).isEmpty(), "clashing: ${clashes(PropertyReference::class.java)}")
    }

    @Test
    fun `no Java operator on a string property reference shares a name with a Kotlin one`() {
        assertTrue(
            clashes(StringPropertyReference::class.java).isEmpty(),
            "clashing: ${clashes(StringPropertyReference::class.java)}",
        )
    }
}
