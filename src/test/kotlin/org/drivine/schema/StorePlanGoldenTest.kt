package org.drivine.schema

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.plan.DeclaredCatalog
import org.drivine.cypher.store.plan.ItemCall
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.Owners
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.cypher.store.plan.RuntimeCatalog
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * drivine4j's driver of the module's schema planner ([SchemaPlanDriver]) over every case of the spec's `plans.yaml`, on
 * each store the case runs on, through a scripted store ([RecordingStore]) that answers each statement as the case does:
 * every statement drivine4j sends must be the next the case's rounds give, each named statement as `store.yaml` or
 * `marker.yaml` pins it, sent the way the spec's README says drivine4j sends it (DDL, listings and waits on a schema
 * connection, the marker's statements on the auto-commit manager, FalkorDB's commands natively, the lowercased copies to
 * their record); and once the plan is done, drivine4j must log the case's lines, and throw the store's error it says to
 * rethrow, as it was thrown, or its failure, or return. The module's own test checks the planner decides as the case
 * says; this checks drivine4j runs what it decides. No database is spoken to.
 *
 * The case's `now`s are the driver's clock, and its polls aren't waited for.
 */
class StorePlanGoldenTest {

    private val yaml = ObjectMapper(YAMLFactory())
    private val plans: JsonNode = yaml.readTree(resource("plans.yaml"))
    private val store: JsonNode = StoreYaml.root
    private val marker: JsonNode = yaml.readTree(resource("marker.yaml"))["marker"]
    private val graph: String = store["graph"].textValue()
    private val database: String = plans["database"].textValue()

    private fun resource(name: String) = requireNotNull(StorePlanGoldenTest::class.java.getResource("/cypher/$name")) { "No spec file cypher/$name" }

    private val engines = mapOf(
        "neo4j" to DatabaseType.NEO4J, "memgraph" to DatabaseType.MEMGRAPH, "falkordb" to DatabaseType.FALKORDB,
        "postgres" to DatabaseType.POSTGRES, "oracle" to DatabaseType.ORACLE, "sqlite" to DatabaseType.SQLITE,
    )

    private fun grammar(id: String): SchemaGrammar = when (id) {
        "neo4j" -> Neo4jSchemaGrammar()
        "memgraph" -> MemgraphSchemaGrammar()
        "falkordb" -> FalkorDbSchemaGrammar()
        "postgres" -> PostgresSchemaGrammar(PostgresGraph(graph))
        "oracle" -> OracleSchemaGrammar(OracleGraph(graph))
        "sqlite" -> SqliteSchemaGrammar(SqliteGraph(graph))
        else -> fail("no store $id")
    }

    @Test
    fun `the cases are the design's 1 to 29, the form check's 30, and the item calls, and every store runs`() {
        val names = plans["cases"].map { it["case"].textValue() }
        assertEquals((1..30).toList(), names.filter { it.first().isDigit() }.map { it.takeWhile(Char::isDigit).toInt() }.distinct().sorted())
        assertTrue("item" in names)
        assertEquals(engines.keys, plans["cases"].flatMap { texts(it["stores"]) }.toSet())
    }

    @TestFactory
    fun `drivine4j runs each plan on each store as the case says`(): List<DynamicTest> = plans["cases"].map { case ->
        val name = "${case["case"].textValue()} ${case["name"].textValue()}"
        DynamicTest.dynamicTest(name) { runCase(name, case) }
    }

    private fun runCase(name: String, case: JsonNode) {
        val stores = texts(case["stores"])
        val catalogs = case["catalogs"].map(::catalog)
        val policy = policy(case["policy"])
        val timeout = case["buildTimeoutMillis"]?.longValue() ?: PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS
        var held = emptyList<RuntimeCatalog>()
        for ((index, step) in case["steps"].withIndex()) {
            val operation = operation(step["operation"])
            val candidate = SchemaPlanner.runtimeAfter(operation, held)
            val request = PlanRequest(database, operation, catalogs, SchemaPlanner.runtimeOn(database, candidate), policy, timeout)
            val refused = step["refused"]?.textValue()
            if (refused != null) {
                for (id in stores) assertEquals(refused, driver(RecordingStore(engines.getValue(id), grammar(id)), id, Script(emptyList())).validate(request), "$name, step $index on $id: refused")
                continue
            }
            held = candidate
            for (run in step["runs"]) for (id in texts(run["stores"])) replay("$name, step $index on $id", id, request, run)
        }
    }

