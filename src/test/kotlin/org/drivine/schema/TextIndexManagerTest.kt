package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.test.RecordingStore
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What [IndexManager] does with a [TextIndexSpec], over a [RecordingStore]: on Neo4j it makes and
 * lists one, and on every other engine it refuses the spec before anything is read or made, rather
 * than make another index in its place.
 */
class TextIndexManagerTest {

    private val spec = TextIndexSpec("Chunk", "title")

    @Test
    fun `on Neo4j ensure makes the TEXT index`() {
        val store = RecordingStore(DatabaseType.NEO4J)

        val result = store.indexes.ensure(spec)

        assertTrue(result is EnsureResult.Created)
        assertContains(store.schema, "CREATE TEXT INDEX `Chunk_title_text` IF NOT EXISTS FOR (n:`Chunk`) ON (n.`title`)")
    }

    @Test
    fun `on Neo4j list reports a TEXT index`() {
        val row = mapOf(
            "name" to "Chunk_title_text", "type" to "TEXT", "entityType" to "NODE",
            "labelsOrTypes" to listOf("Chunk"), "properties" to listOf("title"), "options" to null,
        )
        val store = RecordingStore(DatabaseType.NEO4J) { listOf(row) }

        assertEquals(
            listOf(SchemaItemInfo(SchemaItemKind.TEXT_INDEX, "Chunk", listOf("title"), name = "Chunk_title_text")),
            store.indexes.list(),
        )
    }

    @ParameterizedTest
    @MethodSource("enginesWithoutTextIndex")
    fun `elsewhere ensure, recreate, find and drop refuse it before anything runs`(grammar: SchemaGrammar) {
        val store = RecordingStore(DatabaseType.NEO4J, ddl = grammar)

        listOf<() -> Any?>(
            { store.indexes.ensure(spec) },
            { store.indexes.recreate(spec) },
            { store.indexes.find(spec) },
            { store.indexes.drop(spec) },
        ).forEach { call ->
            val refused = assertFailsWith<DrivineException> { call() }
            assertContains(refused.message!!, "cannot be made on ${grammar.engine}")
            assertContains(refused.message!!, "FullTextIndexSpec")
        }
        assertEquals(emptyList(), store.schema)
    }

    @ParameterizedTest
    @MethodSource("enginesWithoutTextIndex")
    fun `elsewhere the grammar refuses to write it too`(grammar: SchemaGrammar) {
        assertFailsWith<DrivineException> { grammar.createIndex(spec) }
    }

    @ParameterizedTest
    @MethodSource("enginesWithoutTextIndex")
    fun `elsewhere list does not ask for TEXT indexes`(grammar: SchemaGrammar) {
        val store = RecordingStore(DatabaseType.NEO4J, ddl = grammar)

        store.indexes.list()

        val asked = listOf(SchemaItemKind.VECTOR_INDEX, SchemaItemKind.RANGE_INDEX, SchemaItemKind.FULLTEXT_INDEX)
            .map { grammar.listIndexesQuery(it) }
            .toSet()
        assertEquals(asked, store.schema.toSet())
    }

    companion object {
        @JvmStatic
        fun enginesWithoutTextIndex(): List<SchemaGrammar> = listOf(
            MemgraphSchemaGrammar(),
            FalkorDbSchemaGrammar(),
            OracleSchemaGrammar(OracleGraph("g")),
            PostgresSchemaGrammar(PostgresGraph("g")),
        )
    }
}
