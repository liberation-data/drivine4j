package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PatchOutcomesTest {

    @Test
    fun `a node says whether it changed, and one that is not there says nothing`() {
        val outcomes = PatchOutcomes()

        val rows = outcomes.returned(listOf("0/1=a:x", "2/0=b:x"))

        assertEquals(mapOf(0 to true, 2 to false), outcomes.changed(rows))
    }

    @Test
    fun `a node an earlier attempt changed, and that still carries the stamp it left, was changed`() {
        val outcomes = PatchOutcomes()
        // The first statement went through, and the second was turned away with the transaction left standing.
        outcomes.returned(listOf("0/1=mine:x", "1/0=old:x"))

        // Run again, the first statement finds its own writes.
        val again = outcomes.returned(listOf("0/0=mine:x", "1/0=old:x")) + outcomes.returned(listOf("2/1=new:x"))

        assertEquals(mapOf(0 to true, 1 to false, 2 to true), outcomes.changed(again))
    }

    @Test
    fun `a node an attempt that was rolled back changed says for itself what the last attempt did`() {
        val outcomes = PatchOutcomes()
        outcomes.returned(listOf("0/1=mine:x", "1/1=mine2:x"))

        // Between the attempts another writer gave the first node the same values, and a stamp of its own.
        val again = outcomes.returned(listOf("0/0=theirs:x", "1/1=mine2:x"))

        assertEquals(mapOf(0 to false, 1 to true), outcomes.changed(again))
    }

    @Test
    fun `where two nodes share an id, one that changed says so for both`() {
        val outcomes = PatchOutcomes()

        assertEquals(mapOf(0 to true), outcomes.changed(outcomes.returned(listOf("0/0=a:x", "0/1=b:x"))))
    }

    @Test
    fun `rows are put in the order of their ids, as text`() {
        val rows = listOf("c", "a", 10, "b").mapIndexed { index, id -> PatchRow(index, id, emptyMap()) }

        assertEquals(listOf(10, "a", "b", "c"), PatchRow.inLockOrder(rows).map { it.id })
    }
}
