package org.drivine.tck

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.OracleTestContainer
import org.drivine.test.PostgresTestContainer
import org.drivine.test.SqlCypherFixtures
import org.opencypher.tools.tck.SideEffectOps
import org.opencypher.tools.tck.api.`ControlQuery$`
import org.opencypher.tools.tck.api.CsvFile
import org.opencypher.tools.tck.api.CypherTCK
import org.opencypher.tools.tck.api.CypherValueRecords
import org.opencypher.tools.tck.api.Dummy
import org.opencypher.tools.tck.api.`ExecQuery$`
import org.opencypher.tools.tck.api.Execute
import org.opencypher.tools.tck.api.ExecutionFailed
import org.opencypher.tools.tck.api.ExpectError
import org.opencypher.tools.tck.api.ExpectResult
import org.opencypher.tools.tck.api.Graph
import org.opencypher.tools.tck.api.`InitQuery$`
import org.opencypher.tools.tck.api.Measure
import org.opencypher.tools.tck.api.Parameters
import org.opencypher.tools.tck.api.QueryType
import org.opencypher.tools.tck.api.RegisterProcedure
import org.opencypher.tools.tck.api.Scenario
import org.opencypher.tools.tck.api.SideEffects
import org.opencypher.tools.tck.values.Backward
import org.opencypher.tools.tck.values.CypherBoolean
import org.opencypher.tools.tck.values.CypherFloat
import org.opencypher.tools.tck.values.CypherInteger
import org.opencypher.tools.tck.values.CypherList
import org.opencypher.tools.tck.values.CypherNaN
import org.opencypher.tools.tck.values.CypherNode
import org.opencypher.tools.tck.values.CypherNull
import org.opencypher.tools.tck.values.CypherOrderedList
import org.opencypher.tools.tck.values.CypherPath
import org.opencypher.tools.tck.values.CypherPropertyMap
import org.opencypher.tools.tck.values.CypherRelationship
import org.opencypher.tools.tck.values.CypherString
import org.opencypher.tools.tck.values.CypherUnorderedList
import org.opencypher.tools.tck.values.CypherValue
import org.opencypher.tools.tck.values.Forward
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName
import scala.jdk.javaapi.CollectionConverters
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.SQLException
import java.time.Duration
import java.time.temporal.TemporalAccessor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

/** What a scenario came to on a store. */
enum class Outcome(val code: String, val label: String) {
    PASS("pass", "passed"),
    /** Drivine says, on purpose, that it doesn't compile the statement to SQL (yet). */
    REFUSED("a", "refused on purpose"),
    WRONG_RESULT("b", "wrong result"),
    /** An error where the TCK expects none, Drivine's own where it should run the statement, or none where one is expected. */
    WRONG_ERROR("c", "wrong or missing error"),
    /** The database failed on what Drivine sent, or the scenario gave no answer in time. */
    CRASH("d", "crash or stall"),
    /** A step the harness can't run through Drivine: a test procedure, a CSV file, a parameter it can't bind. */
    HARNESS("e", "harness limitation"),
}

@Volatile var currentQuery: String = ""

val DEBUG = System.getProperty("tck.debug") == "true"

/** [errorSource] says, for a scenario that expects an error, whether `drivine` or the `database` raised the one it got. */
class Verdict(val outcome: Outcome, val kind: String, val message: String, val errorSource: String? = null)

private class Failed(val verdict: Verdict) : RuntimeException(verdict.message, null, false, false)

/** One store the TCK runs on: a manager over a graph, emptied before each scenario. */
abstract class Store(val name: String) {
    abstract val pm: PersistenceManager
    abstract fun clear()
    /** A store whose graph a stalled statement may still be writing moves to a fresh one. */
    open fun fresh() = clear()
    /** Whether Drivine compiles Cypher to SQL for the store: a database error there is SQL's, not Cypher's. */
    open val compiles = true
    open fun close() {}

    fun query(cypher: String, parameters: Map<String, Any?>): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trim())).bind(parameters))
            .also { if (DEBUG) println("QUERY $cypher $parameters\n  -> $it") }
}