    /** One statement the case's rounds give, as drivine4j sends it, and how the store answers it. */
    private class Sent(val path: String, val statement: String, val parameters: Map<String, Any?>, val answer: Any?, val delay: Long = 0, val round: Int) {
        override fun toString() = "$path: $statement $parameters"
    }

    /** What the store is asked, in order, checked against what the case gives, and answered as it says. */
    private inner class Script(private val expected: List<Sent>) {
        private var at = 0
        val failures = mutableMapOf<Int, Throwable>()
        val slept = mutableListOf<Long>()

        fun next(path: String, statement: String, parameters: Map<String, Any?>, name: String): Any? {
            val sent = expected.getOrNull(at++) ?: fail("$name: sent $path $statement $parameters past the ${expected.size} statement(s) the case gives")
            assertEquals(sent.path, path, "$name: statement ${at} is sent on ${sent.path}: $sent")
            assertEquals(sent.statement, statement, "$name: statement $at")
            assertEquals(plain(sent.parameters), plain(parameters), "$name: the parameters of statement $at, $statement")
            return sent.answer
        }

        fun done(name: String) {
            assertEquals(expected.size, at, "$name: sent ${at} of the ${expected.size} statement(s) the case gives; the next is ${expected.getOrNull(at)}")
            assertEquals(expected.map { it.delay }.filter { it > 0 }, slept, "$name: the polls' waits")
        }
    }

    private fun driver(recording: RecordingStore, id: String, script: Script, name: String = "", clock: () -> Long = { 0 }): SchemaPlanDriver {
        val copies = object : LowerCopyRecord {
            override fun stored(): Set<Pair<String, String>> {
                @Suppress("UNCHECKED_CAST")
                val rows = answered(script.next(COPIES, "read", emptyMap(), name)) as List<List<String>>
                return rows.map { it[0] to it[1] }.toSet()
            }

            override fun fillIn(label: String, properties: List<String>, again: Boolean) {
                assertTrue(again, "$name: the copies the planner leaves to fill are filled in, recorded or not")
                answered(script.next(COPIES, "fill $label $properties", emptyMap(), name))
            }
        }
        return SchemaPlanDriver(recording.executor, grammar(id), { copies }, clock, script.slept::add)
    }

    private fun answered(answer: Any?): Any? = if (answer is Throwable) throw answer else answer

    /** Runs [request] on [id], each statement answered as [expected]'s rounds say, and checks what drivine4j does with the report. */
    private fun replay(name: String, id: String, request: PlanRequest, expected: JsonNode) {
        val rounds = expected["rounds"].toList()
        val sent = rounds.withIndex().flatMap { (r, round) -> expand(id, round, r) }
        val script = Script(sent)
        val recording = RecordingStore(engines.getValue(id), grammar(id), native = true) { ran ->
            script.next(pathOf(ran), ran.statement, ran.parameters, name).let(::answered)
        }
        currentRecording = recording
        val nows = (rounds.map { it["now"].longValue() } + expected["done"].longValue()).iterator()
        val driver = driver(recording, id, script, name) { if (nows.hasNext()) nows.next() else fail("$name: the clock is read more often than once a round") }
        val report = expected["report"]
        val engine = driver.store.engine
        val logged = capture {
            val thrown = runCatching { driver.run(request, recording) }.exceptionOrNull()
            script.done(name)
            val rethrown = report["rethrown"]
            val failure = report["failure"]?.textValue()
            when {
                rethrown != null -> {
                    val round = rethrown["round"].intValue()
                    val failed = sent.last { it.round == round && it.answer is Throwable }.answer as Throwable
                    assertSame(failed, thrown, "$name: the store's error of round $round is rethrown as it was thrown")
                }
                failure != null -> {
                    assertTrue(thrown is DrivineException, "$name: the failure is thrown as drivine4j's error, not $thrown")
                    assertEquals(failure.replace("{engine}", engine), thrown.message, "$name: the failure")
                }
                thrown != null -> throw AssertionError("$name: the plan succeeded, and drivine4j threw", thrown)
            }
        }
        val log = report["log"].map { line -> listOf(line[0].textValue(), line[1].textValue(), line[2].textValue().replace("{engine}", engine)) }
        assertEquals(log, logged, "$name: the log lines")
        assertEquals(nows.hasNext(), false, "$name: the clock is read once a round")
    }

