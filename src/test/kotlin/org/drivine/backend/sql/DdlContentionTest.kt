package org.drivine.backend.sql

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Schema statements a SQL store turned away because of another session's, and run again (#450). */
class DdlContentionTest {

    private fun oracle(code: Int, message: String) = SQLException("ORA-${code.toString().padStart(5, '0')}: $message", "61000", code)

    private val libraryCacheDeadlock = oracle(4020, "deadlock detected while trying to lock object DRIVINE.DRIVINE_NODE")
    private val busy = oracle(54, "resource busy and acquire with NOWAIT specified or timeout expired")
    private val rowDeadlock = oracle(60, "deadlock detected while waiting for resource")
    private val pgDeadlock = SQLException("ERROR: deadlock detected", "40P01")
    private val pgLockNotHad = SQLException("ERROR: canceling statement due to lock timeout", "55P03")
    private val denied = oracle(1031, "insufficient privileges")

    @Test
    fun `Oracle's library cache deadlock and row deadlock are contention, under a cause or not`() {
        for (failure in listOf(rowDeadlock, libraryCacheDeadlock)) {
            assertTrue(DdlContention.contended(failure), failure.message)
            assertTrue(DdlContention.contended(DrivineException("The statement failed.", failure)), failure.message)
            assertTrue(DdlContention.contended(RuntimeException(DrivineException("The statement failed.", failure))), failure.message)
        }
    }

    @Test
    fun `Postgres's deadlock is contention`() {
        assertTrue(DdlContention.contended(pgDeadlock))
        assertTrue(DdlContention.contended(DrivineException("The statement failed.", pgDeadlock)))
    }

    /**
     * Oracle's block waits out a busy lock itself, for as long as it allows: run again on it, a first connection, and an
     * application's start, would wait many times that long. Nor is a lock not had in time on Postgres run again.
     */
    @Test
    fun `a busy lock, waited out already, is not contention`() {
        assertFalse(DdlContention.contended(busy))
        assertFalse(DdlContention.contended(pgLockNotHad))
        val failing = Failing(busy)
        assertSame(busy, assertFailsWith<SQLException> { DdlContention.whenNotContended(noPause) { failing.run() } })
        assertEquals(1, failing.runs)
    }

    @Test
    fun `another failure is not contention`() {
        assertFalse(DdlContention.contended(denied))
        assertFalse(DdlContention.contended(SQLException("permission denied for schema public", "42501")))
        assertFalse(DdlContention.contended(SQLException("serialization failure", "40001")))
        assertFalse(DdlContention.contended(IllegalStateException("ORA-04020 in a message, and no SQL error")))
        assertFalse(DdlContention.contended(null))
    }

    /** Runs a statement that fails with each of [failures] in turn, and then succeeds; counts its runs. */
    private class Failing(private vararg val failures: Exception) {
        var runs = 0
        fun run(): String {
            val failure = failures.getOrNull(runs++)
            if (failure != null) throw failure
            return "done"
        }
    }

    private val noPause: (Int) -> Long = { 0L }

    @Test
    fun `a statement turned away by another session's is run again until it is let through`() {
        val failing = Failing(libraryCacheDeadlock, pgDeadlock, rowDeadlock)
        assertEquals("done", DdlContention.whenNotContended(noPause) { failing.run() })
        assertEquals(4, failing.runs)
    }

    @Test
    fun `a statement that fails for another reason is run once, and its failure thrown`() {
        val failing = Failing(denied)
        assertSame(denied, assertFailsWith<SQLException> { DdlContention.whenNotContended(noPause) { failing.run() } })
        assertEquals(1, failing.runs)
    }

    @Test
    fun `a statement turned away every time is run as many times as allowed, and the first failure thrown with the later ones in it`() {
        val failures = List(DdlContention.ATTEMPTS + 1) { oracle(4020, "deadlock $it") }
        val failing = Failing(*failures.toTypedArray())
        val thrown = assertFailsWith<SQLException> { DdlContention.whenNotContended(noPause) { failing.run() } }
        assertSame(failures.first(), thrown)
        assertEquals(DdlContention.ATTEMPTS, failing.runs)
        assertEquals(failures.subList(1, DdlContention.ATTEMPTS), thrown.suppressed.toList().dropLast(1))
        assertTrue(DdlContention.gaveUp(thrown))
        assertTrue(DdlContention.gaveUp(DrivineException("The statement failed.", thrown)))
        assertFalse(DdlContention.contended(thrown), "a failure given up on is not run again by a retry outside")
    }

    @Test
    fun `a failure given up on is not run again by a retry outside, on another thread or after it`() {
        val inner = Failing(*Array(DdlContention.ATTEMPTS) { oracle(4020, "deadlock $it") })
        val gaveUp = assertFailsWith<SQLException> { DdlContention.whenNotContended(noPause) { inner.run() } }
        var outerRuns = 0
        assertSame(gaveUp, assertFailsWith<SQLException> { DdlContention.whenNotContended(noPause) { outerRuns++; throw gaveUp } })
        assertEquals(1, outerRuns)
    }

    /** As the provider's first connection is, asked for by an object manager making the tables itself. */
    @Test
    fun `a run within another on the same thread is run once, and the run outside retries`() {
        val inner = Failing(*Array(DdlContention.ATTEMPTS * DdlContention.ATTEMPTS) { oracle(4020, "deadlock $it") })
        assertFailsWith<SQLException> {
            DdlContention.whenNotContended(noPause) { DdlContention.whenNotContended(noPause) { inner.run() } }
        }
        assertEquals(DdlContention.ATTEMPTS, inner.runs)

        // And once the run outside is done, a run is retried again.
        val after = Failing(oracle(4020, "deadlock after"))
        assertEquals("done", DdlContention.whenNotContended(noPause) { after.run() })
        assertEquals(2, after.runs)
    }

    @Test
    fun `the pause between attempts grows with each attempt`() {
        val pauses = mutableListOf<Int>()
        val failing = Failing(rowDeadlock, rowDeadlock, rowDeadlock)
        DdlContention.whenNotContended({ pauses += it; 0L }) { failing.run() }
        assertEquals(listOf(0, 1, 2), pauses)
    }

    /** As two pools' first connections and first writes each wait on the other's lock on the node table (#450). */
    @Test
    fun `Oracle's block is run again when another session turned it away, and once when it failed for another reason`() {
        val graph = OracleGraph("tenant_a")
        val ran = mutableListOf<String>()
        val failing = Failing(libraryCacheDeadlock, rowDeadlock)
        graph.ensure { ran += it; failing.run(); emptyList() }
        assertEquals(List(3) { graph.ensuring }, ran)

        val refused = Failing(denied)
        assertSame(denied, assertFailsWith<SQLException> { graph.ensure { refused.run(); emptyList() } })
        assertEquals(1, refused.runs)
    }

    @Test
    fun `Postgres's block is run again after a deadlock`() {
        val graph = PostgresGraph("tenant_a")
        val ran = mutableListOf<String>()
        val failing = Failing(pgDeadlock)
        graph.ensure { ran += it; failing.run(); emptyList() }
        assertEquals(List(2) { graph.ensuring }, ran)
    }
}