class Neo4jStore : Store("neo4j") {
    private val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
        .withAdminPassword("tckpassword").withStartupTimeout(Duration.ofMinutes(5)).also { it.start() }
    private val registry = SubtypeRegistry()
    private val provider = Neo4jConnectionProvider(
        name = "tck", type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
        user = "neo4j", password = "tckpassword", database = "neo4j", config = emptyMap(), subtypeRegistry = registry,
        cypherDialect = CypherDialect.NEO4J_5,
    )
    override val pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
    override val compiles = false
    val image: String = container.dockerImageName

    override fun clear() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    override fun close() = provider.end()
}

class SqlStore(name: String, private val type: DatabaseType, private val provider: JdbcConnectionProvider) : Store(name) {
    private var epoch = 0
    override var pm: NonTransactionalPersistenceManager = manager("tck0")

    private fun manager(graph: String): NonTransactionalPersistenceManager {
        val registry = SubtypeRegistry()
        return NonTransactionalPersistenceManager(
            JdbcConnectionProvider(graph, type, provider.nativeDriver, registry, graphName = graph), graph, type, registry,
        ).also { m ->
            when (type) {
                DatabaseType.POSTGRES -> PostgresGraph(graph).ensure(m)
                DatabaseType.ORACLE -> OracleGraph(graph).ensure(m)
                DatabaseType.SQLITE -> SqliteGraph(graph).ensure(m)
                else -> error(type)
            }
        }
    }

    override fun clear() = SqlCypherFixtures.clear(pm)

    override fun fresh() {
        epoch++
        pm = manager("tck$epoch")
        clear()
    }

    override fun close() = provider.end()
}

fun storeNamed(name: String): Store = when (name) {
    "neo4j" -> Neo4jStore()
    // Each statement stops after a minute, so one the harness gave up on does not run on.
    "postgres" -> SqlStore(
        name, DatabaseType.POSTGRES,
        JdbcConnectionProvider.pooled(
            "tck", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl() + "?options=-c%20statement_timeout%3D60000",
            PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
        ),
    )
    "oracle" -> SqlStore(
        name, DatabaseType.ORACLE,
        JdbcConnectionProvider.pooled(
            "tck", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
        ),
    )
    "sqlite" -> SqlStore(
        name, DatabaseType.SQLITE,
        JdbcConnectionProvider.sqlite("tck", File.createTempFile("tck", ".db").also { it.delete(); it.deleteOnExit() }.path),
    )
    else -> error("No store $name: neo4j, postgres, oracle or sqlite.")
}

// ---------------------------------------------------------------- values

/** What Drivine gives for a node or a relationship: its properties, so the TCK's expected values are read so too. */
fun expectedAsDrivine(value: CypherValue): CypherValue = when (value) {
    is CypherNode -> props(value.properties())
    is CypherRelationship -> props(value.properties())
    is CypherPath -> {
        val nodes = mutableListOf<CypherValue>(props(value.startingNode().properties()))
        val edges = mutableListOf<CypherValue>()
        CollectionConverters.asJava(value.connections()).forEach { c ->
            when (c) {
                is Forward -> { edges += props(c.r().properties()); nodes += props(c.n().properties()) }
                is Backward -> { edges += props(c.r().properties()); nodes += props(c.n().properties()) }
            }
        }
        pmap(mapOf("nodes" to CypherOrderedList(slist(nodes)), "edges" to CypherOrderedList(slist(edges))))
    }
    is CypherPropertyMap -> props(value)
    is CypherUnorderedList -> CypherUnorderedList(slist(CollectionConverters.asJava(value.elements()).map(::expectedAsDrivine)))
    is CypherOrderedList -> CypherOrderedList(slist(CollectionConverters.asJava(value.elements()).map(::expectedAsDrivine)))
    else -> value
}

private fun props(map: CypherPropertyMap): CypherValue =
    pmap(CollectionConverters.asJava(map.properties()).mapValues { expectedAsDrivine(it.value) })

private fun pmap(map: Map<String, CypherValue>): CypherPropertyMap =
    CypherPropertyMap(scala.collection.immutable.Map.from(CollectionConverters.asScala(map)))

private fun slist(list: List<CypherValue>): scala.collection.immutable.List<CypherValue> =
    CollectionConverters.asScala(list).toList()

