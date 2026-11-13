package org.drivine.oracle

import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.dsl.field
import org.drivine.query.dsl.query
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import sample.vectorfilter.VecDocNode
import sample.vectorfilter.VecDocNodeQueryDsl
import kotlin.test.assertEquals

/**
 * A filtered vector search on Oracle whose filter compares a property a range index is over, by measuring every node
 * and through the vector index: the shape in which Oracle 23.26 drops a filter, or ends the server process, where a
 * statement joins the node table back to itself through an inline view and an index answers the filter
 * (`src/test/resources/oracle/ora7445-repro.sql`). Layout 2's search joins no node back to the node table; this holds
 * its answers to the filter whatever form it takes.
 */
@OracleTests
class OracleFilteredVectorSearchIndexedTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @Test
    fun `a filter on an indexed property keeps only its nodes, measured and through the index`() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(
            pm,
            """
            CREATE (:VecDoc {id: 'a', section_id: 's1', embedding: [1.0, 0.0, 0.0, 0.0]})
            CREATE (:VecDoc {id: 'b', section_id: 's2', embedding: [0.0, 1.0, 0.0, 0.0]})
            CREATE (:VecDoc {id: 'c', section_id: 's1', embedding: [0.9, 0.1, 0.0, 0.0]})
            """,
        )
        val gom = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)
        val doc = VecDocNodeQueryDsl.INSTANCE
        val vector = listOf(1.0f, 0.0f, 0.0f, 0.0f)
        val indexes = listOf(RangeIndexSpec("VecDoc", "id"), RangeIndexSpec("VecDoc", "section_id"))
        val embeddings = VectorIndexSpec("VecDoc", "embedding", 4)
        fun byId(id: String) = gom.loadNearest(VecDocNode::class.java, doc, vector, topK = 10) { where { query.id eq id } }.map { it.value.id }
        fun bySection(section: String) = gom.loadNearest(VecDocNode::class.java, doc, vector, topK = 10) { where { query.field("sectionId") eq section } }.map { it.value.id }
        try {
            indexes.forEach { pm.indexes.ensure(it) }
            assertEquals(listOf("c"), byId("c"), "measured")
            assertEquals(listOf("a", "c"), bySection("s1"), "measured")
            pm.indexes.ensure(embeddings)
            assertEquals(listOf("c"), byId("c"), "through the index")
            assertEquals(listOf("b"), byId("b"), "through the index")
            assertEquals(listOf("a", "c"), bySection("s1"), "through the index")
        } finally {
            (indexes + embeddings).forEach { runCatching { pm.indexes.drop(it) } }
        }
    }
}
