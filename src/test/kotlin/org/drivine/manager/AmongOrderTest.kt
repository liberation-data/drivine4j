package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID

/** The ids of a search among ids in the `@NodeId` type's own order, as the module reads their places (drivine4j#868, #412). */
class AmongOrderTest {

    @NodeFragment(labels = ["Texted"]) data class Texted(@NodeId val id: String)
    @NodeFragment(labels = ["Counted"]) data class Counted(@NodeId val id: Long)
    @NodeFragment(labels = ["Timed"]) data class Timed(@NodeId val id: LocalDateTime)
    @NodeFragment(labels = ["Keyed"]) data class Keyed(@NodeId val id: UUID)
    enum class Colour { RED, BLUE }
    @NodeFragment(labels = ["Coloured"]) data class Coloured(@NodeId val id: Colour)
    @NodeFragment(labels = ["Weighed"]) data class Weighed(@NodeId val id: Double)
    @NodeFragment(labels = ["Numbered"]) data class Numbered(@NodeId val id: Number)
    @NodeFragment(labels = ["Dated"]) data class Dated(@NodeId val id: LocalDate)
    @NodeFragment(labels = ["Zoned"]) data class Zoned(@NodeId val id: ZonedDateTime)
    @GraphView data class CountedView(@Root val counted: Counted)
    @NodeFragment(labels = ["Unkeyed"]) data class Unkeyed(val name: String)

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

    @Test
    fun `a view's ids in its root's id type's order`() {
        assertEquals(listOf("2", "10"), AmongOrder.of(CountedView::class.java, listOf("10", "2"), mapper))
    }

    @Test
    fun `two texts of one value keep the order given, so the first is the one the search reads`() {
        assertEquals(listOf("01", "1", "2"), AmongOrder.of(Counted::class.java, listOf("2", "01", "1"), mapper))
    }

    @Test
    fun `a double by its value, and a Number id of mixed classes by its value too`() {
        assertEquals(listOf("1.5", "2", "10"), AmongOrder.of(Weighed::class.java, listOf("10", "2", "1.5"), mapper))
        assertEquals(listOf("2", "2.5", "10"), AmongOrder.of(Numbered::class.java, listOf("10", "2.5", "2"), mapper))
    }

    @Test
    fun `a date given with a time is its date, as a lookup reads it, and text that is no date goes last`() {
        assertEquals(
            listOf("2030-01-01T00:00", "2030-01-01", "2030-01-02", "2030-13-01"),
            AmongOrder.of(Dated::class.java, listOf("2030-13-01", "2030-01-01T00:00", "2030-01-02", "2030-01-01"), mapper),
        )
    }

    @Test
    fun `a zoned time by its instant, not its text`() {
        assertEquals(
            listOf("2030-01-01T10:00+02:00", "2030-01-01T09:00Z"),
            AmongOrder.of(Zoned::class.java, listOf("2030-01-01T09:00Z", "2030-01-01T10:00+02:00"), mapper),
        )
    }

    @Test
    fun `a class that is neither a view nor a fragment keeps its ids, for the search to refuse it`() {
        assertEquals(listOf("b", "a"), AmongOrder.of(String::class.java, listOf("b", "a"), mapper))
    }

    @Test
    fun `a fragment with no @NodeId keeps its ids, for the search to refuse it`() {
        assertEquals(listOf("b", "a"), AmongOrder.of(Unkeyed::class.java, listOf("b", "a"), mapper))
    }
}