/** A value Drivine gave, as the TCK writes one. */
fun actual(value: Any?): CypherValue = when (value) {
    null -> org.opencypher.tools.tck.values.`CypherNull$`.`MODULE$`
    is Boolean -> CypherBoolean(value)
    is Long -> CypherInteger(value)
    is Int -> CypherInteger(value.toLong())
    is Short -> CypherInteger(value.toLong())
    is Byte -> CypherInteger(value.toLong())
    is BigInteger -> CypherInteger(value.toLong())
    is BigDecimal -> if (value.scale() <= 0) CypherInteger(value.toLong()) else CypherFloat(value.toDouble())
    is Double -> if (value.isNaN()) org.opencypher.tools.tck.values.`CypherNaN$`.`MODULE$` else CypherFloat(value)
    is Float -> if (value.isNaN()) org.opencypher.tools.tck.values.`CypherNaN$`.`MODULE$` else CypherFloat(value.toDouble())
    is String -> CypherString(value)
    is Char -> CypherString(value.toString())
    is List<*> -> CypherOrderedList(slist(value.map(::actual)))
    is Array<*> -> CypherOrderedList(slist(value.map(::actual)))
    is Map<*, *> -> pmap(value.entries.associate { (k, v) -> k.toString() to actual(v) })
    is org.neo4j.driver.types.Path -> pmap(
        mapOf(
            "nodes" to CypherOrderedList(slist(value.nodes().map { actual(it.asMap()) })),
            "edges" to CypherOrderedList(slist(value.relationships().map { actual(it.asMap()) })),
        ),
    )
    // A temporal value is compared as its text, as the TCK writes it.
    is java.time.ZonedDateTime -> CypherString(cypherZoned(value))
    is TemporalAccessor -> CypherString(value.toString())
    is org.neo4j.driver.types.IsoDuration -> CypherString(cypherDuration(value.months(), value.days(), value.seconds(), value.nanoseconds()))
    else -> CypherString(value.toString())
}

/** A duration as Cypher writes it: years and months, days, then hours, minutes and seconds, each with its sign. */
fun cypherDuration(months: Long, days: Long, seconds: Long, nanos: Int): String {
    val sb = StringBuilder("P")
    val y = months / 12; val m = months % 12
    if (y != 0L) sb.append(y).append('Y')
    if (m != 0L) sb.append(m).append('M')
    if (days != 0L) sb.append(days).append('D')
    val total = BigInteger.valueOf(seconds).multiply(BigInteger.valueOf(1_000_000_000)).add(BigInteger.valueOf(nanos.toLong()))
    if (total.signum() != 0) {
        sb.append('T')
        val sign = if (total.signum() < 0) "-" else ""
        val abs = total.abs()
        val billion = BigInteger.valueOf(1_000_000_000)
        val wholeSeconds = abs.divide(billion).toLong()
        val frac = abs.mod(billion).toLong()
        val h = wholeSeconds / 3600; val mi = (wholeSeconds % 3600) / 60; val sec = wholeSeconds % 60
        if (h != 0L) sb.append(sign).append(h).append('H')
        if (mi != 0L) sb.append(sign).append(mi).append('M')
        if (sec != 0L || frac != 0L) {
            sb.append(sign).append(sec)
            if (frac != 0L) sb.append('.').append(frac.toString().padStart(9, '0').trimEnd('0'))
            sb.append('S')
        }
    } else if (months == 0L && days == 0L) sb.append("T0S")
    return sb.toString()
}

private val TEMPORAL_TEXT = Regex("^-?\\d{4}-\\d{2}-\\d{2}(T.*)?$|^\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?$")

/**
 * A text that is a temporal value, written as one form of it: a SQL store gives a temporal value as its text, which may
 * write a time's zero seconds, or a fraction's trailing zeros, where Cypher leaves them out. The value is the same.
 */
fun canonicalTemporal(text: String): String {
    if (!TEMPORAL_TEXT.matches(text)) return text
    val parsers = listOf<(String) -> Any>(
        { java.time.LocalDate.parse(it) }, { java.time.LocalTime.parse(it) }, { java.time.OffsetTime.parse(it) },
        { java.time.LocalDateTime.parse(it) }, { java.time.OffsetDateTime.parse(it) }, { java.time.ZonedDateTime.parse(it) },
    )
    for (parse in parsers) {
        val value = runCatching { parse(text) }.getOrNull() ?: continue
        return if (value is java.time.ZonedDateTime) cypherZoned(value) else value.toString()
    }
    return text
}

