package org.drivine.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.marker.MarkerInventory
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.model.Stamps
import org.drivine.store.StoreIdentityResolver
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * drivine4j's schema marker, store stamp and node stamp, which call the Cypher compiler's module
 * (`org.drivine.cypher.store.marker` and `store.Stamps`, drivine-cypher-spec#106), against `marker.yaml`: what each store
 * keeps of the marker and the stamps as drivine4j wrote them before it called the module, which the three Drivine
 * libraries are to write alike. Each is compared byte for byte through drivine4j's own types and API, so what the module
 * gives is what drivine4j runs:
 *
 * - each store's marker statements, as its grammar gives them and as [SchemaVersionStore] sends them on a
 *   [RecordingStore], with what each binds, what is made first, and the constraint that keeps one marker for each scope;
 * - each inventory as a record writes it, behind Oracle's mark there, and read back to its keys, runtime and stale items,
 *   as are the stored texts of `reads`; the inventory keys, the scopes, and the version token of several catalogs;
 * - each store's stamp statements, as its grammar gives them and as [StoreIdentityResolver] sends them, with what it
 *   binds and the engine it names;
 * - the node stamp's form, its tokens as `String.format("%016x")` wrote them, and how drivine4j's [Stamps] takes one apart.
 *
 * Nothing here touches a database.
 *
 * `src/test/resources/cypher/marker.yaml` is drivine-cypher-spec's `marker.yaml` at the tag the copy's `VERSION` names,
 * copied unchanged with the spec's other files by `scripts/sync-cypher-spec.sh` and checked against it by
 * `scripts/check-cypher-spec.sh`; the compiler `gradle/cypher-compiler.properties` pins is that tag's release.
 */
class StoreMarkerGoldenTest {

    private val pinned: JsonNode =
        ObjectMapper(YAMLFactory()).readTree(requireNotNull(javaClass.getResource("/cypher/marker.yaml")) { "No spec file cypher/marker.yaml" })

    private val graph = pinned["graph"].textValue()

    private val postgres = PostgresGraph(graph)
    private val oracle = OracleGraph(graph)
    private val sqlite = SqliteGraph(graph)

    /** Each store `marker.yaml` names, as drivine4j speaks to it: its type and its grammar. */
    private class Store(val type: DatabaseType, val grammar: SchemaGrammar, val graphName: String?)

    private val stores: Map<String, Store> = linkedMapOf(
        "neo4j" to Store(DatabaseType.NEO4J, Neo4jSchemaGrammar(), null),
        "memgraph" to Store(DatabaseType.MEMGRAPH, MemgraphSchemaGrammar(), null),
        "falkordb" to Store(DatabaseType.FALKORDB, FalkorDbSchemaGrammar(), null),
        "postgres" to Store(DatabaseType.POSTGRES, PostgresSchemaGrammar(postgres), graph),
        "oracle" to Store(DatabaseType.ORACLE, OracleSchemaGrammar(oracle), graph),
        "sqlite" to Store(DatabaseType.SQLITE, SqliteSchemaGrammar(sqlite), graph),
    )

    /** What a SQL store's create begins with, which `marker.yaml` leaves out of a constraint, as `store.yaml` does. */
    private val tables: Map<String, List<String>> = mapOf(
        "postgres" to listOf(postgres.ensuring),
        "oracle" to listOf(oracle.ensuring),
        "sqlite" to sqlite.ddl,
    )

    /** What `prepare` names in `marker.yaml`: the statements of `store.yaml`'s `tables` that make the store. */
    private fun prepared(id: String, name: JsonNode): String? = when (val text = name.textValue()) {
        null -> null
        "ensuring" -> when (id) {
            "postgres" -> postgres.ensuring
            "oracle" -> oracle.ensuring
            else -> fail("$id has no ensuring")
        }
        "ddl[0]" -> sqlite.ddl.first()
        else -> fail("$id: no prepare $text")
    }

    /** A manager over [id]'s store that answers each statement with [answer], as drivine4j's classes are given one. */
    private fun recording(id: String, supportsSchemaManagement: Boolean = true, answer: (RecordingStore.Ran) -> Any? = { emptyList<Any?>() }): RecordingStore {
        val store = stores.getValue(id)
        return RecordingStore(store.type, ddl = store.grammar, graphName = store.graphName, supportsSchemaManagement = supportsSchemaManagement, answer = answer)
    }

    private fun markerOf(id: String) = pinned["marker"]["stores"][id] ?: fail("marker.yaml has no marker statements for $id")

    /** How many values a test compared with `marker.yaml`'s, which it reports, so that a case left unread shows. */
    private var compared = 0

    private fun <T> same(expected: T, actual: T, what: String) {
        assertEquals(expected, actual, what)
        compared++
    }

    @AfterEach
    fun report(test: TestInfo) {
        assertTrue(compared > 0, "${test.displayName} compared nothing with marker.yaml")
        println("marker.yaml: ${test.displayName}: $compared comparisons")
    }

    // ----- The marker -----

    @Test
    fun `the marker's names and the scope of each owner are drivine4j's`() {
        val marker = pinned["marker"]
        same(marker["label"].textValue(), SchemaVersionStore.LABEL, "label")
        same(marker["scopeProperty"].textValue(), SchemaVersionStore.SCOPE_PROPERTY, "scope property")
        same(marker["defaultScope"].textValue(), SchemaVersionStore.DEFAULT_SCOPE, "default scope")
        for (case in pinned["scopeFor"]) {
            val owner = case["owner"].textValue()
            same(case["scope"].textValue(), SchemaVersionStore.scopeFor(owner), "the scope of the owner $owner")
        }
    }

    @Test
    fun `each store's marker statements are what its grammar gives and the version store sends`() {
        for ((id, store) in stores) {
            val expected = markerOf(id)
            val statements = store.grammar.versionMarker(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)
            same(prepared(id, expected["prepare"]), statements.prepare, "$id: prepare")
            same(expected["read"]["version"].textValue(), statements.read("version"), "$id: read version")
            same(expected["read"]["items"].textValue(), statements.read("items"), "$id: read items")
            same(expected["merge"].textValue(), statements.merge, "$id: merge")
            same(expected["deleteAllButNewest"].textValue(), statements.deleteAllButNewest, "$id: deleteAllButNewest")
            same(expected["delete"].textValue(), statements.delete, "$id: delete")
            same(expected["scopes"].textValue(), statements.scopes, "$id: scopes")

            // What the version store sends, and binds, for each thing it is asked.
            val recording = recording(id)
            val versions = SchemaVersionStore(recording, "owner")
            versions.storedVersion()
            versions.storedRecord()
            val before = System.currentTimeMillis()
            versions.record("v1", listOf("a", "b"))
            val after = System.currentTimeMillis()
            versions.clear()
            versions.otherScopes()
            same(listOfNotNull(prepared(id, expected["prepare"])), recording.schema, "$id: what is made first, once, on a schema connection")
            val (readVersion, readItems, scopes) = recording.queried
            same(expected["read"]["version"].textValue(), readVersion.statement, "$id: the version read")
            same(expected["read"]["items"].textValue(), readItems.statement, "$id: the items read")
            for (read in listOf(readVersion, readItems)) same(texts(expected["readParameters"]).associateWith { "owner" }, read.parameters, "$id: a read binds the scope")
            same(expected["scopes"].textValue() ?: pinned["marker"]["scopesCypher"].textValue(), scopes.statement, "$id: the scopes read")
            val (merge, delete) = recording.executed
            same(expected["merge"].textValue(), merge.statement, "$id: the merge sent")
            same(texts(expected["mergeParameters"]), merge.parameters.keys.toList(), "$id: what the merge binds")
            same("owner", merge.parameters["scope"], "$id: the scope bound")
            same("v1", merge.parameters["version"], "$id: the version bound")
            same(expected["itemsMark"].textValue() + "a\nb", merge.parameters["items"], "$id: the items bound, behind the store's mark")
            if ("now" in merge.parameters) assertTrue(merge.parameters["now"] as Long in before..after, "$id: the moment bound is this process's clock")
            same(expected["delete"].textValue(), delete.statement, "$id: the delete sent")
            assertNull(statements.mergeBindings("owner", null, "")["version"], "$id: no version is bound as null")
        }
    }

    @Test
    fun `all but the newest marker of the scope are removed before the constraint that keeps one is made`() {
        same(constraintSpec(pinned["marker"]["constraint"]), SchemaMarker.CONSTRAINT, "the marker's constraint")
        for ((id, store) in stores) {
            val expected = markerOf(id)
            same(statements(expected["constraint"]), made(id, store.grammar, SchemaMarker.CONSTRAINT), "$id: the marker's constraint made")
            if (id == "falkordb") continue // its constraint is built in the background, which a recording store never finishes
            val recording = recording(id)
            SchemaVersionStore(recording, "owner").ensureSingleton()
            same(expected["deleteAllButNewest"].textValue(), recording.executed.single().statement, "$id: the duplicates removed")
            same(mapOf("scope" to "owner"), recording.executed.single().parameters, "$id: of the scope")
            val sent = recording.schema
            for (statement in statements(expected["constraint"])) assertTrue(statement in sent, "$id: ensureSingleton makes $statement")
        }
    }

    // ----- The inventory -----

    @Test
    fun `each inventory is recorded and read back on each store as drivine4j recorded and read it`() {
        for (case in pinned["inventory"]) {
            val name = case["name"].textValue()
            val record = case["record"]
            val items = case["items"].textValue()
            val read = inventory(case["read"])
            for (id in stores.keys) {
                val stored = markerOf(id)["itemsMark"].textValue() + items
                val writing = recording(id)
                SchemaVersionStore(writing, "owner").record("v1", texts(record["keys"]), texts(record["runtime"]).toSet(), texts(record["stale"]).toSet())
                same(stored, writing.executed.single().parameters["items"], "$name: the items written on $id")
                val reading = recording(id) { if ("items" in it.statement) listOf(stored) else emptyList<Any?>() }
                same(read, SchemaVersionStore(reading, "owner").storedRecord(), "$name: the items read back on $id")
                same(read.keys, SchemaVersionStore(reading, "owner").storedInventory(), "$name: the inventory read back on $id")
            }
        }
        assertTrue(pinned["inventory"].size() > 0, "marker.yaml has no inventories")
    }

    @Test
    fun `each text a marker may hold is read as drivine4j read it`() {
        for (case in pinned["reads"]) {
            val stored = case["stored"].textValue()
            for (id in stores.keys) {
                val expected = (if (id == "oracle") case["oracle"] else null) ?: case["read"]
                val reading = recording(id) { if ("items" in it.statement) listOfNotNull(stored) else emptyList<Any?>() }
                same(expected.takeUnless { it.isNull }?.let(::inventory), SchemaVersionStore(reading, "owner").storedRecord(), "$id reads ${stored?.let { "\"$it\"" }}")
            }
        }
    }

    @Test
    fun `each item's inventory key is drivine4j's`() {
        for (entry in pinned["keys"]) {
            val spec = spec(entry["spec"])
            same(entry["key"].textValue(), spec.inventoryKey, "the key of $spec")
        }
        assertTrue(pinned["keys"].size() > 0, "marker.yaml has no keys")
    }

    @Test
    fun `the version token of several catalogs is drivine4j's`() {
        for (case in pinned["versions"]) {
            val versions = case["versions"].map { it.textValue() }
            val catalogs = versions.map { version -> SchemaCatalog.of().let { if (version == null) it else it.withVersion(version) } }
            same(case["token"].textValue(), SchemaCatalog.combineVersions(catalogs), "the token of $versions")
        }
    }

    // ----- The store's stamp -----

    @Test
    fun `each store's stamp statements are what its grammar gives and the resolver sends`() {
        val stamp = pinned["storeStamp"]
        same(constraintSpec(stamp["constraint"]), StoreIdentityResolver.KEY_CONSTRAINT, "the stamp's constraint")
        same(stamp["key"].textValue(), StoreIdentityResolver.SINGLETON_KEY, "the stamp's key")
        same(stamp["label"].textValue(), StoreIdentityResolver.KEY_CONSTRAINT.label, "the stamp's label")
        same(stamp["keyProperty"].textValue(), StoreIdentityResolver.KEY_CONSTRAINT.properties.single(), "the stamp's key property")
        for ((id, store) in stores) {
            val expected = stamp["stores"][id] ?: fail("marker.yaml has no stamp statements for $id")
            val statements = store.grammar.storeStamp(stamp["label"].textValue(), stamp["keyProperty"].textValue())
            same(prepared(id, expected["prepare"]), statements.prepare, "$id: prepare")
            same(expected["assignIfAbsent"].textValue(), statements.assignIfAbsent, "$id: assignIfAbsent")
            same(expected["readStamps"].textValue(), statements.readStamps, "$id: readStamps")
            same(statements(expected["constraint"]), made(id, store.grammar, StoreIdentityResolver.KEY_CONSTRAINT), "$id: the stamp's constraint made")

            // What the resolver sends and binds, with no constraint to ensure first.
            val recording = recording(id, supportsSchemaManagement = false) {
                if (it.statement == statements.readStamps) listOf(StoreIdentityResolver.Stamp("s", "t")) else emptyList<Any?>()
            }
            StoreIdentityResolver(recording).resolve()
            same(listOfNotNull(prepared(id, expected["prepare"])), recording.schema, "$id: what is made first")
            val assign = recording.executed.single()
            same(expected["assignIfAbsent"].textValue(), assign.statement, "$id: the stamp made")
            same(texts(stamp["parameters"]), assign.parameters.keys.toList(), "$id: what it binds")
            same(stamp["key"].textValue(), assign.parameters["key"], "$id: the key bound")
            same(expected["engine"].textValue(), assign.parameters["engine"], "$id: the engine named")
            UUID.fromString(assign.parameters["storeId"] as String)
            val assignedAt = assign.parameters["assignedAt"] as String
            same(Instant.parse(assignedAt).toString(), assignedAt, "$id: the moment bound as Instant.toString writes it")
            same(expected["readStamps"].textValue(), recording.queried.single().statement, "$id: the stamps read")
        }
    }

    // ----- The node stamp -----

    @Test
    fun `a node stamp is written and taken apart as drivine4j does`() {
        val stamps = pinned["nodeStamps"]
        same(stamps["property"].textValue(), Stamps.PROPERTY, "property")
        same(stamps["token"].intValue(), Stamps.TOKEN, "token")
        same(stamps["neverStamped"].textValue(), Stamps.NEVER_STAMPED, "never stamped")
        for (case in stamps["tokens"]) {
            val bits = case["bits"].textValue().toLong()
            // The token as drivine4j formatted it before it called the module, and as the module writes it now.
            same(case["token"].textValue(), String.format("%016x", bits), "the token of $bits as drivine4j formatted it")
            same(case["token"].textValue(), org.drivine.cypher.store.Stamps.token(bits), "the token of $bits as the module writes it")
        }
        for (case in stamps["stamps"]) {
            val stamp = case["stamp"].textValue()
            same(case["nodeToken"].textValue(), Stamps.nodeToken(stamp), "the node token of \"$stamp\"")
            same(case["linksToken"].textValue(), Stamps.linksToken(stamp), "the links token of \"$stamp\"")
            same(case["twoTokens"].booleanValue(), Stamps.isTwoTokens(stamp), "whether \"$stamp\" is two tokens")
            same(case["hasNodeToken"].booleanValue(), Stamps.hasNodeToken(stamp), "whether \"$stamp\" has a node token")
            same(case["withoutLinks"].textValue(), Stamps.withoutLinks(stamp), "\"$stamp\" without its links")
        }
        val fresh = Stamps.fresh()
        assertTrue(Regex("[0-9a-f]{${Stamps.TOKEN}}${stamps["separator"].textValue()}[0-9a-f]{${Stamps.TOKEN}}").matches(fresh), fresh)
    }

    // ----- Reading marker.yaml -----

    /** What [grammar] makes of [spec], without the statements that make the store first: each a text, or `{native}`. */
    private fun made(id: String, grammar: SchemaGrammar, spec: UniquenessConstraintSpec): List<Any> {
        val made = grammar.createConstraint(spec.made()).map {
            when (it) {
                is SchemaStatement.Cypher -> it.statement
                is SchemaStatement.Native -> mapOf("native" to it.args)
            }
        }
        val prefix = tables[id].orEmpty()
        assertEquals(prefix, made.take(prefix.size), "$id: the constraint's create makes the store first")
        return made.drop(prefix.size)
    }

    private fun inventory(node: JsonNode): MarkerInventory =
        MarkerInventory(texts(node["keys"]), texts(node["runtime"]).toSet(), texts(node["stale"]).toSet())

    private fun constraintSpec(node: JsonNode): UniquenessConstraintSpec {
        assertEquals(SchemaItemKind.UNIQUENESS_CONSTRAINT.name, node["kind"].textValue())
        return UniquenessConstraintSpec(node["label"].textValue(), texts(node["properties"]))
    }

    /** A spec of `marker.yaml`'s `keys`: a vector index's size and similarity don't reach its key, so any do. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"].textValue()
        val properties = texts(node["properties"])
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        return when (SchemaItemKind.valueOf(node["kind"].textValue())) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, ignoreCase = ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name)
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(label, properties.single(), 384, SimilarityFunction.COSINE, name)
        }
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue() }

    /** Each statement as its text, or `{native}`, as [made] gives them. */
    private fun statements(node: JsonNode): List<Any> = node.map {
        if (it.isTextual) it.textValue() else mapOf("native" to texts(it["native"]))
    }
}
