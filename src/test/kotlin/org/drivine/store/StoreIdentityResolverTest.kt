package org.drivine.store

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.schema.CypherStoreStamp
import org.drivine.store.StoreIdentityResolver.Stamp
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.Neo4jException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How [StoreIdentityResolver] stamps a store and reads the stamp back: what it sends, what it does
 * when the stamp is not there to read, and when uniqueness cannot be enforced. The store is a
 * [RecordingStore]; what two processes do at once needs a real one.
 */
class StoreIdentityResolverTest {

    private val cypherStamp = CypherStoreStamp("DrivineStore", "key")

    /** A store that speaks Cypher and has no schema management, which reads back [stamps] in turn: one list for each read. */
    private fun unguarded(vararg stamps: List<Stamp>, type: DatabaseType = DatabaseType.NEPTUNE): RecordingStore {
        var read = 0
        return RecordingStore(type, supportsSchemaManagement = false) { ran ->
            if (ran.statement == cypherStamp.readStamps) stamps[read++] else emptyList<Any?>()
        }
    }

    @Test
    fun `the store is stamped if it has no stamp, then read, and the stamp read is its identity`() {
        val store = unguarded(listOf(Stamp("store-1", "2026-01-01T00:00:00Z")))
        val before = Instant.now()

        val identity = StoreIdentityResolver(store).resolve()

        assertEquals(StoreIdentity("store-1", DatabaseType.NEPTUNE, "2026-01-01T00:00:00Z"), identity)
        val written = store.executed.single()
        assertEquals(cypherStamp.assignIfAbsent, written.statement)
        assertEquals(setOf("key", "storeId", "assignedAt", "engine"), written.parameters.keys)
        assertEquals("singleton", written.parameters["key"])
        assertEquals("NEPTUNE", written.parameters["engine"])
        // What is offered is a new id and this moment: the store keeps them only if it had no stamp.
        UUID.fromString(written.parameters["storeId"] as String)
        assertTrue(!Instant.parse(written.parameters["assignedAt"] as String).isBefore(before.minusSeconds(1)))
        assertTrue(store.schema.isEmpty(), "a store with no schema management is asked for no constraint")
    }

    @Test
    fun `of several stamps the first read is the identity, for they are read oldest first`() {
        val store = unguarded(listOf(Stamp("older", "2026-01-01T00:00:00Z"), Stamp("newer", "2026-02-01T00:00:00Z")))

        assertEquals("older", StoreIdentityResolver(store).resolve().id)
        assertEquals(1, store.executed.size, "a duplicate is reported and never deleted")
    }

    @Test
    fun `a stamp that vanished between the write and the read is written again, with a new id offered`() {
        val store = unguarded(emptyList(), listOf(Stamp("store-2", "2026-01-01T00:00:00Z")))

        assertEquals("store-2", StoreIdentityResolver(store).resolve().id)
        assertEquals(2, store.executed.size)
        assertEquals(2, store.executed.map { it.parameters["storeId"] }.distinct().size)
    }

    @Test
    fun `a stamp that is written three times and never read back is an error that names the database`() {
        val store = unguarded(emptyList(), emptyList(), emptyList())

        val failed = assertFailsWith<IllegalStateException> { StoreIdentityResolver(store).resolve() }

        assertContains(failed.message.orEmpty(), "Could not establish identity for the store at 'recording'")
        assertContains(failed.message.orEmpty(), "written 3 times and never read back")
        assertEquals(3, store.executed.size)
        assertEquals(3, store.queried.size)
    }