fun canonical(value: CypherValue): CypherValue = when (value) {
    is CypherString -> CypherString(canonicalTemporal(value.s()))
    is CypherPropertyMap -> pmap(CollectionConverters.asJava(value.properties()).mapValues { canonical(it.value) })
    is CypherUnorderedList -> CypherUnorderedList(slist(CollectionConverters.asJava(value.elements()).map(::canonical)))
    is CypherOrderedList -> CypherOrderedList(slist(CollectionConverters.asJava(value.elements()).map(::canonical)))
    else -> value
}

/** [value] with each text trimmed: the TCK's parser trims the spaces a table's quoted text starts or ends with. */
fun trimmed(value: CypherValue): CypherValue = when (value) {
    is CypherString -> CypherString(value.s().trim())
    is CypherPropertyMap -> pmap(CollectionConverters.asJava(value.properties()).mapValues { trimmed(it.value) })
    is CypherUnorderedList -> CypherUnorderedList(slist(CollectionConverters.asJava(value.elements()).map(::trimmed)))
    is CypherOrderedList -> CypherOrderedList(slist(CollectionConverters.asJava(value.elements()).map(::trimmed)))
    else -> value
}

fun trimmed(records: CypherValueRecords): CypherValueRecords = CypherValueRecords(
    records.header(),
    CollectionConverters.asScala(CollectionConverters.asJava(records.rows()).map { row ->
        scala.collection.immutable.Map.from(CollectionConverters.asScala(CollectionConverters.asJava(row).mapValues { trimmed(it.value) }))
    }).toList(),
)

/** Cypher writes a zoned moment with its offset, and its zone in brackets only where it is a named zone. */
private fun cypherZoned(value: java.time.ZonedDateTime): String =
    if (value.zone is java.time.ZoneOffset) value.toOffsetDateTime().toString() else value.toString()

fun parameter(value: CypherValue): Any? = when (value) {
    is org.opencypher.tools.tck.values.`CypherNull$` -> null
    is CypherBoolean -> value.value()
    is CypherInteger -> value.value()
    is CypherFloat -> value.value()
    is CypherString -> value.s()
    is CypherList -> CollectionConverters.asJava(value.elements()).map(::parameter)
    is CypherPropertyMap -> CollectionConverters.asJava(value.properties()).mapValues { parameter(it.value) }
    else -> throw Failed(Verdict(Outcome.HARNESS, "parameter", "A parameter of a type the harness binds no value of: $value"))
}

/** The rows Drivine gave, under the expected header: a row of one column is its value, of several the list of them. */
fun records(header: List<String>, rows: List<Any?>): CypherValueRecords {
    val scalaRows = rows.map { row ->
        val values: List<Any?> = if (header.size <= 1) listOf(row) else (row as? List<*>) ?: listOf(row)
        val cells = if (values.size == header.size) header.zip(values.map { canonical(actual(it)) }).toMap()
        else mapOf("<columns>" to CypherString("${values.size} columns: $values"))
        scala.collection.immutable.Map.from(CollectionConverters.asScala(cells))
    }
    return CypherValueRecords(CollectionConverters.asScala(header).toList(), CollectionConverters.asScala(scalaRows).toList())
}

fun expected(records: CypherValueRecords): CypherValueRecords = CypherValueRecords(
    records.header(),
    CollectionConverters.asScala(CollectionConverters.asJava(records.rows()).map { row ->
        scala.collection.immutable.Map.from(CollectionConverters.asScala(CollectionConverters.asJava(row).mapValues { canonical(expectedAsDrivine(it.value)) }))
    }).toList(),
)

// ---------------------------------------------------------------- errors

fun chain(e: Throwable): List<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.toList()

/** Whether [e] is Drivine refusing, on purpose, what it does not compile. */
fun isRefusal(e: Throwable): Boolean = chain(e).any { t ->
    t is DrivineException && t.message.orEmpty().let { m ->
        m.contains("is not compiled to SQL yet") || m.contains("does not run on Oracle") || m.contains("do not run on Oracle") ||
            m.contains("A path is not a value drivine gives the same on each store") || m.contains("does not run on") && m.contains("yet")
    }
}

/** Whether [e] came from the database, not from Drivine. */
fun isDatabaseError(e: Throwable): Boolean = chain(e).any { it is SQLException || it is org.neo4j.driver.exceptions.Neo4jException }

