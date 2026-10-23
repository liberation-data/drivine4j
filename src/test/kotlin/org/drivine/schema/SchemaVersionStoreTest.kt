package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The schema marker's statements in Cypher, and what [SchemaVersionStore] sends for each thing it is
 * asked: in the store's own language, bound to its scope. The store is a [RecordingStore].
 */
class SchemaVersionStoreTest {

    private val graph = OracleGraph("g")

    private fun cypher(answer: (RecordingStore.Ran) -> Any? = { emptyList<Any?>() }) = RecordingStore(DatabaseType.NEO4J, answer = answer)

    private fun oracle(answer: (RecordingStore.Ran) -> Any? = { emptyList<Any?>() }) =
        RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "g", answer = answer)

    private val marker = "MATCH (m:`_DrivineSchema` {scope: \$scope})"

    // ----- The statements in Cypher -----

    @Test
    fun `the Cypher marker is found by its label and scope, read newest first, and merged on the scope alone`() {
        val statements = CypherVersionMarker("_DrivineSchema", "scope")

        assertEquals("$marker RETURN m.version ORDER BY m.appliedAt DESC LIMIT 1", statements.read("version"))
        assertEquals("MERGE (m:`_DrivineSchema` {scope: \$scope}) SET m.version = \$version, m.items = \$items, m.appliedAt = timestamp()", statements.merge)
        assertEquals("$marker WITH m ORDER BY m.appliedAt DESC SKIP 1 DELETE m", statements.deleteAllButNewest)
        assertEquals("$marker DELETE m", statements.delete)
        assertNull(statements.prepare)
    }

    @Test
    fun `the Cypher marker keeps an inventory as it is given, an empty one too`() {
        val statements = CypherVersionMarker("_DrivineSchema", "scope")

        assertEquals(mapOf("scope" to "owner", "version" to null, "items" to ""), statements.mergeBindings("owner", null, ""))
        assertEquals("a\nb", statements.items(statements.storedItems("a\nb")))
    }

    @Test
    fun `the Cypher stamp is merged on its key and set only when it is made, and every stamp is read as a map, the oldest first`() {
        val statements = CypherStoreStamp("DrivineStore", "key")

        assertEquals(
            "MERGE (s:DrivineStore { key: \$key })\nON CREATE SET s.storeId = \$storeId,\n              s.assignedAt = \$assignedAt,\n              s.engine = \$engine",
            statements.assignIfAbsent,
        )
        assertEquals("MATCH (s:DrivineStore)\nRETURN { id: s.storeId, assignedAt: s.assignedAt } AS stamp\nORDER BY s.assignedAt, s.storeId", statements.readStamps)
        assertNull(statements.prepare)
    }

    // ----- Reading -----

    @Test
    fun `the version is read for the store's own scope, and is null when there is no marker`() {
        val store = cypher { ran -> if ("m.version" in ran.statement) listOf("v7") else emptyList<Any?>() }

        assertEquals("v7", SchemaVersionStore(store, "billing").storedVersion())
        assertEquals("$marker RETURN m.version ORDER BY m.appliedAt DESC LIMIT 1", store.queried.single().statement)
        assertEquals(mapOf<String, Any?>("scope" to "billing"), store.queried.single().parameters)
        assertNull(SchemaVersionStore(cypher()).storedVersion())
    }

    @Test
    fun `the anonymous owner reads and writes the scope it always has`() {
        val store = cypher()
        SchemaVersionStore(store).storedVersion()

        assertEquals(mapOf<String, Any?>("scope" to "schema"), store.queried.single().parameters)
        assertEquals("schema", SchemaVersionStore.scopeFor(null))
        assertEquals("billing", SchemaVersionStore.scopeFor("billing"))
    }

    @Test
    fun `a marker with no inventory is not one with an empty inventory`() {
        fun inventory(stored: String?) = SchemaVersionStore(cypher { listOfNotNull(stored) }).storedInventory()

        assertNull(inventory(null), "a marker from before inventories were kept")
        assertEquals(emptyList(), inventory(""))
        assertEquals(listOf("RANGE_INDEX:Person:name"), inventory("RANGE_INDEX:Person:name"))
        assertEquals(listOf("a", "b", "c"), inventory("a\nb\nc"))
    }

    @Test
    fun `on Oracle an empty inventory comes back empty from behind its mark, and one written without the mark is read as it is`() {
        fun inventory(stored: String?) = SchemaVersionStore(oracle { ran -> if (ran.statement.startsWith("SELECT")) listOfNotNull(stored) else emptyList<Any?>() }).storedInventory()

        assertNull(inventory(null))
        assertEquals(emptyList(), inventory("items:"))
        assertEquals(listOf("a", "b"), inventory("items:a\nb"))
        assertEquals(listOf("a", "b"), inventory("a\nb"))
    }

    @Test
    fun `an item ensured at runtime is read back as one, and marked when it waits to be recreated`() {
        val stored = "RANGE_INDEX:Doc:slug:a\nruntime:VECTOR_INDEX:Doc:embedding:b\nruntime-stale:RANGE_INDEX:Tag:name:c"
        val record = SchemaVersionStore(cypher { listOf(stored) }).storedRecord()!!

        assertEquals(listOf("RANGE_INDEX:Doc:slug:a", "VECTOR_INDEX:Doc:embedding:b", "RANGE_INDEX:Tag:name:c"), record.keys)
        assertEquals(setOf("VECTOR_INDEX:Doc:embedding:b", "RANGE_INDEX:Tag:name:c"), record.runtime)
        assertEquals(setOf("RANGE_INDEX:Tag:name:c"), record.stale)
        assertEquals(record.keys, SchemaVersionStore(cypher { listOf(stored) }).storedInventory(), "the inventory is every key, unmarked")
    }

    // ----- Writing -----

    @Test
    fun `a record marks the items ensured at runtime, and those to be recreated`() {
        val store = cypher()
        SchemaVersionStore(store, "dice").record("v1", listOf("a", "b", "c"), runtime = setOf("b", "c"), stale = setOf("c"))

        assertEquals("a\nruntime:b\nruntime-stale:c", store.executed.single().parameters["items"])
    }

    @Test
    fun `a record merges the version and the inventory as one string, a key to a line`() {
        val store = cypher()
        SchemaVersionStore(store, "billing").record("v2", listOf("RANGE_INDEX:Person:name", "VECTOR_INDEX:Doc:embedding"))

        val merge = store.executed.single()
        assertTrue(merge.statement.startsWith("MERGE (m:`_DrivineSchema` {scope: \$scope})"), merge.statement)
        assertEquals(mapOf<String, Any?>("scope" to "billing", "version" to "v2", "items" to "RANGE_INDEX:Person:name\nVECTOR_INDEX:Doc:embedding"), merge.parameters)
    }

    @Test
    fun `a record of a version alone writes an empty inventory, which on Oracle is kept behind its mark`() {
        val plain = cypher()
        val marked = oracle()
        SchemaVersionStore(plain).record("v1")
        SchemaVersionStore(marked).record(null)

        assertEquals("", plain.executed.single().parameters["items"])
        assertEquals("items:", marked.executed.single().parameters["items"])
        assertTrue("version" in marked.executed.single().parameters, "no version is bound as null, not left unbound")
        assertNull(marked.executed.single().parameters["version"])
    }

    @Test
    fun `a record that loses the race to make the marker is merged once more, and a second failure is the caller's`() {
        var failures = 1
        val raced = cypher { if (failures-- > 0) IllegalStateException("constraint violated") else emptyList<Any?>() }
        SchemaVersionStore(raced).record("v1", listOf("a"))
        assertEquals(2, raced.executed.size)
        assertEquals(raced.executed[0].statement, raced.executed[1].statement)

        val second = IllegalStateException("still refused")
        var calls = 0
        val failing = cypher { if (calls++ == 0) IllegalStateException("first") else second }
        assertSame(second, assertFailsWith<IllegalStateException> { SchemaVersionStore(failing).record("v1") })
        assertEquals(2, failing.executed.size)
    }

    @Test
    fun `a clear deletes the marker of the scope and no other`() {
        val store = cypher()
        SchemaVersionStore(store, "billing").clear()

        assertEquals("$marker DELETE m", store.executed.single().statement)
        assertEquals(mapOf<String, Any?>("scope" to "billing"), store.executed.single().parameters)
    }

    @Test
    fun `the singleton guard collapses the duplicates of the scope before it makes the key unique`() {
        val store = cypher()
        SchemaVersionStore(store, "billing").ensureSingleton()

        assertEquals("$marker WITH m ORDER BY m.appliedAt DESC SKIP 1 DELETE m", store.executed.single().statement)
        assertEquals(mapOf<String, Any?>("scope" to "billing"), store.executed.single().parameters)
        val created = store.schema.single { it.startsWith("CREATE CONSTRAINT") }
        assertContains(created, "_DrivineSchema")
        assertContains(created, "scope")
    }

    // ----- The store's own language -----

    @Test
    fun `on Oracle the graph is made once before the marker is first read, on a schema connection, and the statements are SQL`() {
        val store = oracle()
        val versions = SchemaVersionStore(store)

        assertTrue(store.schema.isEmpty(), "nothing is made until the marker is wanted")
        versions.storedVersion()
        versions.storedInventory()
        versions.record("v1")
        versions.clear()

        assertEquals(listOf(graph.ensuring), store.schema)
        (store.queried + store.executed).forEach { assertTrue("g_node" in it.statement && "MATCH (m" !in it.statement, it.statement) }
        assertTrue(store.queried.first().statement.startsWith("SELECT JSON_VALUE(n.props, '$.version' RETURNING CLOB) FROM g_node n"))
        assertTrue("now" in store.executed.first().parameters, "Oracle binds the moment the graph engines read from timestamp()")
    }

    @Test
    fun `a manager with no schema grammar is sent the Cypher marker when its store speaks Cypher`() {
        val store = RecordingStore(DatabaseType.MEMGRAPH, schemaGrammar = null)
        SchemaVersionStore(store).clear()

        assertEquals("$marker DELETE m", store.executed.single().statement)
        assertTrue(store.schema.isEmpty())
    }

    @Test
    fun `a manager with no schema grammar whose store speaks no Cypher is sent nothing, and told why`() {
        val store = RecordingStore(DatabaseType.ORACLE, schemaGrammar = null)
        val versions = SchemaVersionStore(store)

        val refused = assertFailsWith<DrivineException> { versions.storedVersion() }
        assertContains(refused.message.orEmpty(), "ORACLE does not speak Cypher, and this PersistenceManager has no schema grammar to write the schema marker with.")
        assertFailsWith<DrivineException> { versions.record("v1") }
        assertTrue(store.queried.isEmpty() && store.executed.isEmpty() && store.schema.isEmpty())
    }
}
