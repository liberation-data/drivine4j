package org.drivine.backend.oracle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OracleNamesTest {

    @Test
    fun `a number is written as the plain form Oracle reads a JSON number back as`() {
        assertEquals("3", OracleSql.key(3.0))
        assertEquals("3", OracleSql.key(3.0f))
        assertEquals("3", OracleSql.key(BigDecimal("3.00")))
        assertEquals("2.5", OracleSql.key(2.5))
        assertEquals("42", OracleSql.key(42L))
        assertEquals("ada", OracleSql.key("ada"))
    }

    @Test
    fun `an object's name fits Oracle's limit with what is made beside it, whatever the graph is called`() {
        val longest = "g" + "x".repeat(113)
        val graph = OracleGraph(longest)
        val labels = listOf("A".repeat(60), "embedding".repeat(5))
        // A table beside the nodes has a trigger and an index named after it; an index on the nodes stands alone.
        for (side in listOf(graph.vectors(labels[0], labels[1]), graph.texts(labels[0], labels))) {
            assertTrue(side.length + "_t".length <= 128, "$side is ${side.length} long")
            assertTrue(side.startsWith(longest + "_"))
        }
        assertTrue(graph.objectName("uq", labels).length <= 128)
        assertThrows<IllegalArgumentException> { OracleGraph(longest + "x") }
    }

    @Test
    fun `two objects made for different parts never share a name`() {
        val graph = OracleGraph("drivine")
        assertNotEquals(graph.texts("Doc", listOf("title", "body")), graph.texts("Doc", listOf("titlebody")))
        assertNotEquals(graph.vectors("Doc", "embedding"), graph.vectors("Do", "cembedding"))
        assertEquals("drivine_v_Docembedding_" + Integer.toHexString("Doc\u0000embedding".hashCode()), graph.vectors("Doc", "embedding"))
    }
}
