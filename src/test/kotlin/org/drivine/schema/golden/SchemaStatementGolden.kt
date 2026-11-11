package org.drivine.schema.golden

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.drivine.connection.ConnectionProvider
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaStatementTrace
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The schema-statement golden: the sequence of schema statements each test sends to each store, as
 * [SchemaStatementTrace] tells them, recorded with `-Pdrivine.schema.golden.record=<dir>` and checked against
 * `src/test/resources/schema-golden` with `-Pdrivine.schema.golden.check=true`. The format and the normalisations are
 * in that directory's README.md. With neither property set, no observer is set and nothing is kept.
 *
 * Everything is recorded, and the writes are checked ([writes]): every statement that changes the store, in order. The
 * reads, the `prepare` block and the polls are left out, since the planner reads less than the managers the golden was
 * recorded from did, and the golden pins every write (liberation-data/drivine-cypher-spec#135). The tests in
 * [UNCHECKED], and those of classes the golden wasn't recorded in (`classes.txt`), are not checked.
 */
internal object SchemaStatementGolden {

    const val RECORD = "drivine.schema.golden.record"
    const val CHECK = "drivine.schema.golden.check"
    const val DEFAULT_GOLDEN = "src/test/resources/schema-golden"
    const val LIVE = "build/schema-golden/live"

    /** Where the recording is written at the end of the run, or null when the golden is off. */
    val output: File? by lazy {
        System.getProperty(RECORD)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: System.getProperty(CHECK)?.takeIf { it.isNotBlank() && it != "false" }?.let { File(LIVE) }
    }

    /** The golden checked against, or null when the check is off. */
    val golden: File? by lazy {
        System.getProperty(CHECK)?.takeIf { it.isNotBlank() && it != "false" }?.let { if (it == "true") File(DEFAULT_GOLDEN) else File(it) }
    }

    val recorder = Recorder()

    private val mapper: ObjectMapper = ObjectMapper()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

    // ----- Recording -----

    private class Frame(val key: String, val thread: Thread)

    /** One statement, or one poll, as it ran, before it is normalised. */
    class Raw(
        val store: String,
        val path: String,
        val kind: String,
        val statement: String,
        val parameters: Map<String, Any?>,
        val outcome: String,
        val concurrent: Boolean,
    )

    private class Poll(val store: String, val what: String, val concurrent: Boolean)

    internal class Recorder : SchemaStatementTrace.Observer {
        private val stack = ArrayDeque<Frame>()
        private val lock = Any()

        /** What each test sent, by its key, in the order it ran. */
        val raw = ConcurrentHashMap<String, MutableList<Raw>>()

        private val order = mutableListOf<String>()

        private val polling = ThreadLocal<ArrayDeque<Poll>>()

        fun started(key: String) = synchronized(lock) {
            stack.addLast(Frame(key, Thread.currentThread()))
            if (key !in order) order += key
        }

        fun finished(key: String) = synchronized(lock) {
            if (stack.lastOrNull()?.key == key) stack.removeLast()
        }

        private fun current(): Frame? = synchronized(lock) { stack.lastOrNull() }

        /** The keys of the tests that have begun, in the order they did. */
        fun ran(): List<String> = synchronized(lock) { order.toList() }

        override fun ran(
            source: Any, path: SchemaStatementTrace.Path, kind: SchemaStatementTrace.Kind, statement: String,
            parameters: Map<String, Any?>, failure: Throwable?,
        ) {
            if (polling.get()?.isNotEmpty() == true) return // A poll is one entry, whatever it ran.
            val store = storeOf(source) ?: return
            if (Thread.currentThread().name.startsWith(BACKGROUND_THREAD)) return
            val frame = current() ?: return
            keep(
                frame.key,
                Raw(
                    store, path.name.lowercase().replace('_', '-'), kind.name.lowercase(), statement, parameters,
                    failure?.let { "failed: ${it.javaClass.name}" } ?: "ran", Thread.currentThread() !== frame.thread,
                ),
            )
        }

        override fun pollStarted(source: Any, what: String) {
            val frames = polling.get() ?: ArrayDeque<Poll>().also(polling::set)
            frames.addLast(Poll(storeOf(source) ?: "", what, current()?.thread !== Thread.currentThread()))
        }

        override fun pollEnded(source: Any, what: String, outcome: String?, failure: Throwable?) {
            val frames = polling.get() ?: return
            val poll = frames.removeLastOrNull() ?: return
            if (frames.isNotEmpty()) return
            if (poll.store.isEmpty() || Thread.currentThread().name.startsWith(BACKGROUND_THREAD)) return
            val frame = current() ?: return
            keep(frame.key, Raw(poll.store, "schema-connection", "poll", what, emptyMap(), failure?.let { "failed: ${it.javaClass.name}" } ?: outcome.orEmpty(), poll.concurrent))
        }

        private fun keep(key: String, raw: Raw) {
            this.raw.computeIfAbsent(key) { java.util.Collections.synchronizedList(mutableListOf()) } += raw
        }

        /** What [key] sent, normalised, by store. */
        fun entries(key: String): Map<String, List<String>> = normalise(key, raw[key]?.toList().orEmpty())

        /** Writes every test's entries, by store, to [dir]. */
        fun write(dir: File) {
            dir.mkdirs()
            val byStore = sortedMapOf<String, MutableList<String>>()
            ran().sorted().forEach { key ->
                entries(key).forEach { (store, lines) -> byStore.getOrPut(store) { mutableListOf() } += lines }
            }
            dir.listFiles { file -> file.name.endsWith(".jsonl") }?.forEach { it.delete() }
            byStore.forEach { (store, lines) -> File(dir, "${store.lowercase()}.jsonl").writeText(lines.joinToString("\n", postfix = "\n")) }
            texts.forEach { (store, byHash) ->
                File(dir, "${store.lowercase()}$STATEMENTS").writeText(
                    byHash.entries.joinToString("\n", postfix = "\n") { (hash, text) -> mapper.writeValueAsString(linkedMapOf("hash" to hash, "statement" to text)) }
                )
            }
        }
    }

    /** The store [source] is, or null for one that is no store: a [org.drivine.test.RecordingStore]. */
    private fun storeOf(source: Any): String? {
        if (source.javaClass.name.startsWith("org.drivine.test.RecordingStore")) return null
        return when (source) {
            is ConnectionProvider -> source.type.name
            is PersistenceManager -> source.type.name
            else -> source.javaClass.simpleName
        }
    }

    /** The threads [org.drivine.schema.CatalogReads] reads catalogs again on, when a save or a load asks: not schema management. */
    private const val BACKGROUND_THREAD = "drivine-catalog-"

    // ----- Normalising -----

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")
    private val TMP: List<String> by lazy {
        listOfNotNull(System.getProperty("java.io.tmpdir"), File(System.getProperty("java.io.tmpdir")).canonicalPath)
            .map { it.trimEnd('/') }.filter { it.length > 1 }.distinct().sortedByDescending { it.length }
    }

    /** The graphs SchemaDdlCrossEngineTest names at random, one a test: `ddl` and a random number in base 36. */
    private val RANDOM_GRAPH = Regex("\\b(ddl[0-9a-z]{2,7})(?=_|\\b)", RegexOption.IGNORE_CASE)

    /** A statement longer than this is given in an entry by its hash, and its text once in `<store>.statements.jsonl`. */
    private const val LONG_STATEMENT = 300
    const val STATEMENTS = ".statements.jsonl"

    /** The text of each long statement, by store and hash. */
    private val texts = ConcurrentHashMap<String, java.util.concurrent.ConcurrentSkipListMap<String, String>>()

    private fun hash(text: String): String =
        "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Parameters that hold the clock: the marker's moment, and the store stamp's. */
    private val CLOCK_PARAMETERS = setOf("now", "appliedAt", "applied_at", "at", "assignedAt")
    private val EPOCH_MILLIS = 1_500_000_000_000L..4_100_000_000_000L

    /** [raw], one test's, as lines of the golden by store: the test's own thread in order, then other threads' sorted. */
    fun normalise(key: String, raw: List<Raw>): Map<String, List<String>> {
        val uuids = linkedMapOf<String, String>()
        val graphs = linkedMapOf<String, String>()
        fun text(value: String): String {
            var out = UUID.replace(value) { match -> uuids.getOrPut(match.value.lowercase()) { "<uuid-${uuids.size + 1}>" } }
            out = RANDOM_GRAPH.replace(out) { match -> graphs.getOrPut(match.value.lowercase()) { "<graph-${graphs.size + 1}>" } }
            out = INSTANT.replace(out, "<instant>")
            TMP.forEach { out = out.replace(it, "<tmp>") }
            return out
        }
        fun value(name: String?, value: Any?): Any? = when {
            name != null && name in CLOCK_PARAMETERS && value != null -> "<now>"
            value is Number && value !is Double && value !is Float && value.toLong() in EPOCH_MILLIS -> "<now>"
            value is String -> text(value)
            value is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to value(k.toString(), v) }.toSortedMap()
            value is Iterable<*> -> value.map { value(null, it) }
            value is Array<*> -> value.map { value(null, it) }
            else -> value
        }
        fun entry(raw: Raw, n: Int?): Pair<String, Map<String, Any?>> = raw.store to linkedMapOf<String, Any?>(
            "test" to key,
            "n" to n,
            "path" to raw.path,
            "kind" to raw.kind,
            "statement" to text(raw.statement).let { text ->
                if (text.length <= LONG_STATEMENT) text
                else hash(text).also { texts.computeIfAbsent(raw.store) { java.util.concurrent.ConcurrentSkipListMap() }[it] = text }
            },
            "parameters" to value(null, raw.parameters),
            // Which of several threads at once is turned away, and tries again, is the scheduler's: their outcome is left out.
            "outcome" to if (n == null) null else raw.outcome,
        ).also { if (n == null) it["concurrent"] = true }

        val (own, others) = raw.partition { !it.concurrent }
        val result = linkedMapOf<String, MutableList<String>>()
        own.groupBy { it.store }.forEach { (store, list) ->
            result.getOrPut(store) { mutableListOf() } += list.mapIndexed { i, r -> mapper.writeValueAsString(entry(r, i + 1).second) }
        }
        // Statements other threads sent run at once, so their order is not the test's: they are kept once each, sorted.
        others.groupBy { it.store }.forEach { (store, list) ->
            result.getOrPut(store) { mutableListOf() } += list.map { mapper.writeValueAsString(entry(it, null).second) }.distinct().sorted()
        }
        return result
    }

    // ----- Checking: the writes -----

    /**
     * The tests in the golden's classes that are not checked, by key, and why: each was rewritten when schema management
     * became a driver over the planner, or added after the golden was recorded, so what it runs now was never recorded.
     * See the README's "Not checked".
     */
    val UNCHECKED: Map<String, String> = mapOf(
        "[class:org.drivine.schema.Neo4jEnsureAwaitsIndexesTest]/[method:a wait that fails for a reason other than the build is thrown as it is, not taken for a failed build()]" to
            "rewritten: it ensured with the internal IndexBuild of 5 seconds, which is gone; it now ensures with the manager's own wait, so db.awaitIndex is given 60, not 5",
        "[class:org.drivine.schema.Neo4jEnsureAwaitsIndexesTest]/[method:a wait that fails for a reason other than its timeout, with the index still POPULATING, is an index still being built()]" to
            "rewritten: it ensured through an IndexManager with IndexBuild(ZERO) and a handler, which are gone; it now applies an owner under WARN, which writes the owner's marker and clears it",
        "[class:org.drivine.schema.FalkorDbEnsureAwaitsIndexesTest]/[method:an index FalkorDB lists as FAILED is an error naming it, not an index taken as built()]" to
            "rewritten: its second ensure went through an IndexManager with IndexBuild and a handler, which are gone; it now applies an owner under WARN, which writes the owner's marker and clears it",
        "[class:org.drivine.store.StatelessStoreIdentityOracleTest]/[method:concurrent first boots without the constraint agree on one identity over a full node table on Oracle()]" to
            "added after the recording: it came with the merge of main (#755, drivine4j#517), after the golden was first taken, so the managers' record has nothing of it (the forms' recording has its lines, kept as recorded)",
        "[class:org.drivine.sqlite.SqliteSchemaManagementTest]/[method:an index over something else under Drivine's name is drift, not made, and a recreate replaces it()]" to
            "added after the recording: it came with the forms (drivine4j#770, drivine-cypher-spec#186), after the SQL stores' golden was recorded again, so neither record has it",
    )

    /**
     * The classes the golden was recorded in, `classes.txt` beside it: a test of another class has nothing recorded, and
     * is not checked. A golden without the file is taken to be of the classes it has lines of.
     */
    private val recordedClasses: Set<String> by lazy {
        golden?.let { File(it, CLASSES) }?.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: goldenLines.keys.map { it.substringAfter("[class:", "").substringBefore("]") }.toSet()
    }

    const val CLASSES = "classes.txt"

    /** Whether the test [key] is checked: a test of a class the golden was recorded in ([classes]), and not in [UNCHECKED]. */
    fun checks(key: String, classes: Set<String>): Boolean =
        key !in UNCHECKED && key.substringAfter("[class:", "").substringBefore("]") in classes

    private val goldenLines: Map<String, Map<String, List<String>>> by lazy {
        val dir = golden ?: return@lazy emptyMap()
        val byTest = linkedMapOf<String, MutableMap<String, MutableList<String>>>()
        dir.listFiles { file -> file.name.endsWith(".jsonl") && !file.name.endsWith(STATEMENTS) }?.sortedBy { it.name }?.forEach { file ->
            val store = file.name.removeSuffix(".jsonl").uppercase()
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val test = mapper.readTree(line)["test"].asText()
                byTest.getOrPut(test) { linkedMapOf() }.getOrPut(store) { mutableListOf() } += line
            }
        }
        byTest
    }

    /** The golden's long statements, by store and hash. */
    private val goldenTexts: Map<String, Map<String, String>> by lazy {
        val dir = golden ?: return@lazy emptyMap()
        dir.listFiles { file -> file.name.endsWith(STATEMENTS) }.orEmpty().associate { file ->
            file.name.removeSuffix(STATEMENTS).uppercase() to file.readLines().filter { it.isNotBlank() }.associate { line ->
                val node = mapper.readTree(line)
                node["hash"].asText() to node["statement"].asText()
            }
        }
    }

    private val checked = ConcurrentHashMap.newKeySet<String>()

    /**
     * Checks the writes of each test under [prefix] that is not checked yet against the golden's ([writes]): the first
     * difference, where there is one. Only a test of the classes the golden was recorded in, and not in [UNCHECKED], is
     * checked ([checks]).
     */
    fun check(prefix: String): String? {
        // Only what ran is checked: a test the golden has that did not run here (disabled, or for a store this run leaves out) is not.
        val keys = recorder.ran().filter { it == prefix || it.startsWith("$prefix/") }.filter { checked.add(it) }.filter { checks(it, recordedClasses) }
        return keys.firstNotNullOfOrNull { key ->
            val live = recorder.entries(key)
            val expected = goldenLines[key].orEmpty()
            (live.keys + expected.keys).sorted().firstNotNullOfOrNull { store ->
                difference(
                    key, store,
                    writes(store, expected[store].orEmpty(), goldenTexts[store].orEmpty()),
                    writes(store, live[store].orEmpty(), texts[store].orEmpty()),
                )
            }
        }
    }

    /**
     * The writes among [lines], one test's on [store], each without its place (`n`), which the reads left out would move;
     * [texts] gives a long statement's text by its hash. What a write is, and what is left out, is the README's
     * "What is checked": the design pins every write (liberation-data/drivine-cypher-spec#135), and leaves the planner
     * free to read less.
     *
     * - Left out: a poll; a read (a statement that begins `SHOW`, `SELECT`, or a `CALL` other than Neo4j's
     *   `db.awaitIndex`, and a `MATCH`, `OPTIONAL MATCH`, `UNWIND` or `WITH` with no clause that writes); and the
     *   `prepare` block, what the marker's statements need made first ([MarkerStatements.prepare]).
     * - Kept: everything else. That is the DDL, FalkorDB's native commands, the marker's merge and deletes, Neo4j's wait,
     *   the lowercased copies' fill and record (SQLite's fill is a `SELECT` of its `drivine_lower_copies` function, and
     *   is kept), and the SQL stores' blocks and record writes.
     */
    fun writes(store: String, lines: List<String>, texts: Map<String, String>): List<String> = lines.mapNotNull { line ->
        val entry = mapper.readTree(line) as com.fasterxml.jackson.databind.node.ObjectNode
        val statement = entry["statement"]?.asText().orEmpty()
        val text = texts[statement] ?: statement
        val write = when (entry["kind"]?.asText()) {
            "poll" -> false
            "native" -> true
            else -> isWrite(text) && !isPrepare(store, text)
        }
        if (write) mapper.writeValueAsString(entry.apply { remove("n") }) else null
    }

    private val LEADING_COMMENT = Regex("""^\s*(/\*.*?\*/\s*)+""", RegexOption.DOT_MATCHES_ALL)
    private val WRITE_CLAUSE = Regex("""\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|INSERT|UPDATE|DROP)\b""")

    /** Whether [text] changes the store: see [writes]. */
    fun isWrite(text: String): Boolean {
        val words = text.replace(LEADING_COMMENT, "").trim().uppercase()
        return when {
            words.startsWith("SHOW") -> false
            words.startsWith("CALL") -> words.startsWith("CALL DB.AWAITINDEX")
            words.startsWith("SELECT") -> "DRIVINE_LOWER_COPIES(" in words
            listOf("MATCH", "OPTIONAL", "UNWIND", "WITH").any(words::startsWith) -> WRITE_CLAUSE.containsMatchIn(words)
            else -> true
        }
    }

    private val POSTGRES_PREPARE = Regex("""^DO \${'$'}ensure\${'$'}.*?to_regclass\('(\S+?)_node'\)""", RegexOption.DOT_MATCHES_ALL)
    private val ORACLE_PREPARE = Regex("""^DECLARE\s+v_made\b.*?CREATE TABLE IF NOT EXISTS (\S+?)_node \(""", RegexOption.DOT_MATCHES_ALL)
    private val SQLITE_PREPARE = Regex("""^CREATE TABLE IF NOT EXISTS (\S+?)_node \(""")

    /** A graph's name to make the module's statements with, for one the golden numbers (`<graph-1>`). */
    private const val NUMBERED_GRAPH = "ddlzzgraph"

    /**
     * Whether [text] is the `prepare` block on [store]: the statement the module's marker needs made first for the graph
     * it names ([MarkerStatements.prepare]: the graph's `ensuring` on Postgres and Oracle, its table of nodes on SQLite),
     * word for word. The graph engines have none.
     */
    fun isPrepare(store: String, text: String): Boolean {
        val trimmed = text.trim()
        val (pattern, made) = when (store) {
            "POSTGRES" -> POSTGRES_PREPARE to { name: String -> PostgresDdl(name).ensuring }
            "ORACLE" -> ORACLE_PREPARE to { name: String -> OracleDdl(name).ensuring }
            "SQLITE" -> SQLITE_PREPARE to { name: String -> SqliteDdl(name).ddl.first() }
            else -> return false
        }
        val graph = pattern.find(trimmed)?.groupValues?.get(1) ?: return false
        val numbered = graph.startsWith("<graph-")
        val expected = runCatching { made(if (numbered) NUMBERED_GRAPH else graph) }.getOrNull() ?: return false
        val named = if (numbered) expected.replace(NUMBERED_GRAPH, graph, ignoreCase = true) else expected
        return words(named) == words(trimmed)
    }

    private fun words(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The first write where [live] differs from [expected], described, or null where they are the same. */
    fun difference(key: String, store: String, expected: List<String>, live: List<String>): String? {
        if (expected == live) return null
        val at = expected.indices.firstOrNull { it >= live.size || expected[it] != live[it] } ?: expected.size
        return "Schema writes of $key on $store differ from the golden at write ${at + 1} " +
            "(golden ${expected.size}, live ${live.size}):\n" +
            "  golden: ${expected.getOrNull(at) ?: "(none)"}\n" +
            "  live:   ${live.getOrNull(at) ?: "(none)"}\n" +
            "The live recording is written to $LIVE at the end of the run."
    }

    /** The key a test is recorded by: its unique id, without the engine's segment. */
    fun keyOf(uniqueId: String): String = uniqueId.substringAfter("]/", uniqueId)
}

