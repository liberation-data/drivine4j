package org.drivine.connection

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CypherCompile.oracle] asks whether the store keeps each of its functions only of a statement that calls it, and
 * compiles again, calling none of those it doesn't keep, where it keeps one the statement calls not.
 */
class CypherCompileTest {

    private val graph = "g"
    private val nullsEqual = "${graph}_nulls_equal("
    private val era = "${graph}_era("

    /** Compares a list that may hold a null in a reduce's step, which calls no function of its own: the store's walks it. */
    private val comparing = "MATCH (c:Claim) WITH [c.a, null] AS l RETURN reduce(acc = true, x IN [[1]] | acc AND x = l) AS same"

    /** A moment's arithmetic, which the store's era functions read and write. */
    private val moments = "RETURN datetime.truncate('second', datetime('2017-10-11T12:31:14+01:00'), {hour: 3}).hour AS h"

    /** [cypher] compiled with each function kept as [kept] says, and which of them was asked of. */
    private fun compiled(cypher: String, parameters: Map<String, Any?>, kept: Boolean): Pair<String, List<String>> {
        val asked = mutableListOf<String>()
        val sql = CypherCompile.oracle(
            graph, cypher, parameters,
            nullsEqualStored = { asked += "nullsEqual"; kept },
            eraStored = { asked += "era"; kept },
        ).sql
        return sql to asked
    }

    @Test
    fun `a statement that calls neither function asks of neither`() {
        val (sql, asked) = compiled("MATCH (n:Person) RETURN n.name", emptyMap(), kept = false)
        assertEquals(emptyList(), asked)
        assertFalse(nullsEqual in sql || era in sql, sql)
    }

    @Test
    fun `a statement that compares lists asks of the nulls-equal function alone, and calls it only where it is kept`() {
        val (kept, keptAsked) = compiled(comparing, emptyMap(), kept = true)
        assertEquals(listOf("nullsEqual"), keptAsked)
        assertTrue(nullsEqual in kept, kept)
        val (missing, missingAsked) = compiled(comparing, emptyMap(), kept = false)
        assertEquals(listOf("nullsEqual"), missingAsked)
        assertFalse(nullsEqual in missing, missing)
    }

    @Test
    fun `a statement of moments asks of the era functions alone, and calls them only where they are kept`() {
        val (kept, keptAsked) = compiled(moments, emptyMap(), kept = true)
        assertEquals(listOf("era"), keptAsked)
        assertTrue(era in kept, kept)
        val (missing, missingAsked) = compiled(moments, emptyMap(), kept = false)
        assertEquals(listOf("era"), missingAsked)
        assertFalse(era in missing, missing)
    }

    @Test
    fun `each function is asked of apart, so a store with one and not the other keeps calling the one`() {
        val cypher = "MATCH (c:Claim) WITH [c.a, null] AS l RETURN reduce(acc = true, x IN [[1]] | acc AND x = l) AS same, " +
            "datetime.truncate('second', datetime('2017-10-11T12:31:14+01:00'), {hour: 3}).hour AS h"
        val asked = mutableListOf<String>()
        // The era functions kept, nulls-equal not.
        val sql = CypherCompile.oracle(graph, cypher, emptyMap(), { asked += "nullsEqual"; false }, { asked += "era"; true }).sql
        assertEquals(listOf("nullsEqual", "era"), asked)
        assertFalse(nullsEqual in sql, sql)
        assertTrue(era in sql, sql)
        // And the other way about.
        val other = CypherCompile.oracle(graph, cypher, emptyMap(), { true }, { false }).sql
        assertTrue(nullsEqual in other, other)
        assertFalse(era in other, other)
    }
}