fun describe(e: Throwable): String = chain(e).distinctBy { it.message }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.lineSequence()?.firstOrNull()?.take(300)}" }

// ---------------------------------------------------------------- scenarios

/** The side-effect state, read with the TCK's queries: `id()` is written `elementId()`, which every store has. */
fun measure(store: Store): SideEffectOps.State {
    fun rows(cypher: String): List<Any?> = store.query(cypher, emptyMap())
    val nodes = rows("MATCH (n) RETURN elementId(n)").map(::actual)
    val rels = rows("MATCH ()-[r]->() RETURN elementId(r)").map(::actual)
    val labels = rows("MATCH (n) UNWIND labels(n) AS label RETURN DISTINCT label").map(::actual)
    // Each property is read from properties(): a key computed at run time, n[key], is itself a feature under test.
    fun triples(cypher: String) = rows(cypher).flatMap { row ->
        val (id, properties) = row as List<*>
        (properties as Map<*, *>).map { (k, v) ->
            scala.Tuple3<CypherValue, CypherValue, CypherValue>(actual(id), actual(k), canonical(actual(v)))
        }
    }
    // One column, a list, as the compiler warns on each statement that returns several.
    val props = triples("MATCH (n) RETURN [elementId(n), properties(n)] AS row") +
        triples("MATCH ()-[r]->() RETURN [elementId(r), properties(r)] AS row")
    return SideEffectOps.State(
        scala.collection.immutable.Set.from(CollectionConverters.asScala(nodes)),
        scala.collection.immutable.Set.from(CollectionConverters.asScala(rels)),
        scala.collection.immutable.Set.from(CollectionConverters.asScala(labels)),
        CollectionConverters.asScala(props).toList(),
    )
}

private val ANY_GRAPH = Regex("^any graph$")

/** Runs [scenario] on [store], from an empty graph, and says what it came to. */
fun run(scenario: Scenario, store: Store): Verdict {
    currentQuery = ""
    store.clear()
    val parameters = mutableMapOf<String, Any?>()
    var last: Result<List<Any?>> = Result.success(emptyList())
    var before: SideEffectOps.State? = null
    var measureFailed: String? = null
    val notes = mutableListOf<String>()
    var errorSource: String? = null
    for (step in CollectionConverters.asJava(scenario.steps())) {
        when (step) {
            is Dummy -> {}
            is Parameters -> CollectionConverters.asJava(step.values()).forEach { (k, v) -> parameters[k] = parameter(v) }
            is RegisterProcedure -> return Verdict(Outcome.HARNESS, "procedure", "The scenario registers a test procedure, which Drivine has no way to: ${step.signature()}")
            is CsvFile -> return Verdict(Outcome.HARNESS, "csv", "The scenario reads a CSV file (LOAD CSV)")
            is Execute -> {
                // "Given any graph" asks for no graph in particular: the empty one serves.
                if (step.qt() === `InitQuery$`.`MODULE$` && ANY_GRAPH.matches(step.source().text.removePrefix("Given ").trim())) continue
                if (step.qt() !== `InitQuery$`.`MODULE$`) currentQuery = step.query() + (if (parameters.isEmpty()) "" else "  PARAMS $parameters")
                val outcome = runCatching { store.query(step.query(), parameters) }
                when (step.qt()) {
                    `InitQuery$`.`MODULE$` -> outcome.exceptionOrNull()?.let { e -> return errorVerdict(e, "setup") }
                    `ExecQuery$`.`MODULE$`, `ControlQuery$`.`MODULE$` -> last = outcome
                    else -> {}
                }
            }
            is Measure -> try {
                before = measure(store)
            } catch (e: Exception) {
                measureFailed = describe(e)
            }
            is ExpectResult -> {
                val e = last.exceptionOrNull()
                if (e != null) return errorVerdict(e, "query")
                val want = expected(step.expectedResult())
                val got = records(CollectionConverters.asJava(want.header()), last.getOrThrow())
                val same = if (step.sorted()) want == got else want.equalsUnordered(got)
                if (!same) {
                    val tw = trimmed(want); val tg = trimmed(got)
                    if (if (step.sorted()) tw == tg else tw.equalsUnordered(tg)) {
                        return Verdict(Outcome.HARNESS, "whitespace", "equal but for the spaces the TCK parser trims from expected texts: ${got.rows()}".take(1000))
                    }
                }
                if (!same) return Verdict(Outcome.WRONG_RESULT, "rows", "expected${if (step.sorted()) " (in order)" else ""} ${want.rows()} got ${got.rows()}".take(2000))
            }
            is ExpectError -> {
                val e = last.exceptionOrNull()
                if (e == null) return Verdict(Outcome.WRONG_ERROR, "no-error", "expected ${step.errorType()} at ${step.phase()} (${step.detail()}), got ${last.getOrNull()?.take(10)}")
                if (isRefusal(e)) return Verdict(Outcome.REFUSED, "refused-expected-error", describe(e))
                // The lenient score passes any error; the strict one keeps apart where it came from.
                if (errorSource != "database") errorSource = if (isDatabaseError(e)) "database" else "drivine"
                notes += "error ${step.errorType()}/${step.detail()} as ${describe(e).take(200)}"
            }
            is SideEffects -> {
                if (last.isFailure) continue
                val start = before
                if (start == null) {
                    notes += "side effects unchecked: $measureFailed"
                    continue
                }
                val after = try {
                    measure(store)
                } catch (e: Exception) {
                    notes += "side effects unchecked: ${describe(e)}"
                    continue
                }
                val diff = start.diff(after).fillInZeros()
                val want = step.expected().fillInZeros()
                if (diff != want) return Verdict(Outcome.WRONG_RESULT, "side-effects", "expected side effects ${want.v()} got ${diff.v()}")
            }
            else -> return Verdict(Outcome.HARNESS, "step", "A step the harness does not run: $step")
        }
    }
    return Verdict(Outcome.PASS, if (notes.isEmpty()) "" else "pass-notes", notes.joinToString("; "), errorSource)
}