    /** The lines the schema managers' loggers log while [run] runs, each its level, logger and text. */
    private fun capture(run: () -> Unit): List<List<String>> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = listOf("SchemaManager", "IndexManager", "ConstraintManager").map { LoggerFactory.getLogger("org.drivine.schema.$it") as Logger }
        val levels = loggers.map { it.level }
        loggers.forEach { it.addAppender(appender); it.level = Level.INFO }
        try {
            run()
        } finally {
            loggers.forEachIndexed { i, logger -> logger.detachAppender(appender); logger.level = levels[i] }
        }
        return appender.list.filter { it.level.isGreaterOrEqual(Level.INFO) }.map { listOf(it.level.toString(), it.loggerName.substringAfterLast('.'), it.formattedMessage) }
    }

    private var currentRecording: RecordingStore? = null

    /** Which way a statement reached the recording store: its schema connection, or the manager's query or execute. */
    private fun pathOf(ran: RecordingStore.Ran): String {
        val recording = currentRecording!!
        return when {
            recording.queried.lastOrNull() === ran -> MANAGER_QUERY
            recording.executed.lastOrNull() === ran -> MANAGER_EXECUTE
            else -> SCHEMA
        }
    }

    // ----- The statements, by their names in store.yaml and marker.yaml -----

    private fun markerStore(id: String): JsonNode = marker["stores"][id]

    private fun prefix(id: String): List<JsonNode> {
        val tables = store["tables"][id] ?: return emptyList()
        return tables["ensuring"]?.let { listOf(it) } ?: tables["ddl"].toList()
    }

    private fun storeCase(name: String): JsonNode = store["cases"].firstOrNull { it["name"].textValue() == name } ?: fail("store.yaml has no case $name")

    /** A statement of DDL, as drivine4j sends it: its text on a schema connection, or FalkorDB's command natively. */
    private fun ddl(node: JsonNode, round: Int, answer: Any?): Sent =
        if (node.isTextual) Sent(SCHEMA, node.textValue(), emptyMap(), answer, round = round)
        else Sent(SCHEMA, texts(node["native"]).joinToString(" "), emptyMap(), answer, round = round)

    private fun ddlNodes(id: String, execute: JsonNode): List<JsonNode> {
        execute["create"]?.let { return prefix(id) + storeCase(it.textValue())["stores"][id]["create"].toList() }
        execute["drop"]?.let { return storeCase(it.textValue())["stores"][id]["drop"].toList() }
        execute["dropUnnamed"]?.let { return storeCase(it.textValue())["stores"][id]["dropUnnamed"].toList() }
        execute["statement"]?.let { return listOf(it) }
        execute["native"]?.let { return listOf(execute) }
        // The record of the catalog's item made in the current form, whose record said none.
        if (execute["formRecorded"]?.booleanValue() == true) return listOf(store["catalog"]["stores"][id]["formRecorded"])
        return when (val name = execute["marker"].textValue()) {
            "prepare" -> {
                val prepare = markerStore(id)["prepare"].textValue()
                val tables = store["tables"][id]
                listOf(if (prepare.startsWith("ddl[")) tables["ddl"][prepare.removePrefix("ddl[").removeSuffix("]").toInt()] else tables[prepare])
            }
            "constraint" -> prefix(id) + markerStore(id)["constraint"].toList()
            else -> fail("no marker DDL $name")
        }
    }

    private fun query(id: String, query: JsonNode, round: Int, delay: Long, answer: Any?): Sent {
        query["listing"]?.let { listing ->
            val catalog = store["catalog"]["stores"][id]
            val name = listing.textValue()
            val statement = if (name == "constraints") catalog["constraints"] else catalog["indexes"][name.removePrefix("indexes.")]
            return Sent(SCHEMA, statement.textValue(), emptyMap(), answer, delay, round)
        }
        query["indexState"]?.let {
            return Sent(SCHEMA, store["schema"]["stores"][id]["indexState"].textValue(), mapOf("name" to it.textValue()), answer, delay, round)
        }
        query["sample"]?.let { sample ->
            val spec = spec(sample["spec"])
            val limit = sample["limit"].longValue()
            val pinned = store["schema"]["samples"].firstOrNull { spec(it["spec"]) == spec && it["limit"].longValue() == limit }
                ?: fail("store.yaml samples no $spec with the limit $limit")
            return Sent(SCHEMA, pinned["stores"][id].textValue(), emptyMap(), answer, delay, round)
        }
        query["statement"]?.let { return Sent(SCHEMA, it.textValue(), emptyMap(), answer, delay, round) }
        return when (val name = query["marker"].textValue()) {
            "read.version", "read.items" ->
                Sent(MANAGER_QUERY, markerStore(id)["read"][name.removePrefix("read.")].textValue(), mapOf("scope" to query["scope"].textValue()), answer, delay, round)
            "scopes" -> Sent(MANAGER_QUERY, markerStore(id)["scopes"]?.takeUnless { it.isNull }?.textValue() ?: marker["scopesCypher"].textValue(), emptyMap(), answer, delay, round)
            else -> fail("no marker query $name")
        }
    }

    /** A round's statements on [id], as drivine4j sends them, up to the first that fails: those after it aren't run. */
    private fun expand(id: String, round: JsonNode, r: Int): List<Sent> {
        val now = round["now"].longValue()
        val sent = mutableListOf<Sent>()
        for (entry in round["actions"]) {
            val failed = entry["failed"]?.let { failure -> (failure["causes"] ?: failure).map(StoreErrorsTest::cause) }
            fun answer(rows: () -> Any?): Any? = if (failed != null) StoreErrorsTest.thrown(failed) else rows()
            when {
                entry["execute"] != null -> {
                    val execute = entry["execute"]
                    if (execute["marker"]?.textValue() == "deleteAllButNewest") {
                        sent += Sent(MANAGER_EXECUTE, markerStore(id)["deleteAllButNewest"].textValue(), mapOf("scope" to execute["scope"].textValue()), answer { emptyList<Any?>() }, round = r)
                    } else {
                        val nodes = ddlNodes(id, execute)
                        val at = entry["failed"]?.get("at")?.intValue() ?: 0
                        for ((k, node) in nodes.withIndex()) {
                            sent += ddl(node, r, if (failed != null && k == at) StoreErrorsTest.thrown(failed) else emptyList<Any?>())
                            if (failed != null && k == at) break
                        }
                    }
                }
                entry["query"] != null -> sent += query(id, entry["query"], r, entry["delay"]?.longValue() ?: 0, answer {
                    entry["items"]?.let { items ->
                        val constraints = entry["query"]["listing"]?.textValue() == "constraints"
                        StorePlanListingRows.rows(id, items.map(::item), constraints)
                    } ?: entry["rows"].map(::plain)
                })
                entry["awaitIndex"] != null -> {
                    val await = entry["awaitIndex"]
                    sent += Sent(SCHEMA, store["schema"]["stores"][id]["awaitIndex"].textValue(), mapOf("name" to await["name"].textValue(), "timeout" to await["timeout"].longValue()), answer { emptyList<Any?>() }, round = r)
                }
                entry["readLowerCopies"] != null -> sent += Sent(COPIES, "read", emptyMap(), answer { entry["rows"].map(::plain) }, round = r)
                entry["fillLowerCopies"] != null -> {
                    val fill = entry["fillLowerCopies"]
                    sent += Sent(COPIES, "fill ${fill["label"].textValue()} ${texts(fill["properties"])}", emptyMap(), answer { Unit }, round = r)
                }
                entry["writeMarker"] != null -> {
                    val write = entry["writeMarker"]
                    val scope = write["scope"].textValue()
                    val stored = markerStore(id)["itemsMark"].textValue() + write["items"].textValue()
                    val bound = mapOf("scope" to scope, "version" to write["version"]?.textValue(), "items" to stored, "now" to now)
                    val parameters = texts(markerStore(id)["mergeParameters"]).associateWith { bound[it] }
                    sent += Sent(MANAGER_EXECUTE, markerStore(id)["merge"].textValue(), parameters, answer { emptyList<Any?>() }, round = r)
                }
                else -> fail("$id: no action $entry")
            }
            if (failed != null) break
        }
        return sent
    }

    // ----- The request -----

    private fun operation(node: JsonNode): Operation {
        fun owners(of: JsonNode): Owners = if (of.isTextual) Owners.All else Owners.One(of["owner"]?.textValue())
        node["enforce"]?.let { return Operation.Enforce(owners(it)) }
        node["recreateAll"]?.let { return Operation.RecreateAll(owners(it)) }
        node["ensure"]?.let { return Operation.Ensure(it["owner"]?.textValue(), it["items"].map(::spec)) }
        node["remove"]?.let { return Operation.Remove(it["owner"]?.textValue(), it["items"].map(::spec)) }
        node["item"]?.let { return Operation.Item(ItemCall.valueOf(it["call"].textValue()), spec(it["spec"])) }
        fail("no operation $node")
    }

    private fun catalog(node: JsonNode) = DeclaredCatalog(node["owner"]?.textValue(), node["version"]?.textValue(), node["items"].map(::spec))

    private fun policy(node: JsonNode?): SchemaPolicy {
        val defaults = SchemaPolicy()
        if (node == null) return defaults
        return SchemaPolicy(
            mode = node["mode"]?.textValue()?.let(SchemaMode::valueOf) ?: defaults.mode,
            recreateOnDrift = node["recreateOnDrift"]?.booleanValue() ?: defaults.recreateOnDrift,
            recreateOnStartup = node["recreateOnStartup"]?.booleanValue() ?: defaults.recreateOnStartup,
            violationSampleSize = node["violationSampleSize"]?.intValue() ?: defaults.violationSampleSize,
            replaceBlockingIndex = node["replaceBlockingIndex"]?.booleanValue() ?: defaults.replaceBlockingIndex,
        )
    }

    /** A spec: a `store.yaml` case's by its name, or written as its cases write one. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        if (node.isTextual) return spec(storeCase(node.textValue())["spec"])
        val label = node["label"].textValue()
        val properties = texts(node["properties"])
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"].textValue())) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, node["nodeIdProperty"]?.textValue(), ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, option("analyzer")?.textValue())
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), option("dimensions")!!.intValue(),
                SimilarityFunction.valueOf(option("similarity")!!.textValue()), name,
                option("hnswM")?.intValue(), option("hnswEfConstruction")?.intValue(),
                listOfNotNull(
                    option("quantizationEnabled")?.let { Neo4jVectorOptions(quantizationEnabled = it.booleanValue()) },
                    option("efRuntime")?.let { FalkorDbVectorOptions(efRuntime = it.intValue()) },
                ),
            )
        }
    }

    private fun item(node: JsonNode): SchemaItemInfo = SchemaItemInfo(
        kind = SchemaItemKind.valueOf(node["kind"].textValue()),
        label = node["label"].textValue(),
        properties = texts(node["properties"]),
        name = node["name"]?.textValue(),
        dimensions = node["dimensions"]?.intValue(),
        similarity = node["similarity"]?.textValue()?.let(SimilarityFunction::valueOf),
        analyzer = node["analyzer"]?.textValue(),
        status = node["status"]?.textValue(),
        quantizationEnabled = node["quantizationEnabled"]?.booleanValue(),
        hnswM = node["hnswM"]?.intValue(),
        hnswEfConstruction = node["hnswEfConstruction"]?.intValue(),
    )

    private fun plain(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node.isArray -> node.map(::plain)
        node.isObject -> node.properties().associate { it.key to plain(it.value) }
        else -> fail("not a value: $node")
    }

    /** [value] with its numbers as longs, so a parameter bound as an int compares with the case's. */
    private fun plain(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Map<*, *> -> value.entries.associate { (k, v) -> k to plain(v) }
        is List<*> -> value.map(::plain)
        else -> value
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue() }

    private companion object {
        const val SCHEMA = "schema-connection"
        const val MANAGER_QUERY = "manager query"
        const val MANAGER_EXECUTE = "manager execute"
        const val COPIES = "lowercased copies"
    }
}

