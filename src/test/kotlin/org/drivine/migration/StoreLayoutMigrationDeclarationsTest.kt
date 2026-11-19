package org.drivine.migration

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The declarations a migration is told, from a fragment's fields' types (liberation-data/drivine4j#832): no store. */
@OptIn(ExperimentalStoreLayoutMigration::class)
class StoreLayoutMigrationDeclarationsTest {

    @NodeFragment(labels = ["Person", "Author"])
    data class Person(
        @NodeId val name: String,
        val born: LocalDate,
        val wakes: LocalTime?,
        val meets: OffsetTime?,
        val seen: LocalDateTime?,
        val at: ZonedDateTime?,
        val logged: Instant?,
        val tenure: Duration?,
        val height: Double,
        val ratio: Float?,
        val photo: ByteArray?,
        val days: List<LocalDate>,
        val visits: Int,
        val tags: List<String>,
    )

    @org.drivine.annotation.RelationshipFragment
    data class Knows(val since: LocalDate, val weight: Double?, val note: String?, val target: Person)

    @Test
    fun `a node fragment's temporal, float and bytes fields are declared under each of its labels`() {
        val expected = listOf(
            "born" to Kind.DATE, "wakes" to Kind.LOCAL_TIME, "meets" to Kind.TIME, "seen" to Kind.LOCAL_DATE_TIME,
            "at" to Kind.DATE_TIME, "logged" to Kind.DATE_TIME, "tenure" to Kind.DURATION, "height" to Kind.FLOAT,
            "ratio" to Kind.FLOAT, "photo" to Kind.BYTES, "days" to Kind.DATE,
        )
        val declared = StoreLayoutMigration.declarationsOf(Person::class.java)
        assertEquals(
            listOf("Person", "Author").flatMap { label -> expected.map { (p, k) -> Declaration(label, p, k) } }.sortedBy { "${it.label}.${it.property}" },
            declared.sortedBy { "${it.label}.${it.property}" },
        )
    }

    @Test
    fun `a relationship fragment's are declared under the type it is given`() {
        assertEquals(
            setOf(Declaration("KNOWS", "since", Kind.DATE), Declaration("KNOWS", "weight", Kind.FLOAT)),
            StoreLayoutMigration.declarationsOf("KNOWS", Knows::class.java).toSet(),
        )
    }

    @Test
    fun `a list's items are declared, and a list of lists is not, as the module reads its items as no value of a kind`() {
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.collections.List<java.time.LocalDate>"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("java.util.Set<? extends java.time.LocalDate>"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<kotlin.collections.List<java.time.LocalDate>>"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<kotlin.String>"))
    }

    @NodeFragment(labels = ["Signal"])
    open class Signal(@NodeId open val id: String = "", open val at: Instant? = null)

    @NodeFragment(labels = ["Alarm"])
    class Alarm(override val id: String = "", override val at: Instant? = null, val level: Double = 0.0) : Signal(id, at)

    @Test
    fun `a subtype's fields are declared under its own labels and those it inherits`() {
        assertEquals(
            setOf("Alarm", "Signal").flatMap { label -> listOf(Declaration(label, "at", Kind.DATE_TIME), Declaration(label, "level", Kind.FLOAT)) }.toSet(),
            StoreLayoutMigration.declarationsOf(Alarm::class.java).toSet(),
        )
    }

    @Test
    fun `a Java node fragment's and relationship fragment's fields are declared as a Kotlin one's`() {
        val each = listOf("days" to Kind.DATE, "weight" to Kind.FLOAT, "stamped" to Kind.DATE_TIME)
        assertEquals(
            (each + ("born" to Kind.DATE)).map { (p, k) -> Declaration("JavaDated", p, k) }.toSet(),
            StoreLayoutMigration.declarationsOf(JavaDated.Node::class.java).toSet(),
        )
        assertEquals(
            (each + ("since" to Kind.DATE)).map { (p, k) -> Declaration("LINKS", p, k) }.toSet(),
            StoreLayoutMigration.declarationsOf("LINKS", JavaDated.Link::class.java).toSet(),
        )
    }

    @Test
    fun `a Date is a moment, and a list of any class of list or set, or an array, is its items' kind`() {
        assertEquals(Kind.DATE_TIME, StoreLayoutMigration.kindOf("java.util.Date"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("java.util.ArrayList<java.time.LocalDate>"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.collections.HashSet<java.time.LocalDate>"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.Array<java.time.LocalDate>"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("java.time.LocalDate[]"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.Array<(out) java.time.LocalDate!>!"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.collections.(Mutable)Set<java.time.LocalDate!>!"))
        assertEquals(Kind.FLOAT, StoreLayoutMigration.kindOf("kotlin.DoubleArray"))
        assertEquals(Kind.FLOAT, StoreLayoutMigration.kindOf("double[]"))
        assertEquals(Kind.BYTES, StoreLayoutMigration.kindOf("byte[]"))
        assertNull(StoreLayoutMigration.kindOf("java.time.LocalDate[][]"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.(Mutable)List<kotlin.collections.(Mutable)List<java.time.LocalDate!>!>!"))
    }
}
