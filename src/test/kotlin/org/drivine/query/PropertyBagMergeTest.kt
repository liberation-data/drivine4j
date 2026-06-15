package org.drivine.query

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.propertybag.BaggedNode
import sample.propertybag.OverlapNode
import sample.propertybag.TwoBagNode

/**
 * Phase A+B unit coverage — model recognition and the MERGE/SET/REMOVE shape for `@PropertyBag`.
 */
class PropertyBagMergeTest {

    private val mapper = Neo4jObjectMapper.instance
    private val model = FragmentModel.from(BaggedNode::class.java)
    private val builder = FragmentMergeBuilder(model, mapper)

    // ----- Model recognition -----

    @Test
    fun `bag field is partitioned out of declared fields into propertyBags`() {
        assertTrue(model.fields.none { it.name == "metadata" }, "bag must not be a declared field")
        assertTrue(model.fields.any { it.name == "title" })
        assertEquals(1, model.propertyBags.size)
        val bag = model.propertyBags.single()
        assertEquals("metadata", bag.fieldName)
        assertEquals("metadata.", bag.storedPrefix)
        assertEquals("metadata.source", bag.storedKey("source"))
    }

    // ----- Save shape -----

    @Test
    fun `full save expands bag entries to backtick-quoted prefixed properties`() {
        val node = BaggedNode(id = "n1", title = "T", metadata = mapOf("source" to "wiki", "score" to 3))
        val stmt = builder.buildMergeStatement(node, dirtyFields = null)

        assertTrue(stmt.statement.startsWith("MERGE (n:Bagged {id: \$id})"))
        assertTrue(stmt.statement.contains("n.title = \$title"))
        assertTrue(stmt.statement.contains("n.`metadata.source` = "))
        assertTrue(stmt.statement.contains("n.`metadata.score` = "))
        // bag values are bound
        assertTrue(stmt.bindings.values.contains("wiki"))
        assertTrue(stmt.bindings.values.contains(3))
        // no nested-map property
        assertFalse(stmt.statement.contains("n.metadata ="))
    }

    @Test
    fun `update removing a key emits REMOVE for the stale prefixed property`() {
        val previous = BaggedNode(id = "n1", title = "T", metadata = mapOf("source" to "wiki", "score" to 3))
        val current = BaggedNode(id = "n1", title = "T", metadata = mapOf("source" to "wiki")) // dropped "score"

        // dirtyFields includes "metadata" because the bag changed
        val stmt = builder.buildMergeStatement(current, dirtyFields = setOf("metadata"), previousObject = previous)

        assertTrue(stmt.statement.contains("SET n.`metadata.source` = "))
        assertTrue(stmt.statement.contains("REMOVE n.`metadata.score`"), stmt.statement)
    }

    @Test
    fun `empty bag with a previous non-empty bag removes all stale keys`() {
        val previous = BaggedNode(id = "n1", title = "T", metadata = mapOf("source" to "wiki", "score" to 3))
        val current = BaggedNode(id = "n1", title = "T", metadata = emptyMap())

        val stmt = builder.buildMergeStatement(current, dirtyFields = setOf("metadata"), previousObject = previous)
        assertTrue(stmt.statement.contains("REMOVE "), stmt.statement)
        assertTrue(stmt.statement.contains("n.`metadata.source`"), stmt.statement)
        assertTrue(stmt.statement.contains("n.`metadata.score`"), stmt.statement)
        assertFalse(stmt.statement.contains("SET n.`metadata."), stmt.statement)
    }

    @Test
    fun `homogeneous list value is storable`() {
        val node = BaggedNode(id = "n1", title = "T", metadata = mapOf("tags" to listOf("a", "b")))
        val stmt = builder.buildMergeStatement(node, dirtyFields = null)
        assertTrue(stmt.statement.contains("n.`metadata.tags` = "))
        assertEquals(listOf("a", "b"), stmt.bindings.values.first { it == listOf("a", "b") })
    }

    @Test
    fun `non-storable nested map value throws naming the key`() {
        val node = BaggedNode(id = "n1", title = "T", metadata = mapOf("nested" to mapOf("a" to 1)))
        val e = assertThrows<IllegalArgumentException> {
            builder.buildMergeStatement(node, dirtyFields = null)
        }
        assertTrue(e.message!!.contains("metadata.nested"), e.message)
    }

    // ----- Multiple bags / overlap guard -----

    @Test
    fun `two non-overlapping bags both expand with their own prefixes`() {
        val model2 = FragmentModel.from(TwoBagNode::class.java)
        assertEquals(setOf("meta.", "attr."), model2.propertyBags.map { it.storedPrefix }.toSet())

        val node = TwoBagNode(id = "n1", metadata = mapOf("a" to 1), attributes = mapOf("b" to 2))
        val stmt = FragmentMergeBuilder(model2, mapper).buildMergeStatement(node, dirtyFields = null)
        assertTrue(stmt.statement.contains("n.`meta.a` = "))
        assertTrue(stmt.statement.contains("n.`attr.b` = "))
    }

    @Test
    fun `overlapping bag prefixes are rejected at model build`() {
        val e = assertThrows<IllegalArgumentException> { FragmentModel.from(OverlapNode::class.java) }
        assertTrue(e.message!!.contains("overlap"), e.message)
    }
}