/**
 * Keys what is recorded by the test that is running, and writes the recording at the end of the run. Registered with
 * the launcher (META-INF/services); it does nothing, and sets no observer, unless the golden is on.
 */
class SchemaStatementGoldenListener : TestExecutionListener {
    private val on = SchemaStatementGolden.output != null

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        if (!on) return
        require(testPlan.configurationParameters.getBoolean("junit.jupiter.execution.parallel.enabled").orElse(false) != true) {
            "The schema-statement golden keys what it records by the test that is running, so tests must run one at a time."
        }
        SchemaStatementTrace.observer = SchemaStatementGolden.recorder
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (!on || testIdentifier.parentId.isEmpty) return
        SchemaStatementGolden.recorder.started(SchemaStatementGolden.keyOf(testIdentifier.uniqueId))
    }

    override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
        if (!on || testIdentifier.parentId.isEmpty) return
        SchemaStatementGolden.recorder.finished(SchemaStatementGolden.keyOf(testIdentifier.uniqueId))
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        if (!on) return
        SchemaStatementTrace.observer = null
        SchemaStatementGolden.output?.let { SchemaStatementGolden.recorder.write(it) }
    }
}

/**
 * Checks, after each test and after each class, that the schema writes it sent are the golden's
 * ([SchemaStatementGolden.writes]), and fails it with the first difference where they are not. Found by the platform's extension autodetection, which the build
 * turns on only with `-Pdrivine.schema.golden.check`.
 */
class SchemaStatementGoldenExtension : AfterEachCallback, AfterAllCallback {
    override fun afterEach(context: ExtensionContext) = check(context)

    override fun afterAll(context: ExtensionContext) = check(context)

    private fun check(context: ExtensionContext) {
        if (SchemaStatementGolden.golden == null) return
        SchemaStatementGolden.check(SchemaStatementGolden.keyOf(context.uniqueId))?.let { throw AssertionError(it) }
    }
}
