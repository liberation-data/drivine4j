package org.drivine.schema

import org.drivine.cypher.store.ddl.GeneratedFunctions
import com.fasterxml.jackson.databind.JsonNode
import org.drivine.DrivineException
import org.drivine.backend.oracle.LabelledIndexCatalog
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sql.SqlGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.schema.StoreYaml.spec
import org.drivine.schema.StoreYaml.texts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * drivine4j's schema grammars and graphs, which call the Cypher compiler's module for their DDL (drivine-cypher-spec#105),
 * against `store.yaml`: the store layout's statements as drivine4j wrote them before it called the module, which the three
 * Drivine libraries are to write alike. Each is compared byte for byte, through drivine4j's own types and API, so what the
 * module gives is what drivine4j runs:
 *
 * - each SQL store's tables, the block or the look that finds them made, and Oracle's functions;
 * - each case's index or constraint made, and dropped by its name and with none, or the [DrivineException] it is refused
 *   with, and FalkorDB's refusal of a constraint `GRAPH.CONSTRAINT` cannot take;
 * - each store's listings of its catalog, the statements that set a record's aliases, and Oracle's labelled indexes;
 * - the name each SQL store gives an object, and why each refuses a graph's name.
 *
 * The reads `store.yaml` pairs with its indexes are checked by the module's own test, and on the stores by
 * `PostgresStoreIndexPlanTest` and `OracleStoreIndexPlanTest`. Nothing here touches a database.
 *
 * `src/test/resources/cypher/store.yaml` is drivine-cypher-spec's `store.yaml` at the tag the copy's `VERSION` names,
 * copied unchanged with the spec's other files by `scripts/sync-cypher-spec.sh` and checked against it by
 * `scripts/check-cypher-spec.sh`; the compiler `gradle/cypher-compiler.properties` pins is that tag's release.
 */
class StoreDdlGoldenTest {

    private val store: JsonNode = StoreYaml.root

    private val graph = store["graph"].textValue()

    private val postgres = PostgresGraph(graph)
    private val oracle = OracleGraph(graph)
    private val sqlite = SqliteGraph(graph)

    private val grammars: Map<String, SchemaGrammar> = linkedMapOf(
        "postgres" to PostgresSchemaGrammar(postgres),
        "oracle" to OracleSchemaGrammar(oracle),
        "sqlite" to SqliteSchemaGrammar(sqlite),
        "neo4j" to Neo4jSchemaGrammar(),
        "memgraph" to MemgraphSchemaGrammar(),
        "falkordb" to FalkorDbSchemaGrammar(),
    )

    /** What a SQL store's create begins with, which `store.yaml` leaves out of each case: the statements that make the store. */
    private val tables: Map<String, List<String>> = mapOf(
        "postgres" to listOf(postgres.ensuring),
        "oracle" to listOf(oracle.ensuring),
        "sqlite" to sqlite.ddl,
    )

    @Test
    fun `each SQL store's tables, and what finds them made, are as drivine4j made them`() {
        val tables = store["tables"]
        // A function the module generates is held by its marker, which the module expands (GeneratedFunctions).
        fun expanded(node: JsonNode) = GeneratedFunctions.expand(node.textValue())
        assertEquals(tables["postgres"]["ddl"].map(::expanded), postgres.ddl, "postgres ddl")
        assertEquals(expanded(tables["postgres"]["ensuring"]), postgres.ensuring, "postgres ensuring")
        assertEquals(tables["oracle"]["ddl"].map(::expanded), oracle.ddl, "oracle ddl")
        assertEquals(tables["oracle"]["functions"].map(::expanded), oracle.functions, "oracle functions")
        assertEquals(expanded(tables["oracle"]["ensuring"]), oracle.ensuring, "oracle ensuring")
        assertEquals(texts(tables["sqlite"]["ddl"]), sqlite.ddl, "sqlite ddl")
        assertEquals(tables["sqlite"]["made"].textValue(), sqlite.made, "sqlite made")
        assertEquals(tables["sqlite"]["file"].textValue(), SqliteGraph.FILE, "sqlite file")
    }

    @Test
    fun `each index and constraint is made and dropped on each store as drivine4j made and dropped it`() {
        var checked = 0
        for (case in store["cases"]) {
            val name = case["name"].textValue()
            val made = spec(case["spec"]).made()
            val existing = case["existing"]?.let { SchemaItemInfo(made.kind, made.label, texts(it)) }
            val named = SchemaItemInfo(made.kind, made.label, made.properties, made.effectiveName)
            val unnamed = SchemaItemInfo(made.kind, made.label, made.properties)
            for ((id, grammar) in grammars) {
                val expected = case["stores"][id] ?: fail("$name: no statements for $id")
                val create = expected["create"]
                val prefix = tables[id].orEmpty().map { mapOf("text" to it) }
                assertEquals(
                    if (create.isArray) prefix + statements(create) else refusal(create),
                    attempt { if (made is IndexSpec) grammar.createIndex(made, existing) else grammar.createConstraint(made as ConstraintSpec) },
                    "$name: create on $id",
                )
                assertEquals(expectedOf(expected["drop"]), attempt { drop(grammar, named) }, "$name: drop on $id")
                assertEquals(expectedOf(expected["dropUnnamed"] ?: expected["drop"]), attempt { drop(grammar, unnamed) }, "$name: drop unnamed on $id")
                checked++
            }
            if (made is ConstraintSpec) {
                val refused = case["stores"]["falkordb"]["constrainable"]?.let(::refusal) ?: emptyList<Any>()
                assertEquals(refused, attempt { grammars.getValue("falkordb").requireConstrainable(made); emptyList() }, "$name: constrainable on falkordb")
            }
        }
        assertTrue(checked > 0, "store.yaml has no cases")
    }

    @Test
    fun `each store's catalog is listed and aliased as drivine4j listed and aliased it`() {
        val catalog = store["catalog"]
        val item = catalog["item"].let { SchemaItemInfo(SchemaItemKind.valueOf(it["kind"].textValue()), it["label"].textValue(), texts(it["properties"]), it["name"]?.textValue()) }
        val aliases = texts(catalog["aliases"])
        for ((id, grammar) in grammars) {
            val expected = catalog["stores"][id]
            for (kind in SchemaItemKind.entries) {
                assertEquals(expected["indexes"][kind.name].textValue(), grammar.listIndexesQuery(kind), "$id: list of $kind")
            }
            assertEquals(expected["constraints"].textValue(), grammar.listConstraintsQuery(), "$id: list of constraints")
            if (grammar is SchemaAliases) {
                assertEquals(expected["aliased"].textValue(), text(grammar.aliased(item, aliases)), "$id: aliased")
                assertEquals(expected["unaliased"].textValue(), text(grammar.aliased(item, emptyList())), "$id: unaliased")
            }
        }
        assertEquals(catalog["stores"]["oracle"]["labelled"].textValue(), LabelledIndexCatalog.query(oracle), "oracle: labelled indexes")
    }

    @Test
    fun `each SQL store names an object as drivine4j named it`() {
        val names = store["names"]
        for (entry in names) {
            val graph = entry["graph"].textValue()
            val sql: SqlGraph = when (val id = entry["store"].textValue()) {
                "postgres" -> PostgresGraph(graph)
                "oracle" -> OracleGraph(graph)
                "sqlite" -> SqliteGraph(graph)
                else -> fail("No SQL store $id")
            }
            val parts = texts(entry["parts"])
            assertEquals(entry["name"].textValue(), sql.objectName(entry["kind"].textValue(), parts), "${entry["store"].textValue()} $graph $parts")
        }
        assertTrue(names.size() > 0, "store.yaml has no names")
    }

    @Test
    fun `each SQL store refuses a graph's name as drivine4j refused it`() {
        for (entry in store["refusals"]) {
            val name = entry["graph"].textValue()
            val refusal = when (val id = entry["store"].textValue()) {
                "postgres" -> PostgresGraph.refusal(name)
                "oracle" -> OracleGraph.refusal(name)
                "sqlite" -> SqliteGraph.refusal(name)
                else -> fail("No SQL store $id")
            }
            assertEquals(entry["refusal"]?.takeUnless { it.isNull }?.textValue(), refusal, "${entry["store"].textValue()}: $name")
        }
    }

    private fun drop(grammar: SchemaGrammar, item: SchemaItemInfo): List<SchemaStatement> =
        if (item.kind == SchemaItemKind.UNIQUENESS_CONSTRAINT) grammar.dropConstraint(item) else grammar.dropIndex(item)

    private fun text(statement: SchemaStatement): String = (statement as SchemaStatement.Cypher).statement

    /** Each statement as `{text}` or `{native}`, as [attempt] gives a grammar's. */
    private fun statements(node: JsonNode): List<Map<String, Any>> = node.map {
        if (it.isTextual) mapOf("text" to it.textValue()) else mapOf("native" to texts(it["native"]))
    }

    private fun refusal(node: JsonNode): Map<String, Any> = mapOf("error" to node["error"].textValue())

    private fun expectedOf(node: JsonNode): Any = if (node.isArray) statements(node) else refusal(node)

    /** What [block] gives, each statement as `{text}` or `{native}`, or `{error}`, the message of the [DrivineException] it threw. */
    private fun attempt(block: () -> List<SchemaStatement>): Any = try {
        block().map {
            when (it) {
                is SchemaStatement.Cypher -> mapOf("text" to it.statement)
                is SchemaStatement.Native -> mapOf("native" to it.args)
            }
        }
    } catch (e: DrivineException) {
        mapOf("error" to (e.message ?: ""))
    }
}
