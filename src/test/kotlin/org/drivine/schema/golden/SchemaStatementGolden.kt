package org.drivine.schema.golden

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.drivine.connection.ConnectionProvider
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

    // ----- Checking -----

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

    private val checked = ConcurrentHashMap.newKeySet<String>()

    /** Checks what each test under [prefix] that is not checked yet sent against the golden: the first difference, where there is one. */
    fun check(prefix: String): String? {
        // Only what ran is checked: a test the golden has that did not run here (disabled, or for a store this run leaves out) is not.
        val keys = recorder.ran().filter { it == prefix || it.startsWith("$prefix/") }.filter { checked.add(it) }
        return keys.firstNotNullOfOrNull { key ->
            val live = recorder.entries(key)
            val expected = goldenLines[key].orEmpty()
            (live.keys + expected.keys).sorted().firstNotNullOfOrNull { store -> difference(key, store, expected[store].orEmpty(), live[store].orEmpty()) }
        }
    }

    /** The first line where [live] differs from [expected], described, or null where they are the same. */
    fun difference(key: String, store: String, expected: List<String>, live: List<String>): String? {
        if (expected == live) return null
        val at = expected.indices.firstOrNull { it >= live.size || expected[it] != live[it] } ?: expected.size
        return "Schema statements of $key on $store differ from the golden at entry ${at + 1} " +
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
 * Checks, after each test and after each class, that the schema statements it sent are the golden's, and fails it
 * with the first difference where they are not. Found by the platform's extension autodetection, which the build
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
