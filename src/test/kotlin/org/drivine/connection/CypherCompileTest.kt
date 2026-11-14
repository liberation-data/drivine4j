package org.drivine.connection

import org.drivine.cypher.store.OracleSql as StoreSql
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
            temporalArithmeticStored = { asked += "arithmetic"; kept },
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
    fun `arithmetic of values read from the store asks of the temporal arithmetic function alone, and calls it only where it is kept`() {
        // drivine4j#418: a store without it, as one an earlier version made, has the statement fail by name as it runs.
        val cypher = "MATCH (n:Claim) RETURN n.at + n.d AS later"
        val function = "${graph}_temporal_arith("
        val asked = mutableListOf<String>()
        val kept = CypherCompile.oracle(graph, cypher, emptyMap(), { asked += "nullsEqual"; true }, { asked += "era"; true }, { asked += "arithmetic"; true }).sql
        assertEquals(listOf("arithmetic"), asked)
        assertTrue(function in kept, kept)
        val missing = CypherCompile.oracle(graph, cypher, emptyMap(), { true }, { true }, { false }).sql
        assertFalse(function in missing, missing)
        assertTrue("compiled to SQL yet (drivine4j#418)" in missing, missing)
        // A statement that does no such arithmetic doesn't ask of it.
        asked.clear()
        CypherCompile.oracle(graph, "MATCH (n:Claim) RETURN n.a + 1 AS b, duration('P1D') * 2 AS d", emptyMap(), { true }, { true }, { asked += "arithmetic"; true })
        assertEquals(listOf("arithmetic"), asked, "n.a + 1 may be a tag's, so the statement calls the function")
        asked.clear()
        CypherCompile.oracle(graph, "MATCH (n:Claim) RETURN size(n.l) + 1 AS b", emptyMap(), { true }, { true }, { asked += "arithmetic"; true })
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `each function is asked of apart, so a store with one and not the other keeps calling the one`() {
        val cypher = "MATCH (c:Claim) WITH [c.a, null] AS l RETURN reduce(acc = true, x IN [[1]] | acc AND x = l) AS same, " +
            "datetime.truncate('second', datetime('2017-10-11T12:31:14+01:00'), {hour: 3}).hour AS h"
        val asked = mutableListOf<String>()
        // The era functions kept, nulls-equal not.
        val sql = CypherCompile.oracle(graph, cypher, emptyMap(), { asked += "nullsEqual"; false }, { asked += "era"; true }, { true }).sql
        assertEquals(listOf("nullsEqual", "era"), asked)
        assertFalse(nullsEqual in sql, sql)
        assertTrue(era in sql, sql)
        // And the other way about.
        val other = CypherCompile.oracle(graph, cypher, emptyMap(), { true }, { false }, { true }).sql
        assertTrue(nullsEqual in other, other)
        assertFalse(era in other, other)
    }

    @Test
    fun `a statement compiled again, for a store without a function it calls, is still read through the graph's indexes`() {
        // A keyed MATCH that compares lists: compiled again for a store with no nulls-equal function (#805).
        val cypher = "MATCH (c:Claim {id: \$id}) WITH [c.a, null] AS l RETURN reduce(acc = true, x IN [[1]] | acc AND x = l) AS same"
        val throughIndex = StoreSql.labelledIndex("n1", "Claim", "id")
        for (kept in listOf(true, false)) {
            val sql = CypherCompile.oracle(graph, cypher, mapOf("id" to "a"), { kept }, { kept }, { kept }, indexedProperties = setOf("Claim" to "id")).sql
            assertTrue(throughIndex.all { it in sql }, sql)
            assertEquals(kept, nullsEqual in sql, sql)
        }
        val unindexed = CypherCompile.oracle(graph, cypher, mapOf("id" to "a"), { true }, { true }, { true }).sql
        assertFalse(throughIndex.any { it in unindexed }, unindexed)
    }
}
