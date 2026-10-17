package org.drivine.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** What a view save holds the nodes it reaches through a relationship to. */
class StampingTest {

    @Test
    fun `a node reached through a relationship is written whichever it is, made or found, whatever the root is held to`() {
        assertEquals(Stamping(false), Stamping(false, root = RootMode.ABSENT, handsBack = true).unchecked())
        assertEquals(Stamping(false), Stamping(false, root = RootMode.FOUND).unchecked())
        assertEquals(Stamping(false), Stamping(true, relationships = true).unchecked())
    }

    @Test
    fun `an unchecked stamping of either root is the same for the nodes it reaches`() {
        val stamping = Stamping(false, handsBack = true)

        assertSame(stamping, stamping.unchecked())
    }
}
