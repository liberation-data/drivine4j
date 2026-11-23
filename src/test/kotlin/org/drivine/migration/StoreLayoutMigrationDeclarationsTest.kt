package org.drivine.migration

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.mapper.toMap
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
            "at" to Kind.DATE_TIME, "logged" to Kind.DATE_TIME, "height" to Kind.FLOAT,
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
    fun `a Duration is not declared, as layout 1 saved it as its seconds, a number, which the module reads as no duration`() {
        // How drivine4j 0.7.0, the last of layout 1, saved a field: its mapper's JavaTimeModule, dates as text, not the
        // Duration serializer drivine4j#862 added later.
        val layout1 = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val props = layout1.writeValueAsString(mapOf("tenure" to Duration.ofHours(1), "span" to java.time.Period.ofDays(2)))
        val rewrite = @OptIn(org.drivine.DrivineInternalApi::class) org.drivine.cypher.store.migrate.LayoutMigration.rewrite(
            props, org.drivine.cypher.api.Store.SQLITE, listOf("Person"),
            listOf("tenure", "span").map {
                org.drivine.cypher.store.migrate.LayoutMigration.Declaration("Person", it, org.drivine.cypher.store.migrate.LayoutMigration.Kind.DURATION)
            },
        )
        assertEquals(listOf("tenure"), rewrite.invalid.map { it.property }, props)
        assertEquals(listOf("span"), rewrite.changes.map { it.property }, props)
        assertNull(StoreLayoutMigration.kindOf("java.time.Duration"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<java.time.Duration>"))
        // A Period JavaTimeModule wrote as its text, which the module reads as a duration.
        assertEquals(Kind.DURATION, StoreLayoutMigration.kindOf("java.time.Period"))
    }

    @Test
    fun `a list's items are declared, and a list of lists is not, as the module reads its items as no value of a kind`() {
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("kotlin.collections.List<java.time.LocalDate>"))
        assertEquals(Kind.DATE, StoreLayoutMigration.kindOf("java.util.Set<? extends java.time.LocalDate>"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<kotlin.collections.List<java.time.LocalDate>>"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<kotlin.String>"))
    }

    @NodeFragment(labels = ["Sensor"])
    data class Sensor(
        @NodeId val id: String,
        @org.drivine.annotation.JsonPacked val readings: List<Double>,
        @field:org.drivine.annotation.JsonPacked val calibrated: List<LocalDate>? = null,
        val level: Double,
    )

    @org.drivine.annotation.RelationshipFragment
    data class Reads(@org.drivine.annotation.JsonPacked val samples: List<Double>, val target: Sensor)

    @Test
    fun `a JsonPacked field is not declared, as layout 1 saved it as a JSON text, which the module reads as no value of its kind`() {
        assertEquals(listOf(Declaration("Sensor", "level", Kind.FLOAT)), StoreLayoutMigration.declarationsOf(Sensor::class.java))
        assertEquals(emptyList(), StoreLayoutMigration.declarationsOf("READS", Reads::class.java))
        // The text a @JsonPacked List<Double> is saved as.
        val props = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(mapOf("readings" to "[1.0,2.5]"))
        val rewrite = @OptIn(org.drivine.DrivineInternalApi::class) org.drivine.cypher.store.migrate.LayoutMigration.rewrite(
            props, org.drivine.cypher.api.Store.SQLITE, listOf("Sensor"),
            listOf(org.drivine.cypher.store.migrate.LayoutMigration.Declaration("Sensor", "readings", org.drivine.cypher.store.migrate.LayoutMigration.Kind.FLOAT)),
        )
        assertEquals(listOf("readings"), rewrite.invalid.map { it.property }, props)
    }

    @NodeFragment(labels = ["Gauge"])
    data class Gauge(
        @NodeId val id: String,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) val level: Double,
        @get:com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer::class) val peak: Double,
        val low: Double,
    )

    @org.drivine.annotation.RelationshipFragment
    data class Feeds(@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) val rate: Double, val target: Gauge)

    @Test
    fun `a field written as its JsonFormat or JsonSerialize says is not declared, as its saved form can't be told from its type`() {
        assertEquals(listOf(Declaration("Gauge", "low", Kind.FLOAT)), StoreLayoutMigration.declarationsOf(Gauge::class.java))
        assertEquals(emptyList(), StoreLayoutMigration.declarationsOf("FEEDS", Feeds::class.java))
        // What drivine4j 0.7.0's save wrote of the shaped fields: texts, which the module reads as no float.
        val saved = org.drivine.mapper.Neo4jObjectMapper.instance.toMap(Gauge("g", 1.5, 2.5, 0.5))
        val props = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(saved)
        val rewrite = @OptIn(org.drivine.DrivineInternalApi::class) org.drivine.cypher.store.migrate.LayoutMigration.rewrite(
            props, org.drivine.cypher.api.Store.SQLITE, listOf("Gauge"),
            listOf("level", "peak").map {
                org.drivine.cypher.store.migrate.LayoutMigration.Declaration("Gauge", it, org.drivine.cypher.store.migrate.LayoutMigration.Kind.FLOAT)
            },
        )
        assertEquals(listOf("level", "peak"), rewrite.invalid.map { it.property }.sorted(), props)
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
        // A list of arrays is a list of lists, whose items the module reads as no float and no bytes.
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.List<kotlin.DoubleArray>"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.(Mutable)List<kotlin.ByteArray!>!"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.Array<kotlin.FloatArray>"))
        assertNull(StoreLayoutMigration.kindOf("byte[][]"))
        assertNull(StoreLayoutMigration.kindOf("kotlin.collections.(Mutable)List<kotlin.collections.(Mutable)List<java.time.LocalDate!>!>!"))
    }
}
