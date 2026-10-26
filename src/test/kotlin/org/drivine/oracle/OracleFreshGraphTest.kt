package org.drivine.oracle

import org.drivine.backend.oracle.OracleGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.schema.SchemaVersionStore
import org.drivine.store.StoreIdentityResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drivine's own nodes on a graph nothing has made yet: an application that enforces its schema, or
 * reads the store's identity, before it builds an object manager speaks to a graph with no tables.
 */
@OracleTests
class OracleFreshGraphTest {

    private val graph = "fresh"

    /** A manager for [graph], which is dropped first and made by nothing here. */
    private val manager = NonTransactionalPersistenceManager(
        JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = graph), graph, DatabaseType.ORACLE, SubtypeRegistry(),
    )

    @BeforeEach
    fun dropGraph() {
        OracleTestDatabase.drop("PROPERTY GRAPH $graph", "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema", "FUNCTION ${graph}_nulls_equal")
    }

    @Test
    fun `the schema marker is read and kept on a graph nothing has made`() {
        val store = SchemaVersionStore(manager)
        assertNull(store.storedVersion())
        store.ensureSingleton()
        store.record("v1", listOf("a", "b"))
        assertEquals("v1", store.storedVersion())
        assertEquals(listOf("a", "b"), store.storedInventory())
    }

    /**
     * What a startup does for an owner whose catalog has no version: the marker's key is not
     * constrained first, so nothing indexes the scope the marker is found by.
     */
    @Test
    fun `an owner with no version records its marker, and records it again, on a graph nothing has made`() {
        val store = SchemaVersionStore(manager, "an-owner")
        assertNull(store.storedInventory())

        store.record(null, listOf("a"))
        assertNull(store.storedVersion())
        assertEquals(listOf("a"), store.storedInventory())

        store.record("v1", listOf("a", "b"))
        assertEquals("v1", store.storedVersion())
        assertEquals(listOf("a", "b"), store.storedInventory())
        assertNull(SchemaVersionStore(manager).storedInventory(), "another owner's marker is its own")
    }

    @Test
    fun `the graph is made with the function a Cypher statement compares values that may hold a null by`() {
        OracleGraph(graph).ensure(manager)
        val f = OracleGraph(graph).nullsEqual
        val compared = "NVL(TO_CHAR($f(JSON('[null]'), JSON('[1]'))), 'null') || ',' || TO_CHAR($f(JSON('[1, null]'), JSON('[2, null]'))) || ',' || TO_CHAR($f(JSON('{\"x\": [1]}'), JSON('{\"x\": [1]}')))"
        assertEquals(listOf("null,0,1"), manager.query(QuerySpecification.withStatement("SELECT $compared AS r FROM DUAL")))
    }

    @Test
    fun `the store's identity is minted on a graph nothing has made`() {
        val identity = StoreIdentityResolver(manager).resolve()
        assertEquals(identity.id, StoreIdentityResolver(manager).resolve().id)
    }
}
