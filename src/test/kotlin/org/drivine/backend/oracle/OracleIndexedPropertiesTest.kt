package org.drivine.backend.oracle

import org.drivine.connection.DatabaseType
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which properties [OracleIndexedProperties] says find few nodes, and what it asks the store to know. No database is needed. */
class OracleIndexedPropertiesTest {

    private val graph = OracleGraph("g")

    private fun store(answer: (RecordingStore.Ran) -> Any?) = RecordingStore(DatabaseType.ORACLE, graphName = "g", answer = answer)

    @Test
    fun `the properties are those the store gives, asked for once and kept`() {
        val store = store { listOf("repoKey", "id") }
        val found = OracleIndexedProperties(store, graph)

        assertTrue(found.finds("repoKey"))
        assertTrue(found.finds("id"))
        assertFalse(found.finds("state"))
        assertEquals(1, store.queried.size, "asked once, however many properties are asked about")
    }

    @Test
    fun `it asks for the range indexes of one property that Oracle counts as holding few rows to a key`() {
        val store = store { emptyList<String>() }
        OracleIndexedProperties(store, graph).finds("anything")

        val asked = store.queried.single()
        val text = asked.statement.replace(Regex("\\s+"), " ")
        assertTrue(text.contains("FROM g_schema c JOIN user_indexes i ON i.index_name = UPPER(c.object_name)"), text)
        // A uniqueness constraint's index is over a CASE of the label, which a test of the property cannot use.
        assertTrue(text.contains("c.kind = 'RANGE_INDEX'"), text)
        assertFalse(text.contains("UNIQUENESS"), text)
        // An index over several properties counts its whole key, and one with no statistics counts nothing.
        assertTrue(text.contains("JSON_VALUE(c.properties, '$.size()' RETURNING NUMBER) = 1"), text)
        assertTrue(text.contains("i.distinct_keys > 0 AND i.num_rows <= i.distinct_keys * \$few"), text)
        assertEquals(mapOf<String, Any?>("few" to OracleIndexedProperties.FEW), asked.parameters)
    }

    @Test
    fun `a store that cannot be asked gives no properties, fails no query, and is not asked again at once`() {
        val store = store { IllegalStateException("ORA-00942: table or view does not exist") }
        val found = OracleIndexedProperties(store, graph)

        assertFalse(found.finds("repoKey"))
        assertFalse(found.finds("repoKey"))
        assertEquals(1, store.queried.size)
    }
}