/**
 * The rows a store lists items as, the way its listing gives them, for `plans.yaml`'s answers, which give a listing as
 * the items it reads as: the module's test's own (`ListingRows`, in drivine-cypher-spec's `compiler/src/commonTest`),
 * which writes each store's rows once and checks they read back to the items over `store.yaml`'s listings.
 */
internal object StorePlanListingRows {

    fun rows(store: String, items: List<SchemaItemInfo>, constraints: Boolean): List<Any?> = when (store) {
        "neo4j" -> items.map { if (constraints) neo4jConstraint(it) else neo4jIndex(it) }
        "memgraph" -> items.map { if (constraints) listOf("unique", it.label, it.properties) else memgraphIndex(it) }
        "falkordb" -> if (constraints) items.map(::falkorDbConstraint) else falkorDbIndexes(items)
        else -> items.map { sqlRecord(store, it) }
    }

    private fun neo4jConstraint(item: SchemaItemInfo) = linkedMapOf(
        "name" to item.name, "type" to "UNIQUENESS", "entityType" to "NODE", "labelsOrTypes" to listOf(item.label), "properties" to item.properties,
    )

    private fun neo4jIndex(item: SchemaItemInfo): Map<String, Any?> {
        val config = linkedMapOf<String, Any?>()
        if (item.kind == SchemaItemKind.VECTOR_INDEX) {
            item.dimensions?.let { config["vector.dimensions"] = it.toLong() }
            item.similarity?.let { config["vector.similarity_function"] = it.name }
            item.quantizationEnabled?.let { config["vector.quantization.enabled"] = it }
            item.hnswM?.let { config["vector.hnsw.m"] = it.toLong() }
            item.hnswEfConstruction?.let { config["vector.hnsw.ef_construction"] = it.toLong() }
        }
        if (item.kind == SchemaItemKind.FULLTEXT_INDEX) item.analyzer?.let { config["fulltext.analyzer"] = it }
        return linkedMapOf(
            "name" to item.name, "type" to item.kind.name.removeSuffix("_INDEX"), "entityType" to "NODE", "labelsOrTypes" to listOf(item.label),
            "properties" to item.properties, "options" to linkedMapOf("indexConfig" to config),
        )
    }