fun errorVerdict(e: Throwable, where: String): Verdict = when {
    e is Failed -> e.verdict
    isRefusal(e) -> Verdict(Outcome.REFUSED, "refused-$where", describe(e))
    isDatabaseError(e) -> Verdict(Outcome.CRASH, "db-error-$where", describe(e))
    // Drivine's own compile-time error, for a statement the TCK runs: a semantic or syntax error it should not raise.
    chain(e).any { it is DrivineException } -> Verdict(Outcome.WRONG_ERROR, "drivine-error-$where", describe(e))
    else -> Verdict(Outcome.CRASH, "exception-$where", describe(e))
}

fun opt(o: scala.Option<*>): Any? = if (o.isDefined) o.get() else null

private fun path(scenario: Scenario): String = scenario.sourceFile().toString().substringAfter("features/")

private fun key(scenario: Scenario): String = "${path(scenario)}#${scenarioKey(opt(scenario.number()) as Int, opt(scenario.exampleIndex()) as Int?)}"

/**
 * Runs the TCK on one store, writes `<store>.jsonl` and the report, and with `tck.gate` holds the run to the store's
 * baseline, or with `tck.baseline.update` rewrites the baseline. Exits 1 when the gate fails.
 */
fun main() {
    val storeName = System.getProperty("tck.store") ?: "sqlite"
    val filter = System.getProperty("tck.features")?.takeIf { it.isNotBlank() }?.split(',')
    val timeout = (System.getProperty("tck.timeout") ?: "30").toLong()
    val limit = System.getProperty("tck.limit")?.toInt()
    val gate = System.getProperty("tck.gate") == "true"
    val update = System.getProperty("tck.baseline.update") == "true"
    val tckVersion = System.getProperty("tck.version") ?: "unknown"
    val baselineFile = File(System.getProperty("tck.baseline.dir") ?: "src/tck/resources", "tck-baseline-$storeName.json")
    val out = File(System.getProperty("tck.out") ?: "build/tck/$storeName.jsonl").absoluteFile.also { it.parentFile.mkdirs() }

    // A filtered run would write a baseline that forgets every scenario outside the filter.
    if (update && (filter != null || limit != null)) {
        System.err.println("tck.baseline.update rewrites the whole baseline, so it runs the whole TCK: leave out tck.features and tck.limit.")
        exitProcess(2)
    }
    val baseline = if (gate) {
        Baseline.read(baselineFile) ?: run {
            System.err.println("No baseline for $storeName at $baselineFile: write one with -Ptck.baseline.update=true.")
            exitProcess(2)
        }
    } else null
    baseline?.refusal(tckVersion)?.let {
        System.err.println(it)
        exitProcess(2)
    }

    val all = CollectionConverters.asJava(CypherTCK.allTckScenarios()).sortedWith(
        compareBy({ it.sourceFile().toString() }, { opt(it.number()) as Int? ?: 0 }, { opt(it.exampleIndex()) as Int? ?: 0 }),
    )
    val tckKeys = all.map(::key).toSet()
    val scenarios = all.filter { s -> filter == null || filter.any { f -> s.sourceFile().toString().contains(f) } }
        .let { if (limit != null) it.take(limit) else it }
    println("TCK: ${all.size} scenarios, running ${scenarios.size} on $storeName")

    val started = System.nanoTime()
    val store = storeNamed(storeName)
    if (store is Neo4jStore) println("Neo4j image: ${store.image}")
    println("Store ready in ${(System.nanoTime() - started) / 1_000_000} ms")
    val json = ObjectMapper()
    var executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tck").apply { isDaemon = true } }
    val counts = sortedMapOf<String, Int>()
    val rows = mutableListOf<Row>()
    out.bufferedWriter().use { writer ->
        scenarios.forEachIndexed { index, scenario ->
            val t0 = System.nanoTime()
            val future = executor.submit<Verdict> {
                try {
                    run(scenario, store)
                } catch (e: Failed) {
                    e.verdict
                } catch (e: Throwable) {
                    errorVerdict(e, "harness")
                }
            }
            val verdict = try {
                future.get(timeout, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                executor.shutdownNow()
                executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tck").apply { isDaemon = true } }
                runCatching { store.fresh() }
                Verdict(Outcome.CRASH, "timeout", "No answer in $timeout s")
            }
            counts.merge(verdict.outcome.code, 1, Int::plus)
            val row = Row(
                store = storeName, file = path(scenario), feature = scenario.featureName(), number = opt(scenario.number()) as Int,
                name = scenario.name(), example = opt(scenario.exampleIndex()) as Int?, outcome = verdict.outcome,
                kind = verdict.kind, message = verdict.message.take(3000), query = currentQuery,
                ms = (System.nanoTime() - t0) / 1_000_000, errorSource = verdict.errorSource,
                strict = verdict.outcome == Outcome.PASS && !(store.compiles && verdict.errorSource == "database"),
            )
            rows += row
            writer.write(json.writeValueAsString(row.toMap()))
            writer.newLine()
            if (index % 100 == 0) {
                writer.flush()
                println("[${index + 1}/${scenarios.size}] $counts  ${(System.nanoTime() - started) / 1_000_000_000}s")
            }
        }
    }
    val seconds = (System.nanoTime() - started) / 1_000_000_000
    executor.shutdownNow()
    runCatching { store.close() }

    val totals = Report.totals(rows)
    println("Done on $storeName in ${seconds}s: lenient ${Report.rate(totals, "pass")}, strict ${Report.rate(totals, "strict")}, $counts -> ${out.path}")
    Report.write(out.parentFile)
    println("Report: ${File(out.parentFile, "report.md").path}")

    var status = 0
    if (baseline != null) {
        val comparison = Comparison.of(baseline, rows, tckKeys)
        print(comparison.describe())
        println(
            "Gate on $storeName: ${comparison.regressions.size} regressed, ${comparison.missing.size} missing, " +
                "${comparison.gains.size} newly passing (baseline ${baseline.passing}).",
        )
        if (comparison.gains.isNotEmpty()) println("Newly passing scenarios move the baseline up with -Ptck.baseline.update=true.")
        if (comparison.failed) status = 1
    }
    if (update) {
        if (status != 0) {
            println("The gate failed, so the baseline is left as it was.")
        } else {
            Baseline.read(baselineFile)?.let { previous ->
                val lost = Comparison.of(previous, rows, tckKeys)
                if (lost.failed) {
                    println("The new baseline drops ${lost.regressions.size + lost.missing.size} scenarios that passed in the old one:")
                    print(lost.describe())
                }
            }
            val next = Baseline.of(storeName, tckVersion, rows)
            next.write(baselineFile)
            println("Baseline for $storeName: ${next.passing} of ${next.scenarios} passing -> ${baselineFile.path}")
        }
    }
    exitProcess(status)
}
