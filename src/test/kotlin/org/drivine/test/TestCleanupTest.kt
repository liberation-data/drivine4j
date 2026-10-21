package org.drivine.test

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.connection.DatabaseType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What [TestCleanup] sends each engine to remove what an earlier run of a test left. The store is a [RecordingStore]. */
class TestCleanupTest {

    private fun oracle(graphName: String?) =
        RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(OracleGraph(graphName ?: OracleGraph.DEFAULT_NAME)), graphName = graphName)

    @Test
    fun `a graph engine is sent a delete of the nodes that carry the tag, and FalkorDB a delete of every node`() {
        val neo4j = RecordingStore(DatabaseType.NEO4J)
        val falkor = RecordingStore(DatabaseType.FALKORDB)

        TestCleanup.beforeEach(neo4j, "my-test")
        TestCleanup.beforeEach(falkor, "my-test")

        assertEquals("MATCH (n) WHERE n.createdBy = 'my-test' DETACH DELETE n", neo4j.executed.single().statement)
        assertEquals("MATCH (n) DETACH DELETE n", falkor.executed.single().statement)
    }

    @Test
    fun `a tag is looked for under createdBy unless the test names another property`() {
        val named = RecordingStore(DatabaseType.MEMGRAPH)
        val unnamed = RecordingStore(DatabaseType.MEMGRAPH)

        TestCleanup.beforeEach(named, "suite", "contracts")
        TestCleanup.beforeEach(unnamed, "contracts")

        assertEquals("MATCH (n) WHERE n.suite = 'contracts' DETACH DELETE n", named.executed.single().statement)
        assertEquals("MATCH (n) WHERE n.createdBy = 'contracts' DETACH DELETE n", unnamed.executed.single().statement)
    }

    @Test
    fun `Oracle is sent SQL that deletes the tagged rows of the graph's node table, after the graph is made on a schema connection`() {
        val store = oracle("g")

        TestCleanup.beforeEach(store, "my-test")

        assertEquals(listOf(OracleGraph("g").ensuring), store.schema, "a cleanup may be the first thing to speak to the graph")
        assertEquals("DELETE FROM g_node n WHERE JSON_VALUE(n.props, '$.createdBy') = 'my-test'", store.executed.single().statement)
        assertTrue(store.executed.single().parameters.isEmpty())
    }

    @Test
    fun `on Oracle a property with a dot in it is one property, and a quote in the tag does not end the literal`() {
        val store = oracle("g")

        TestCleanup.beforeEach(store, "meta.suite", "it's")

        assertEquals("DELETE FROM g_node n WHERE JSON_VALUE(n.props, '$.\"meta.suite\"') = 'it''s'", store.executed.single().statement)
    }

    @Test
    fun `a manager that names no graph is cleaned in the default one`() {
        val store = oracle(null)

        TestCleanup.beforeEach(store, "my-test")

        assertEquals(listOf(OracleGraph("drivine").ensuring), store.schema)
        assertTrue(store.executed.single().statement.startsWith("DELETE FROM drivine_node n WHERE "))
    }

    @Test
    fun `a graph is reset on Neo4j and Memgraph only`() {
        for (type in listOf(DatabaseType.FALKORDB, DatabaseType.ORACLE)) {
            val store = RecordingStore(type)
            assertFailsWith<UnsupportedOperationException>("$type") { TestCleanup.resetGraph(store) }
            assertTrue(store.executed.isEmpty(), "$type")
        }
    }
}
