package org.drivine.schema

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.cypher.store.schema.MemgraphSchema
import org.drivine.cypher.store.schema.Neo4jSchema
import org.drivine.cypher.store.schema.OracleSchema
import org.drivine.cypher.store.schema.PostgresSchema
import org.drivine.cypher.store.schema.SqliteSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [storeSchema]'s mapping of each grammar onto the module's schema of its store: the options a grammar is made with
 * (Neo4j 4's id function, Memgraph's vector capacity, a SQL store's graph) reach the module's schema, and a grammar of
 * another kind is asked itself. No database is spoken to.
 */
class StoreSchemasTest {

    @Test
    fun `Neo4j 4's grammar gives the module's Neo4j schema its id function`() {
        val grammar = Neo4jSchemaGrammar(elementIdFunction = "id")
        val store = assertIs<Neo4jSchema>(grammar.storeSchema { null })
        assertEquals("id(n)", store.elementId("n"))
        assertEquals(grammar.elementId("n"), store.elementId("n"))
    }

    @Test
    fun `Memgraph's grammar gives the module's Memgraph schema the capacity a vector index is made with`() {
        val grammar = MemgraphSchemaGrammar(vectorIndexCapacity = 500)
        val store = assertIs<MemgraphSchema>(grammar.storeSchema { null })
        val spec = VectorIndexSpec("Proposition", "embedding", 1536)
        val made = assertIs<SchemaStatement.Cypher>(schemaStatement(store.ddl.createIndex(spec, null).single()))
        assertContains(made.statement, """"capacity": 500}""")
        assertEquals(grammar.createIndex(spec).single(), made)
    }

    @Test
    fun `a SQL store's grammar gives the module's schema of the graph it writes for, with its marker`() {
        val stores = listOf(
            PostgresSchemaGrammar(PostgresGraph("orders")) to PostgresSchema::class,
            OracleSchemaGrammar(OracleGraph("orders")) to OracleSchema::class,
            SqliteSchemaGrammar(SqliteGraph("orders")) to SqliteSchema::class,
        )
        stores.forEach { (grammar, kind) ->
            val store = grammar.storeSchema { null }
            assertEquals(kind, store::class, grammar.engine)
            val listing = store.ddl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
            assertContains(listing, "orders", ignoreCase = true, message = grammar.engine)
            assertEquals(grammar.listIndexesQuery(SchemaItemKind.RANGE_INDEX), listing, grammar.engine)
            val marker = grammar.versionMarker(SchemaMarker.LABEL, SchemaMarker.SCOPE_PROPERTY)
            assertEquals(marker.merge, markerStatements(store).merge, grammar.engine)
            assertEquals(marker.scopes, markerStatements(store).scopes, grammar.engine)
        }
    }

    @Test
    fun `a grammar of another kind is asked itself, its marker's statements its own`() {
        val sqlite = SqliteSchemaGrammar(SqliteGraph("orders"))
        val wrapped = object : SchemaGrammar by sqlite {}
        val store = assertIs<GrammarStoreSchema>(wrapped.storeSchema { null })
        assertSame(store.markers, markerStatements(store))
        assertEquals(sqlite.versionMarker(SchemaMarker.LABEL, SchemaMarker.SCOPE_PROPERTY).scopes, markerStatements(store).scopes)
        assertEquals(sqlite.listIndexesQuery(SchemaItemKind.RANGE_INDEX), store.ddl.listIndexesQuery(SchemaItemKind.RANGE_INDEX))
    }
}