    private fun memgraphIndex(item: SchemaItemInfo): Any = when (item.kind) {
        SchemaItemKind.VECTOR_INDEX -> linkedMapOf(
            "index_name" to item.name, "label" to item.label, "property" to item.properties.single(), "dimension" to item.dimensions?.toLong(),
            "metric" to when (item.similarity) {
                SimilarityFunction.COSINE -> "cos"
                SimilarityFunction.EUCLIDEAN -> "l2sq"
                null -> null
            },
        )
        SchemaItemKind.FULLTEXT_INDEX -> listOf("label_text (name: ${item.name})", item.label, item.properties, 0L)
        else -> listOf("label+property", item.label, item.properties.singleOrNull() ?: item.properties, 0L)
    }

    private fun falkorDbConstraint(item: SchemaItemInfo) = linkedMapOf<String, Any?>(
        "type" to "unique", "label" to item.label, "properties" to item.properties, "entitytype" to "NODE",
    ).also { row -> item.status?.let { row["status"] = it } }

    private fun falkorDbIndexes(items: List<SchemaItemInfo>): List<Any?> = items.groupBy { it.label }.map { (label, of) ->
        val types = linkedMapOf<String, MutableList<String>>()
        val options = linkedMapOf<String, Any?>()
        for (item in of) for (property in item.properties) {
            types.getOrPut(property) { mutableListOf() } += item.kind.name.removeSuffix("_INDEX")
            if (item.kind == SchemaItemKind.VECTOR_INDEX) {
                options[property] = linkedMapOf<String, Any?>("dimension" to item.dimensions?.toLong(), "similarityFunction" to item.similarity?.name?.lowercase()).also {
                    item.hnswM?.let { m -> it["M"] = m.toLong() }
                    item.hnswEfConstruction?.let { ef -> it["efConstruction"] = ef.toLong() }
                }
            }
        }
        // The label's status is its items': one being built, where any is, else the one they share.
        val status = of.firstNotNullOfOrNull { it.status?.takeIf { status -> status != "OPERATIONAL" } } ?: of.firstNotNullOfOrNull { it.status }
        linkedMapOf<String, Any?>(
            "label" to label, "properties" to types.keys.toList(), "types" to types, "options" to options, "entitytype" to "NODE",
        ).also { row -> status?.let { row["status"] = it } }
    }

    private fun sqlRecord(store: String, item: SchemaItemInfo): Map<String, Any?> {
        val details = linkedMapOf<String, Any?>()
        item.dimensions?.let { details["dimensions"] = it.toLong() }
        item.similarity?.let { details["similarity"] = it.name }
        item.analyzer?.let { details["analyzer"] = it }
        return linkedMapOf<String, Any?>("kind" to item.kind.name, "label" to item.label, "properties" to item.properties, "name" to item.name, "details" to details).also {
            if (store == "oracle" && item.status != null) it["status"] = item.status
        }
    }
}
