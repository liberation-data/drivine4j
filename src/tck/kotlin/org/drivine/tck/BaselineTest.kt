package org.drivine.tck

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate and the ratchet: a run held to a [Baseline] scenario by scenario, and a baseline written from a run. No
 * database: the rows are made here.
 */
class BaselineTest {

    private val tck = "openCypher TCK 2024.2, tck-api 1.0.0-M23"
    private val match = "clauses/match/Match1.feature"
    private val ret = "clauses/return/Return1.feature"

    private fun row(file: String, number: Int, outcome: Outcome, example: Int? = null) = Row(
        store = "sqlite", file = file, feature = file, number = number, name = "scenario $number", example = example,
        outcome = outcome, kind = "", message = "", query = "RETURN 1", ms = 0, errorSource = null, strict = outcome == Outcome.PASS,
    )

    private val baseline = Baseline("sqlite", tck, 4, mapOf(match to setOf("1", "2:0"), ret to setOf("1")))
    private val tckKeys = setOf("$match#1", "$match#2:0", "$match#2:1", "$ret#1")

    @Test
    fun `a whole run that passes what the baseline passes does not fail`() {
        val c = Comparison.of(baseline, listOf(row(match, 1, Outcome.PASS), row(match, 2, Outcome.PASS, 0), row(ret, 1, Outcome.PASS)), tckKeys)
        assertFalse(c.failed)
        assertTrue(c.gains.isEmpty())
    }

    @Test
    fun `a scenario that passes in the baseline and times out or is a harness limitation now is a regression`() {
        val c = Comparison.of(
            baseline,
            listOf(row(match, 1, Outcome.CRASH), row(match, 2, Outcome.HARNESS, 0), row(ret, 1, Outcome.PASS)),
            tckKeys,
        )
        assertTrue(c.failed)
        assertEquals(listOf("1", "2:0"), c.regressions.map { it.key })
        assertTrue(c.describe().contains("REGRESSED $match #2, example 0"))
    }

    @Test
    fun `a scenario a filter left out is neither a regression nor a gain`() {
        val c = Comparison.of(baseline, listOf(row(ret, 1, Outcome.PASS)), tckKeys)
        assertFalse(c.failed)
        assertTrue(c.gains.isEmpty())
    }

    @Test
    fun `a scenario of the baseline that the TCK no longer has fails the gate`() {
        val c = Comparison.of(baseline, listOf(row(match, 1, Outcome.PASS), row(ret, 1, Outcome.PASS)), tckKeys - "$match#2:0")
        assertTrue(c.failed)
        assertEquals(listOf("$match#2:0"), c.missing)
    }

    @Test
    fun `a newly passing scenario is a gain and does not fail the gate`() {
        val c = Comparison.of(
            baseline,
            listOf(row(match, 1, Outcome.PASS), row(match, 2, Outcome.PASS, 0), row(match, 2, Outcome.PASS, 1), row(ret, 1, Outcome.PASS)),
            tckKeys,
        )
        assertFalse(c.failed)
        assertEquals(listOf("2:1"), c.gains.map { it.key })
    }

    @Test
    fun `a baseline refuses a run of another TCK version`() {
        assertNull(baseline.refusal(tck))
        assertNotNull(baseline.refusal("openCypher TCK 2024.3, tck-api 1.0.0-M23"))
    }

    @Test
    fun `the ratchet keeps the passes of a run, and a written baseline reads back the same`() {
        val rows = listOf(
            row(match, 10, Outcome.PASS), row(match, 2, Outcome.PASS, 1), row(match, 2, Outcome.PASS, 0), row(match, 3, Outcome.WRONG_RESULT),
            row(ret, 1, Outcome.REFUSED),
        )
        val next = Baseline.of("sqlite", tck, rows)
        assertEquals(5, next.scenarios)
        assertEquals(3, next.passing)
        assertEquals(mapOf(match to setOf("10", "2:0", "2:1")), next.features)

        val file = File.createTempFile("tck-baseline", ".json").also { it.deleteOnExit() }
        next.write(file)
        assertTrue(file.readText().contains("\"$match\": [\"2:0\", \"2:1\", \"10\"]"))
        val read = assertNotNull(Baseline.read(file))
        assertEquals(next.store, read.store)
        assertEquals(next.tck, read.tck)
        assertEquals(next.scenarios, read.scenarios)
        assertEquals(next.features, read.features)
    }
}
