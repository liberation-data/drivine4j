package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.convertValueForNeo4j
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * [GraphIdText] says a type is held as text exactly where the graph engines' writer, [convertValueForNeo4j], writes a value
 * of it as a `String`: were the writer to write a type another way, this fails, before a search among ids that allows it
 * answers wrongly (liberation-data/drivine-cypher-spec#422).
 */
class GraphIdTextTest {

    enum class Colour { RED }

    private val mapper = ObjectMapper()

    private val samples: List<Any> = listOf(
        "a", UUID.fromString("00000000-0000-0000-0000-000000000001"), Colour.RED,
        1, 1L, 1.5, 1.5f, BigDecimal.ONE, true, LocalDate.of(2030, 1, 1), LocalDateTime.of(2030, 1, 1, 10, 0),
        ZonedDateTime.parse("2030-01-01T10:00:00Z"), OffsetDateTime.parse("2030-01-01T10:00:00Z"), Instant.parse("2030-01-01T10:00:00Z"),
    )

    @Test
    fun `a type is held as text exactly where the graph writer writes it as a String`() {
        for (sample in samples) {
            assertEquals(mapper.convertValueForNeo4j(sample) is String, GraphIdText.isText(sample.javaClass), "${sample.javaClass.simpleName}")
        }
    }

    @Test
    fun `a search among ids on a graph engine refuses an id that is not text, by name`() {
        @org.drivine.annotation.NodeFragment(labels = ["Counted"])
        data class Counted(@org.drivine.annotation.NodeId val id: Long, @org.drivine.annotation.VectorIndex val embedding: List<Float>?)
        val failure = org.junit.jupiter.api.assertThrows<UnsupportedOperationException> {
            VectorSearchPlanner.planAmong(Counted::class.java, null, listOf("1"), 2, null, 3,
                org.drivine.query.grammar.Neo4j5Grammar(org.drivine.query.sort.ApocSortMapsEmitter()))
        }
        assert("finds roots by a text @NodeId only" in failure.message!! && "Long" in failure.message!! && "#422" in failure.message!!) { failure.message!! }
    }
}
