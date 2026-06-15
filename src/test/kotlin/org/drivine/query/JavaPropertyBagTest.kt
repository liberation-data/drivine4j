package org.drivine.query

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.propertybag.JavaBaggedNode

/** Does `@PropertyBag` recognition + save expansion work for a plain Java fragment? */
class JavaPropertyBagTest {

    @Test
    fun `model detects @PropertyBag on a Java fragment`() {
        val model = FragmentModel.from(JavaBaggedNode::class.java)
        assertEquals(1, model.propertyBags.size, "expected the Java @PropertyBag to be recognized")
        assertEquals("metadata.", model.propertyBags.single().storedPrefix)
        assertTrue(model.fields.none { it.name == "metadata" }, "bag must be partitioned out of declared fields")
        assertTrue(model.fields.any { it.name == "title" })
    }

    @Test
    fun `Java fragment save expands the bag to prefixed properties`() {
        val node = JavaBaggedNode("j1", "T", mapOf<String, Any?>("source" to "wiki", "score" to 3))
        val stmt = FragmentMergeBuilder(FragmentModel.from(JavaBaggedNode::class.java), Neo4jObjectMapper.instance)
            .buildMergeStatement(node, null)
        assertTrue(stmt.statement.contains("n.`metadata.source` = "), stmt.statement)
        assertTrue(stmt.statement.contains("n.`metadata.score` = "), stmt.statement)
    }
}