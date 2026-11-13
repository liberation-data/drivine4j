package org.drivine.backend.sql

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.backend.oracle.OracleSql
import org.drivine.backend.postgres.PostgresSql
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@NodeFragment(labels = ["IdsLong"])
private data class LongIdNode(@NodeId val id: Long, val name: String)

@NodeFragment(labels = ["IdsInt"])
private data class IntIdNode(@NodeId val id: Int, val name: String)

@NodeFragment(labels = ["IdsDate"])
private data class DateIdNode(@NodeId val id: LocalDate, val name: String)

@NodeFragment(labels = ["IdsMoment"])
private data class MomentIdNode(@NodeId val id: LocalDateTime, val name: String)

@NodeFragment(labels = ["IdsText"])
private data class TextIdNode(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["IdsUuid"])
private data class UuidIdNode(@NodeId val id: UUID, val name: String)

private enum class Shade { LIGHT, DARK }

@NodeFragment(labels = ["IdsShade"])
private data class ShadeIdNode(@NodeId val id: Shade, val name: String)

/** An id held as a type of the caller's own, which a save writes as its value. */
@JvmInline
value class IdKey(val value: String)

/** An id held as a number of the caller's own. */
@JvmInline
value class IdNumber(val value: Long)

@NodeFragment(labels = ["IdsKey"])
private data class KeyIdNode(@NodeId val id: IdKey, val name: String)

@NodeFragment(labels = ["IdsNumberKey"])
private data class NumberKeyIdNode(@NodeId val id: IdNumber, val name: String)

@NodeFragment(labels = ["IdsNullable"])
private data class NullableIdNode(@NodeId val id: Long?, val name: String)

@NodeFragment(labels = ["IdsRenamed"])
private data class RenamedIdNode(@NodeId @org.drivine.annotation.GraphProperty("key") val id: Long, val name: String)

@NodeFragment(labels = ["IdsDouble"])
private data class DoubleIdNode(@NodeId val id: Double, val name: String)

/**
 * An id given to find a node by on a SQL store is made the JSON the node holds it as, by the paths a save writes the
 * `@NodeId` field by: the text a load or a delete by id gives is read as the field's type first, and text that is no value
 * of the type finds no node (liberation-data/drivine-cypher-spec#245).
 */
class NodeIdsTest {

    private val ids = NodeIds(Neo4jObjectMapper.instance)

    private inline fun <reified T> json(given: Any?): String? = ids.json(FragmentModel.from(T::class.java), given)

    /** The JSON a save writes the id of [node] as: what a lookup must give for the same id. */
    private fun saved(node: Any): String {
        val model = FragmentModel.from(node.javaClass)
        return NodeIds.json(Neo4jObjectMapper.instance.toMap(node)[model.nodeIdField]!!)
    }

    @Test
    fun `text given for a number id is that number, as a save writes it`() {
        assertEquals("5", json<LongIdNode>("5"))
        assertEquals(saved(LongIdNode(5, "a")), json<LongIdNode>("5"))
        assertEquals("5", json<IntIdNode>("5"))
        assertEquals("5", json<LongIdNode>(5))
        assertEquals("5", json<LongIdNode>(5L))
    }

    @Test
    fun `text that is no value of the id's type finds no node, and nothing is thrown`() {
        assertNull(json<LongIdNode>("abc"))
        assertNull(json<LongIdNode>(""))
        assertNull(json<LongIdNode>("5.5"))
        assertNull(json<IntIdNode>("99999999999"))
        assertNull(json<DateIdNode>("2024-13-40"))
        assertNull(json<ShadeIdNode>("PURPLE"))
        assertNull(json<UuidIdNode>("not-a-uuid"))
        assertNull(json<LongIdNode>(null))
    }

    @Test
    fun `text given for a temporal id is its tag, as a save writes it`() {
        val date = LocalDate.of(2024, 1, 2)
        assertEquals(saved(DateIdNode(date, "a")), json<DateIdNode>("2024-01-02"))
        assertEquals(saved(DateIdNode(date, "a")), json<DateIdNode>(date))
        val tag = Neo4jObjectMapper.instance.readTree(json<DateIdNode>("2024-01-02"))
        assertEquals(listOf("date", "2024-01-02"), listOf(tag["${'$'}t"].asText(), tag["v"].asText()))
        val moment = LocalDateTime.of(2024, 1, 2, 10, 0)
        assertEquals(saved(MomentIdNode(moment, "a")), json<MomentIdNode>("2024-01-02T10:00"))
    }

    @Test
    fun `a String id is the text given, whatever it looks like`() {
        assertEquals("\"5\"", json<TextIdNode>("5"))
        assertEquals("\"2024-01-02\"", json<TextIdNode>("2024-01-02"))
        assertEquals("\"abc\"", json<TextIdNode>("abc"))
    }

    @Test
    fun `a UUID and an enum id are their text, as a save writes them`() {
        val uuid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        assertEquals(saved(UuidIdNode(uuid, "a")), json<UuidIdNode>(uuid.toString()))
        assertEquals("\"$uuid\"", json<UuidIdNode>(uuid.toString()))
        assertEquals(saved(ShadeIdNode(Shade.DARK, "a")), json<ShadeIdNode>("DARK"))
        assertEquals("\"DARK\"", json<ShadeIdNode>(Shade.DARK))
    }

    @Test
    fun `a temporal id is keyed in a plan by its tag's text, which the stores read a target's id back as`() {
        val moment = LocalDateTime.of(2024, 1, 2, 10, 0)
        val tag = Neo4jObjectMapper.instance.readTree(NodeIds.json(moment))
        assertEquals(tag["v"].asText(), PostgresSql.key(moment))
        assertEquals(tag["v"].asText(), OracleSql.key(moment))
        assertEquals("2024-01-02", PostgresSql.key(LocalDate.of(2024, 1, 2)))
        assertNull(NodeIds.temporalText("2024-01-02"))
        assertEquals("7", PostgresSql.key(7L))
        assertEquals("3", OracleSql.key(3.0))
    }

    @Test
    fun `an id of a value class, a nullable, a renamed, a Double or a Java field is read as a save writes it`() {
        assertEquals(saved(KeyIdNode(IdKey("k1"), "a")), json<KeyIdNode>("k1"))
        assertEquals(saved(KeyIdNode(IdKey("k1"), "a")), json<KeyIdNode>(IdKey("k1")))
        assertEquals(saved(NumberKeyIdNode(IdNumber(5), "a")), json<NumberKeyIdNode>("5"))
        assertEquals("5", json<NullableIdNode>("5"))
        assertEquals(saved(RenamedIdNode(5, "a")), json<RenamedIdNode>("5"))
        assertEquals(saved(DoubleIdNode(3.0, "a")), json<DoubleIdNode>("3"))
        assertEquals(saved(DoubleIdNode(3.0, "a")), json<DoubleIdNode>(3))
        assertEquals("\"m1\"", json<org.drivine.schema.JavaMembershipNode>("m1"))
    }
}
