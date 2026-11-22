package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

/** The ids of a search among ids in the `@NodeId` type's own order, as the module reads their places (drivine4j#868, #412). */
class AmongOrderTest {

    @NodeFragment(labels = ["Texted"]) data class Texted(@NodeId val id: String)
    @NodeFragment(labels = ["Counted"]) data class Counted(@NodeId val id: Long)
    @NodeFragment(labels = ["Timed"]) data class Timed(@NodeId val id: LocalDateTime)
    @NodeFragment(labels = ["Keyed"]) data class Keyed(@NodeId val id: UUID)
    enum class Colour { RED, BLUE }
    @NodeFragment(labels = ["Coloured"]) data class Coloured(@NodeId val id: Colour)

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `a text by its code points, not its UTF-16 units`() {
        // U+FFFD is one UTF-16 unit above the surrogate pair of U+1F600, but a lower code point.
        val emoji = String(Character.toChars(0x1F600))
        assertEquals(listOf("a", "b", "\uFFFD", emoji), AmongOrder.of(Texted::class.java, listOf(emoji, "\uFFFD", "b", "a"), mapper))
        assertTrue("\uFFFD" > emoji, "Kotlin's own order puts the emoji first")
    }

    @Test
    fun `a number by its value, and an id that is no number last, in the order given`() {
        assertEquals(listOf("1", "2", "10", "abc", "x"), AmongOrder.of(Counted::class.java, listOf("10", "abc", "2", "x", "1"), mapper))
    }

    @Test
    fun `a temporal by its time, whatever text it is given as`() {
        assertEquals(
            listOf("2030-01-01T09:00", "2030-01-01T10:00:00", "2030-01-01T10:00:30"),
            AmongOrder.of(Timed::class.java, listOf("2030-01-01T10:00:30", "2030-01-01T09:00", "2030-01-01T10:00:00"), mapper),
        )
    }

    @Test
    fun `a UUID and an enum by their text, as a graph engine holds them`() {
        val low = "00000000-0000-0000-0000-000000000001"
        val high = "f0000000-0000-0000-0000-000000000000" // first by UUID.compareTo, which compares signed halves
        assertEquals(listOf(low, high), AmongOrder.of(Keyed::class.java, listOf(high, low), mapper))
        assertEquals(listOf("BLUE", "RED"), AmongOrder.of(Coloured::class.java, listOf("RED", "BLUE"), mapper))
    }
}
