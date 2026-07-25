package org.drivine.model

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.FragmentMergeBuilder
import org.drivine.query.FragmentQueryBuilder
import org.drivine.schema.FragmentSchemaScanner
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.VectorDimensionProvider
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.graphproperty.ChunkNode
import sample.graphproperty.EmbeddedNode
import sample.graphproperty.WidgetNode

class GraphPropertyTest {

    private val mapper = Neo4jObjectMapper.instance

    // ----- Model plumbing: field name is identity, propertyName is on-disk -----

    @Test
    fun `field name stays identity, propertyName carries the override`() {
        val model = FragmentModel.from(ChunkNode::class.java)
        val byName = model.fields.associateBy { it.name }

        assertEquals("container_section_id", byName.getValue("containerSectionId").propertyName)
        assertEquals("sequence_number", byName.getValue("sequenceNumber").propertyName)
        // A non-overridden field's property name defaults to its identity
        assertEquals("text", byName.getValue("text").propertyName)
    }

    @Test
    fun `nodeIdProperty reflects a @GraphProperty override on the id field`() {
        assertEquals("widget_key", FragmentModel.from(WidgetNode::class.java).nodeIdProperty)
        assertEquals("id", FragmentModel.from(ChunkNode::class.java).nodeIdProperty)
    }

    // ----- Validation -----

    @NodeFragment(labels = ["Clash"])
    data class ClashNode(
        @NodeId val id: String,
        @GraphProperty("shared") val a: String,
        @GraphProperty("shared") val b: String,
    )

    @Test
    fun `two fields mapping to the same property name fail fast, naming both`() {
        val e = assertThrows<IllegalArgumentException> { FragmentModel.from(ClashNode::class.java) }
        assertTrue(e.message!!.contains("'a'") && e.message!!.contains("'b'"), e.message)
        assertTrue(e.message!!.contains("shared"), e.message)
    }

    @NodeFragment(labels = ["Collide"])
    data class CollideWithDefaultNode(
        @NodeId val id: String,
        val sequenceNumber: String,
        @GraphProperty("sequenceNumber") val other: String,
    )

    @Test
    fun `an override colliding with another field's default name fails fast`() {
        assertThrows<IllegalArgumentException> { FragmentModel.from(CollideWithDefaultNode::class.java) }
    }

    @NodeFragment(labels = ["BagClash"])
    data class BagAndPropertyNode(
        @NodeId val id: String,
        @GraphProperty("meta") @PropertyBag val metadata: Map<String, Any?> = emptyMap(),
    )

    @Test
    fun `@GraphProperty combined with @PropertyBag on one field is rejected`() {
        val e = assertThrows<IllegalArgumentException> { FragmentModel.from(BagAndPropertyNode::class.java) }
        assertTrue(e.message!!.contains("@GraphProperty") && e.message!!.contains("@PropertyBag"), e.message)
    }

    // ----- Write seam: property on LHS, field name as bind-param -----

    @Test
    fun `merge writes the on-disk property but binds by field name`() {
        val model = FragmentModel.from(ChunkNode::class.java)
        val stmt = FragmentMergeBuilder(model, mapper).buildMergeStatement(
            ChunkNode(id = "c1", text = "t", containerSectionId = "sec-1", sequenceNumber = 3), dirtyFields = null
        )

        assertTrue(stmt.statement.contains("n.container_section_id = \$containerSectionId"), stmt.statement)
        assertTrue(stmt.statement.contains("n.sequence_number = \$sequenceNumber"), stmt.statement)
        // bind-param keys are the field names (identity), values present
        assertEquals("sec-1", stmt.bindings["containerSectionId"])
        assertEquals(3L, stmt.bindings["sequenceNumber"])
        // the field name must NOT appear as a written property
        assertFalse(stmt.statement.contains("n.containerSectionId ="), stmt.statement)
    }

    @Test
    fun `an overridden @NodeId is the MERGE key property, bound by field name`() {
        val model = FragmentModel.from(WidgetNode::class.java)
        val stmt = FragmentMergeBuilder(model, mapper).buildMergeStatement(
            WidgetNode(key = "w1", name = "Widget"), dirtyFields = null
        )
        assertTrue(stmt.statement.contains("MERGE (n:Widget {widget_key: \$key})"), stmt.statement)
        assertEquals("w1", stmt.bindings["key"])
    }

    // ----- Read seam: concrete projection aliases property → field -----

    @Test
    fun `concrete projection aliases the on-disk property back to the field name`() {
        val query = FragmentQueryBuilder.forFragment(ChunkNode::class.java).buildQuery(null, null)
        assertTrue(query.contains("containerSectionId: n.container_section_id"), query)
        assertTrue(query.contains("sequenceNumber: n.sequence_number"), query)
    }

    @Test
    fun `id where-clause targets the overridden id property`() {
        val where = FragmentQueryBuilder.forFragment(WidgetNode::class.java).buildIdWhereClause("id")
        assertEquals("n.widget_key = \$id", where)
    }

    // ----- Schema seam: specs target the on-disk property -----

    @Test
    fun `a @RangeIndex on an overridden field indexes the on-disk property`() {
        val specs = FragmentSchemaScanner.scan(ChunkNode::class.java, null)
        val range = specs.filterIsInstance<RangeIndexSpec>().single()
        assertEquals(listOf("container_section_id"), range.properties)
    }

    @Test
    fun `a @VectorIndex on an overridden field indexes the on-disk property`() {
        val specs = FragmentSchemaScanner.scan(EmbeddedNode::class.java, VectorDimensionProvider.fixed(4))
        val vector = specs.filterIsInstance<VectorIndexSpec>().single()
        assertEquals("embedding_vec", vector.property)
        assertEquals("Embedded_embedding_vec_vector", vector.effectiveName)
    }
}
