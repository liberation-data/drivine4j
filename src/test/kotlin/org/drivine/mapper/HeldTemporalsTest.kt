package org.drivine.mapper

import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The texts a read keeps with their tags ([HeldTemporals]): by identity, and only while the text is alive. */
class HeldTemporalsTest {

    private fun node(id: String): Map<String, Any?> =
        mapOf("labels" to listOf("Day"), "id" to mapOf("\$t" to "date", "v" to id, "s" to "x"))

    @Test
    fun `keeps a node's tag by the very text read, not by an equal one`() {
        val read = HeldTemporals.read(node(String("2026-01-05".toCharArray()))) as Map<*, *>
        val text = read["id"] as String
        assertEquals("date", assertNotNull(HeldTemporals.of(text)).word)
        assertNull(HeldTemporals.of(String(text.toCharArray())))
        assertNull(HeldTemporals.of("2026-01-05"))
    }

    @Test
    fun `lets a text read go once nothing else holds it`() {
        val gone = WeakReference((HeldTemporals.read(node(String("2026-01-06".toCharArray()))) as Map<*, *>)["id"] as String)
        repeat(50) {
            if (gone.get() == null) return@repeat
            System.gc()
            Thread.sleep(10)
        }
        assertTrue(gone.get() == null, "the text read was kept alive by what was held with it")
    }
}
