package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test
import sample.graphproperty.ChunkNode
import sample.nodelabels.ThingNode
import sample.patch.ClaimViewQueryDsl
import sample.patch.Pace
import sample.patch.PatchSession
import sample.patch.PatchSessionQueryDsl
import sample.stateless.ClaimView
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference

/** Fields stored as an annotation says: what each stores cannot be told from the value alone. */
@NodeFragment(labels = ["Shouted"])
data class Shouted(
    @NodeId val id: String,
    @get:JsonSerialize(using = Shout::class) val text: String? = null,
    val plain: String? = null,
    @get:JsonFormat(shape = JsonFormat.Shape.STRING) val at: Instant? = null,
)

class Shout : JsonSerializer<String>() {
    override fun serialize(value: String, gen: JsonGenerator, serializers: SerializerProvider) = gen.writeString(value.uppercase())
}

/** What a patch of one node writes for the fields it names: which it refuses, and each value as a save stores it. */
class PatchedValuesTest {

    private fun values(graphClass: Class<*>) = PatchedValues(graphClass, Neo4jObjectMapper.instance)

    private val session = PatchSessionQueryDsl.INSTANCE

    @Test
    fun `the properties are in one order, however they were named`() {
        val written = values(PatchSession::class.java).of(listOf(session.turns.assign(3L), session.pace.cleared(), session.lastActivityAt.assign(Instant.EPOCH)))

        assertEquals(listOf("lastActivityAt", "pace", "turns"), written.keys.toList())
        assertEquals(null to 3L, written["pace"] to (written["turns"] as Number).toLong())
    }

    @Test
    fun `a value is what the object mapper makes of the field in a save`() {
        val saved = Neo4jObjectMapper.instance.convertValue(
            PatchSession("s1", lastActivityAt = Instant.EPOCH, pace = Pace.BRISK, tags = listOf("a", "b")), Map::class.java,
        )

        val written = values(PatchSession::class.java).of(listOf(session.pace.assign(Pace.BRISK), session.tags.assign(listOf("a", "b")), session.lastActivityAt.assign(Instant.EPOCH)))

        assertEquals("BRISK", written["pace"])
        // A `@JsonPacked` field is packed, as the mapper packs it on the object.
        assertEquals(saved["tags"], written["tags"])
        assertEquals("[\"a\",\"b\"]", written["tags"])
        // A moment is kept a moment: the connection stores it as it stores a save's.
        assertEquals(Instant.EPOCH, written["lastActivityAt"])
    }

    @Test
    fun `a field is written under the property it is stored as`() {
        val written = values(ChunkNode::class.java).of(listOf(PropertyReference<Long>("n", "sequence_number").assign(2L)))

        assertEquals(setOf("sequence_number"), written.keys)
    }

    @Test
    fun `a field the mapper writes as an annotation says is refused, and its plain neighbour is written`() {
        val failure = assertFailsWith<IllegalArgumentException> { values(Shouted::class.java).of(listOf(StringPropertyReference("n", "text").assign("quiet"))) }

        assertContains(failure.message!!, "is written as its @JsonSerialize says")
        assertContains(
            assertFailsWith<IllegalArgumentException> { values(Shouted::class.java).of(listOf(PropertyReference<Instant>("n", "at").assign(Instant.EPOCH))) }.message!!,
            "is written as its @JsonFormat says",
        )
        assertEquals(mapOf<String, Any?>("plain" to "quiet"), values(Shouted::class.java).of(listOf(StringPropertyReference("n", "plain").assign("quiet"))))
        // Clearing it stores nothing, so there is nothing a serializer would have changed.
        assertEquals(mapOf<String, Any?>("text" to null), values(Shouted::class.java).of(listOf(StringPropertyReference("n", "text").cleared())))
    }

    @Test
    fun `what is not a field of the node a patch can write is refused, with the fields that are`() {
        val view = ClaimViewQueryDsl.INSTANCE
        fun refused(graphClass: Class<*>, reference: PropertyReference<*>) =
            assertFailsWith<IllegalArgumentException> { values(graphClass).of(listOf(reference.cleared())) }.message!!

        assertContains(refused(ClaimView::class.java, view.claim.id), "is the id, which a node is found by")
        assertContains(refused(ClaimView::class.java, view.claim.stamp), "is the stamp")
        assertContains(refused(ClaimView::class.java, view.people.name), "'people.name' is a field of a related node")
        // A fragment's own DSL names its node `n`: a reference of another alias is no field of this node.
        assertContains(refused(ClaimView::class.java, StringPropertyReference("n", "text")), "'n.text' is not one")
        assertContains(refused(ClaimView::class.java, view.claim.id), "The fields it can write are: note, text.")
        assertContains(refused(ThingNode::class.java, StringPropertyReference("n", "labels")), "nor a @NodeLabels field")
        assertContains(refused(ThingNode::class.java, StringPropertyReference("n", "colour")), "no field stored as 'colour'")
        assertContains(assertFailsWith<IllegalArgumentException> { values(ThingNode::class.java).of(emptyList()) }.message!!, "names none")
    }
}