    @Test
    fun `without the constraint a failed write is the caller's at once, for no other writer explains it`() {
        val refused = IllegalStateException("Cannot resolve conflicting transactions")
        val store = RecordingStore(DatabaseType.NEPTUNE, supportsSchemaManagement = false) { refused }

        assertSame(refused, assertFailsWith<IllegalStateException> { StoreIdentityResolver(store).resolve() })
        assertEquals(1, store.executed.size)
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `where the store manages a schema the key is made unique before the stamp is written`() {
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            if (ran.statement == cypherStamp.readStamps) listOf(Stamp("store-3", "2026-01-01T00:00:00Z")) else emptyList<Any?>()
        }

        assertEquals("store-3", StoreIdentityResolver(store).resolve().id)
        val created = store.schema.single { it.startsWith("CREATE CONSTRAINT") }
        assertContains(created, "DrivineStore")
        assertContains(created, "key")
        assertEquals(1, store.executed.size)
    }

    @Test
    fun `a plain index on the key in the way of the constraint is replaced by it, for the label is Drivine's`() {
        val index = mapOf("name" to "store_key", "type" to "RANGE", "entityType" to "NODE", "labelsOrTypes" to listOf("DrivineStore"), "properties" to listOf("key"))
        var dropped = false
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-6", "2026-01-01T00:00:00Z"))
                ran.statement.startsWith("DROP INDEX") -> emptyList<Any?>().also { dropped = true }
                ran.statement.startsWith("CREATE CONSTRAINT") ->
                    if (dropped) emptyList<Any?>() else Neo4jException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:DrivineStore {key}).")
                ran.statement.startsWith("SHOW INDEXES") -> if (dropped) emptyList() else listOf(index)
                else -> emptyList<Any?>()
            }
        }

        assertEquals("store-6", StoreIdentityResolver(store).resolve().id)
        val dropAt = store.schema.indexOf("DROP INDEX `store_key` IF EXISTS")
        assertTrue(dropAt >= 0, "expected the index dropped: ${store.schema}")
        assertTrue(store.schema.drop(dropAt).any { it.startsWith("CREATE CONSTRAINT") }, "expected the constraint made after it: ${store.schema}")
    }

    @Test
    fun `when the constraint cannot be made the identity is still resolved, best effort`() {
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement == cypherStamp.readStamps -> listOf(Stamp("store-4", "2026-01-01T00:00:00Z"))
                ran.statement == cypherStamp.assignIfAbsent -> emptyList<Any?>()
                else -> IllegalStateException("no privilege to manage the schema")
            }
        }

        assertEquals("store-4", StoreIdentityResolver(store).resolve().id)
        assertTrue(store.schema.isNotEmpty(), "the constraint was tried")
    }

    @Test
    fun `on Oracle the graph is made first, on a schema connection, and the stamp is written and read in SQL`() {
        val graph = OracleGraph("g")
        val grammar = OracleSchemaGrammar(graph)
        val statements = grammar.storeStamp("DrivineStore", "key")
        val store = RecordingStore(DatabaseType.ORACLE, ddl = grammar, graphName = "g", supportsSchemaManagement = false) { ran ->
            if (ran.statement == statements.readStamps) listOf(Stamp("store-5", "2026-01-01T00:00:00Z")) else emptyList<Any?>()
        }

        val identity = StoreIdentityResolver(store).resolve()

        assertEquals(StoreIdentity("store-5", DatabaseType.ORACLE, "2026-01-01T00:00:00Z"), identity)
        assertEquals(listOf(graph.ensuring), store.schema)
        assertEquals(statements.assignIfAbsent, store.executed.single().statement)
        assertEquals("ORACLE", store.executed.single().parameters["engine"])
    }

    @Test
    fun `a manager with no schema grammar whose store speaks no Cypher is sent nothing, and told why`() {
        val store = RecordingStore(DatabaseType.ORACLE, schemaGrammar = null, supportsSchemaManagement = false)

        val refused = assertFailsWith<DrivineException> { StoreIdentityResolver(store).resolve() }

        assertContains(refused.message.orEmpty(), "ORACLE does not speak Cypher, and this PersistenceManager has no schema grammar to write the store's identity with.")
        assertTrue(store.executed.isEmpty() && store.queried.isEmpty() && store.schema.isEmpty())
    }
}
