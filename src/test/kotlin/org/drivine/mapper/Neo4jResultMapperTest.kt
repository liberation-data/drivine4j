package org.drivine.mapper

import org.junit.jupiter.api.Test
import org.neo4j.driver.Values
import org.neo4j.driver.internal.InternalNode
import org.neo4j.driver.internal.InternalPath
import org.neo4j.driver.internal.InternalRecord
import org.neo4j.driver.internal.InternalRelationship
import org.neo4j.driver.types.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A whole record handed to [Neo4jResultMapper.toNative] maps each column as a column on its own is
 * mapped (drivine4j#121): a node or relationship, also inside a list, becomes its properties, and a
 * path column stays the driver's Path.
 */
class Neo4jResultMapperTest {

    private val mapper = Neo4jResultMapper()

    private val laurence = InternalNode(1, listOf("Person"), mapOf("name" to Values.value("Laurence")))
    private val keanu = InternalNode(2, listOf("Person"), mapOf("name" to Values.value("Keanu")))
    private val knows = InternalRelationship(3, 1, 2, "KNOWS", mapOf("since" to Values.value(1L)))

    @Test
    fun `a record maps its node, relationship, list and path columns`() {
        val record = InternalRecord(
            listOf("n", "r", "people", "p"),
            listOf(
                laurence.asValue(),
                knows.asValue(),
                Values.value(listOf(laurence, keanu)),
                InternalPath(laurence, knows, keanu).asValue(),
            ),
        )

        val row = assertIs<Map<*, *>>(mapper.toNative(record))

        assertEquals(mapOf("name" to "Laurence"), row["n"])
        assertEquals(mapOf("since" to 1L), row["r"])
        assertEquals(listOf(mapOf("name" to "Laurence"), mapOf("name" to "Keanu")), row["people"])
        assertEquals(2, assertIs<Path>(row["p"]).nodes().count())
    }
}
