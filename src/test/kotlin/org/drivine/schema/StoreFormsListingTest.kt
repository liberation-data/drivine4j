package org.drivine.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine4j's own SQL grammars read a listing as the module does: `store.yaml`'s `forms` give rows captured from each
 * store's listings, the records first and then a row for each object under a name Drivine gives (`object`), and the items
 * the records are listed as. An object row is no item, read through the grammar itself or through [GrammarStoreSchema],
 * as a grammar that wraps one of drivine4j's is read.
 */
class StoreFormsListingTest {

    private val mapper = ObjectMapper()
    private val graph = StoreYaml.root["graph"].textValue()

    private fun grammar(store: String): SchemaGrammar = when (store) {
        "postgres" -> PostgresSchemaGrammar(PostgresGraph(graph))
        "oracle" -> OracleSchemaGrammar(OracleGraph(graph))
        "sqlite" -> SqliteSchemaGrammar(SqliteGraph(graph))
        else -> error("No SQL store $store")
    }

    private fun parse(store: SchemaGrammar, listing: String, rows: List<Any?>): List<SchemaItemInfo> =
        if (listing == "constraints") store.parseConstraintRows(rows) else store.parseIndexRows(rows)

    /** The items a case's records are listed as, each a map of what it says: its kind, label, properties and name, and its dimensions, similarity, analyzer or status where it has one. */
    private fun listed(node: JsonNode): List<Map<String, Any?>> =
        node.map { item -> item.properties().associate { (key, value) -> key to (if (value.isArray) value.map { it.asText() } else if (value.isNumber) value.intValue() else value.textValue()) } }

    private fun read(items: List<SchemaItemInfo>): List<Map<String, Any?>> = items.map {
        mapOf(
            "kind" to it.kind.name, "label" to it.label, "properties" to it.properties, "name" to it.name,
            "dimensions" to it.dimensions, "similarity" to it.similarity?.name, "analyzer" to it.analyzer, "status" to it.status,
        ).filterValues { value -> value != null }
    }

    @TestFactory
    fun `each forms listing is read as its records, and its object rows as no item`(): List<DynamicTest> =
        StoreYaml.root["forms"].map { case ->
            DynamicTest.dynamicTest(case["name"].textValue()) {
                val store = case["store"].textValue()
                val listing = case["listing"].textValue()
                val rows: List<Any?> = case["rows"].map { mapper.convertValue(it, Map::class.java) }
                val own = grammar(store)
                val wrapped = object : SchemaGrammar by own {}
                val expected = listed(case["listed"])
                assertEquals(expected, read(parse(own, listing, rows)), "$store's grammar")
                assertEquals(expected, read(parse(wrapped, listing, rows)), "a grammar that wraps $store's")
                val throughSchema = wrapped.storeSchema { null }
                assertTrue(throughSchema is GrammarStoreSchema)
                val schemaRead = if (listing == "constraints") throughSchema.parseConstraintRows(rows) else throughSchema.parseIndexRows(rows)
                assertEquals(expected, read(schemaRead), "$store's grammar as a GrammarStoreSchema")
            }
        }

    @TestFactory
    fun `some forms listing of each SQL store has object rows`(): List<DynamicTest> =
        listOf("postgres", "oracle", "sqlite").map { store ->
            DynamicTest.dynamicTest(store) {
                val objects = StoreYaml.root["forms"].filter { it["store"].textValue() == store }.flatMap { it["rows"] }.count { it.has("object") }
                assertTrue(objects > 0, "store.yaml's forms give $store no object rows")
            }
        }
}
