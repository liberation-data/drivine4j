package org.drivine.backend.postgres

import org.drivine.connection.DatabaseType
import org.drivine.cypher.api.FullTextSearch
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.SearchIndex
import org.drivine.cypher.api.SearchPlan
import org.drivine.cypher.api.SearchRequest
import org.drivine.cypher.api.SearchStore
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.fulltext.EntityNode
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [PostgresBackend] makes of what an object manager asks of it. The store is a [RecordingStore], so no database is needed. */
class PostgresBackendTest {

    private fun store(answer: (RecordingStore.Ran) -> Any? = { emptyList<Any?>() }) =
        RecordingStore(DatabaseType.POSTGRES, ddl = PostgresSchemaGrammar(PostgresGraph("g")), graphName = "g", answer = answer)

    private fun backend(store: RecordingStore = store()) = PostgresBackend(store, Neo4jObjectMapper.instance)

    /** A catalog whose full-text index's trigram index is made ([made]) or not. */
    private fun made(made: Boolean): (RecordingStore.Ran) -> Any? = { ran ->
        if (made && ran.statement.startsWith("SELECT details FROM g_schema")) listOf(mapOf("trigram" to true)) else emptyList<Any?>()
    }

    private fun String.squashed(): String = replace(Regex("\\s+"), " ")

    private fun planned(query: String, trigram: Boolean): SearchPlan = FullTextSearch.plan(
        SearchStore.POSTGRES, GraphOptions("g"), SearchRequest(listOf("Entity"), listOf("name", "description"), query, SearchIndex(trigram = trigram)),
    ) as SearchPlan

    @Test
    fun `a deadlock and a serialization failure are contention, and no other failure is`() {
        val backend = backend()

        assertTrue(backend.contended(SQLException("could not serialize access due to concurrent update", "40001")))
        assertTrue(backend.contended(SQLException("deadlock detected", "40P01")))
        // A unique key, a lock that ran out of time, a lost connection: none is another writer at that moment.
        assertFalse(backend.contended(SQLException("duplicate key value violates unique constraint", "23505")))
        assertFalse(backend.contended(SQLException("canceling statement due to lock timeout", "55P03")))
        assertFalse(backend.contended(SQLException("An I/O error occurred while sending to the backend.", "08006")))
        assertFalse(backend.contended(IllegalStateException("40001")))
        // The failure itself is asked, not what caused it: the executor walks the causes.
        assertFalse(backend.contended(RuntimeException(SQLException("deadlock detected", "40P01"))))
    }

    @Test
    fun `a text search asks the catalog by the shared module's lookup, and with the trigram index made the module narrows by it`() {
        for (trigram in listOf(true, false)) {
            val store = store(made(trigram))
            val plan = backend(store).matching(EntityNode::class.java, null, null, "graph", topK = 3, threshold = 0.25)

            val lookup = FullTextSearch.lookup(SearchStore.POSTGRES, GraphOptions("g"), "Entity", listOf("name", "description"))!!
            assertEquals(lookup.sql, store.queried.single().statement)
            assertEquals(lookup.parameters, store.queried.single().parameters)
            val module = planned("graph", trigram)
            val sql = plan.cypher.squashed()
            // The module's ranked nids, joined back to the node table for their rows.
            assertTrue(sql.contains(module.sql!!.squashed()), sql)
            assertTrue(sql.contains(") m JOIN g_node c ON c.nid = m.nid"), sql)
            assertEquals(trigram, sql.contains(" LIKE "), sql)
            // The module's parameters, then the select's own threshold and limit, numbered past them.
            assertEquals(module.parameters, plan.bindings.filterKeys { it.startsWith("_cy_ft") })
            assertTrue(0.25 in plan.bindings.filterKeys { it.startsWith("_p") }.values, plan.bindings.toString())
            assertTrue(3 in plan.bindings.filterKeys { it.startsWith("_p") }.values, plan.bindings.toString())
        }
    }

    @Test
    fun `a text query with no word to look for is refused as the shared module refuses it`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            backend(store(made(false))).matching(EntityNode::class.java, null, null, "-graph", topK = 3, threshold = 0.0)
        }
        assertEquals("A full-text query needs a word to look for: '-graph' has none.", refused.message)
    }
}
