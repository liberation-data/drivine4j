package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.drivine.backend.sqlite.SqliteDataSource
import org.drivine.backend.sqlite.SqliteFunctions
import org.drivine.connection.CypherCompile
import org.drivine.cypher.spec.CypherSpec.Companion.expanded
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.mapper.JdbcResultMapper
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `cypher/host.yaml`: what Drivine's own code does with the values compiled SQL hands it, with no store. Each
 * `function` step calls one of the functions Drivine registers on SQLite ([SqliteFunctions]), on an in-memory database Drivine
 * opens ([SqliteDataSource]), and
 * each `read` step reads a column's text as [JdbcResultMapper] reads a compiled statement's rows. Each case's `pairs` must
 * name store cases the other spec files have, whose statements rely on its steps: one of them compiles, as Drivine compiles
 * it ([CypherCompile]), on SQLite, to SQL
 * that calls each function the case's steps call (for `regexp`, which SQLite calls for its `REGEXP` operator, SQL that holds the
 * operator), and one compiles, on each store its steps read, with the `nonFinite`
 * each read step says. The three libraries read the same file.
 *
 * As the shared module's HostSpecTest runs it: a step with an `error` must fail, with a message that holds it; a step
 * whose note holds [HELD] waits for layout 2, and must fail, so the hold comes off when it passes; and
 * `drivine_host_version`'s steps, which no compiled statement calls, need no pair.
 */
class HostSpecTest {

    data class Spec(val cases: List<Case> = emptyList())

    data class Case(
        val name: String,
        val description: String? = null,
        /** The store cases, by spec file, whose compiled statements rely on [steps]. */
        val pairs: Map<String, List<String>> = emptyMap(),
        /** Each a map, as a step's `result: null` is not the same as no `result`. */
        val steps: List<Map<String, Any?>>,
    )

    private companion object {
        /** The keys of each kind of step, beside its optional `note`: a function's result, its failure, its order, and a read. */
        val SHAPES = setOf(setOf("function", "args", "result"), setOf("function", "args", "error"), setOf("function", "ascending"), setOf("read", "value"))
        val READ = setOf("store", "nonFinite", "column")
        /** Drivine's functions, and `regexp`, which SQLite calls, as `regexp(pattern, text)`, for its operator `text REGEXP pattern`. */
        val FUNCTION = Regex("drivine_[a-z_]+|regexp")
        val REGEXP_OPERATOR = Regex("""\bREGEXP\b""", RegexOption.IGNORE_CASE)
        /**
         * SQL's quoted texts and names. A quote inside one is written twice, so splits it into two that touch, each matched
         * here; matching them one by one, not as a repeated group, keeps a long one from overflowing the stack.
         */
        val QUOTED = Regex("""'[^']*'|"[^"]*"""")

        /**
         * The note of a step held until the store's timestamps keep nine digits (layout 2): it is run, and must fail, so
         * the hold comes off when it passes. Matched within the note, as the module's HostSpecTest matches it.
         */
        const val HELD = "drivine4j#421 (layout 2)"

        /** SQLite's errors of a call it won't make, which no held step's failure can be: the step names no function of the store's. */
        val RUNNER_ERRORS = listOf("no such function", "wrong number of arguments")

        /** The function a library calls itself, once on each connection, and no compiled statement does: its steps pair nothing. */
        const val UNPAIRED = "drivine_host_version"
    }

    private val spec: Spec = ObjectMapper(YAMLFactory()).registerKotlinModule()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(javaClass.getResource("/cypher/host.yaml")) { "No spec file cypher/host.yaml" })

    @TestFactory
    fun host(): List<DynamicTest> = spec.cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val held = check(case)
            if (held.isNotEmpty()) println("HostSpecTest: held for layout 2, and failing as they should: ${held.joinToString("; ")}.")
        }
    }

    @Test
    fun `a step with an error must fail with a message that holds it`() {
        val fails = mapOf("function" to "drivine_round", "args" to listOf(2.5, 0L, "UNNECESSARY"), "error" to "Rounding necessary")
        val pairs = mapOf("writes.yaml" to listOf("NaN worked out from stored infinities"))
        check(case(fails, pairs = pairs))
        val gives = mapOf("function" to "drivine_add", "args" to listOf("1", "2"), "error" to "x")
        assertContains(assertFails { check(case(gives)) }.message!!, "gave no error")
        val otherError = fails + ("error" to "no such")
        assertContains(assertFails { check(case(otherError, pairs = pairs)) }.message!!, "Rounding necessary")
        val untexted = fails + ("error" to 1L)
        assertContains(assertFails { check(case(untexted, pairs = pairs)) }.message!!, "error is a text, not 1")
    }

    @Test
    fun `a step held for layout 2 must fail, is listed, and one that passes fails the run`() {
        val heldFailing = mapOf("note" to "x $HELD", "function" to "drivine_add", "args" to listOf("1", "2"), "result" to "4")
        assertEquals(listOf("a case, step 1"), check(case(heldFailing)))
        val heldErring = mapOf("note" to HELD, "function" to "drivine_round", "args" to listOf(2.5, 0L, "UNNECESSARY"), "result" to 3.0)
        assertEquals(listOf("a case, step 1"), check(case(heldErring, pairs = mapOf("writes.yaml" to listOf("NaN worked out from stored infinities")))))
        val heldPassing = mapOf("note" to HELD, "function" to "drivine_add", "args" to listOf("1", "2"), "result" to "3")
        assertContains(assertFails { check(case(heldPassing)) }.message!!, "and passing: take the hold off")
        val heldWrongError = mapOf("note" to HELD, "function" to "drivine_add", "args" to listOf("1", "2"), "error" to "x")
        assertEquals(listOf("a case, step 1"), check(case(heldWrongError)))
        // A step the runner can't run is no hold.
        val heldUnreadable = mapOf("note" to HELD, "function" to "drivine_add", "args" to listOf(true, "2"), "result" to "3")
        assertContains(assertFails { check(case(heldUnreadable)) }.message!!, "No SQL value of")
        // Nor is a function SQLite doesn't have, or one called with arguments of another number.
        val heldMissing = mapOf("note" to HELD, "function" to "drivine_nothing", "args" to listOf("1"), "result" to "1")
        assertContains(assertFails { check(case(heldMissing)) }.message!!, "no such function")
        val heldMiscounted = mapOf("note" to HELD, "function" to "drivine_add", "args" to listOf("1"), "result" to "1")
        assertContains(assertFails { check(case(heldMiscounted)) }.message!!, "wrong number of arguments")
        // Not even where the step expects an error.
        val heldMissingErring = mapOf("note" to HELD, "function" to "drivine_nothing", "args" to listOf("1"), "error" to "x")
        assertContains(assertFails { check(case(heldMissingErring)) }.message!!, "no such function")
        val otherNote = mapOf("note" to "drivine4j#421", "function" to "drivine_add", "args" to listOf("1", "2"), "result" to "4")
        assertFails { check(case(otherNote)) }
    }

    @Test
    fun `drivine_host_version's steps alone pair nothing`() {
        val version = mapOf("function" to UNPAIRED, "args" to emptyList<Any>(), "result" to 1L)
        check(case(version, pairs = emptyMap()))
        val add = mapOf("function" to "drivine_add", "args" to listOf("1", "2"), "result" to "3")
        assertContains(assertFails { check(Case("a case", steps = listOf(version, add))) }.message!!, "pairs no store case")
        // Beside another function's steps, it still needs no pair of its own.
        check(Case("a case", pairs = mapOf("writes.yaml" to listOf("NaN and the infinities written")), steps = listOf(version, add)))
    }

    @Test
    fun `a step or a read with a key the spec does not have fails`() {
        val step = mapOf("function" to "drivine_add", "args" to listOf("1", "2"), "result" to "3")
        check(case(step))
        assertContains(assertFails { check(case(step + ("reslt" to "3"))) }.message!!, "a step has the keys")
        assertContains(assertFails { check(case(step - "result")) }.message!!, "a step has the keys")
        assertContains(assertFails { check(case(step + ("read" to mapOf<String, Any>()))) }.message!!, "a step has the keys")
        val read = mapOf("read" to mapOf("store" to "postgres", "nonFinite" to true, "column" to "1.5"), "value" to 1.5)
        val pairs = mapOf("queries.yaml" to listOf("NaN and the infinities as parameters"))
        check(case(read, pairs = pairs))
        assertContains(assertFails { check(case(read - "value", pairs = pairs)) }.message!!, "a step has the keys")
        val misspelt = read + ("read" to mapOf("store" to "postgres", "nonfinite" to true, "column" to "1.5"))
        assertContains(assertFails { check(case(misspelt, pairs = pairs)) }.message!!, "a read has the keys")
    }

    @Test
    fun `a function Drivine does not register, or a result it does not give, fails`() {
        assertFails { check(case(mapOf("function" to "drivine_nothing", "args" to listOf("1"), "result" to "1"))) }
        assertFails { check(case(mapOf("function" to "drivine_add", "args" to listOf("1", "2"), "result" to "4"))) }
        assertContains(assertFails { check(case(mapOf("function" to "drivine_nothing", "ascending" to listOf("[1]")))) }.message!!, "two values or more")
    }

    @Test
    fun `a case with no pairs, a pair no file has, or pairs that call none of its functions or read none of its reads fails`() {
        val step = mapOf("function" to "drivine_add", "args" to listOf("1", "2"), "result" to "3")
        assertContains(assertFails { check(case(step, pairs = emptyMap())) }.message!!, "pairs no store case")
        val missing = mapOf("writes.yaml" to listOf("No such case"))
        assertContains(assertFails { check(case(step, pairs = missing)) }.message!!, "writes.yaml has no case \"No such case\"")
        val unrelated = mapOf("queries.yaml" to listOf("Returning nodes and properties"))
        assertContains(assertFails { check(case(step, pairs = unrelated)) }.message!!, "compiles on SQLite to SQL that calls drivine_add")
        val read = mapOf("read" to mapOf("store" to "postgres", "nonFinite" to true, "column" to "1.5"), "value" to 1.5)
        assertContains(assertFails { check(case(read, pairs = unrelated)) }.message!!, "compiles on postgres with nonFinite true")
    }

    @Test
    fun `regexp is called with the pattern first, and is paired by SQL that holds the REGEXP operator`() {
        val regexp = mapOf("queries.yaml" to listOf("Regular expressions and more functions"))
        val steps = listOf(
            mapOf("function" to "regexp", "args" to listOf("b", "abc"), "result" to 1L),
            mapOf("function" to "regexp", "args" to listOf("abc", "b"), "result" to 0L),
            mapOf("function" to "regexp", "args" to listOf(null, "a"), "result" to null),
            mapOf("function" to "regexp", "args" to listOf("^a\\z", "a\n"), "result" to 0L),
            mapOf("function" to "regexp", "args" to listOf("^(?:(?i)keanu)\\z", "Keanu"), "result" to 1L),
        )
        check(Case("regexp", pairs = regexp, steps = steps))
        assertFails { check(case(mapOf("function" to "regexp", "args" to listOf("abc", "b"), "result" to 1L), pairs = regexp)) }
        val unrelated = mapOf("queries.yaml" to listOf("Returning nodes and properties"))
        assertContains(assertFails { check(case(steps.first(), pairs = unrelated)) }.message!!, "compiles on SQLite to SQL that calls regexp")
        assertContains(assertFails { check(case(steps.first() + ("function" to "regexpx"))) }.message!!, "no function of Drivine's")
    }

    @Test
    fun `regexp is paired by the REGEXP operator, not by a text or a name that says regexp`() {
        assertTrue(holdsRegexp("SELECT ('x' ->> '$') REGEXP '^a\\z'"))
        assertTrue(holdsRegexp("SELECT 'x' not regexp 'a'"))
        assertFalse(holdsRegexp("SELECT json_quote('a REGEXP b')"))
        assertFalse(holdsRegexp("SELECT json_object('regexp', 1) AS \"regexp\""))
        assertFalse(holdsRegexp("SELECT drivine_regexp_count('a', 'b')"))
        assertFalse(holdsRegexp("SELECT 'it''s REGEXP', \"a\"\"REGEXP\""))
        assertTrue(holdsRegexp("SELECT 'a\\' REGEXP 'b'"))
    }

    @Test
    fun `a long quoted text, or one of many doubled quotes, is passed over without overflowing the stack`() {
        assertTrue(holdsRegexp("SELECT '${"x".repeat(200_000)}' REGEXP 'a'"))
        assertFalse(holdsRegexp("SELECT '${"''".repeat(100_000)} REGEXP'"))
    }

    private fun case(step: Map<String, Any?>, pairs: Map<String, List<String>> = mapOf("writes.yaml" to listOf("NaN and the infinities written"))) =
        Case("a case", pairs = pairs, steps = listOf(step))

    /**
     * The case's steps give what they say, each step of a shape the spec's README gives, and its pairs rely on them. Gives
     * the steps held for layout 2, each of which failed as it should.
     */
    private fun check(case: Case): List<String> {
        val held = mutableListOf<String>()
        SqliteDataSource(":memory:").connection.use { connection ->
            case.steps.forEachIndexed { index, step ->
                val where = "${case.name}, step ${index + 1}: ${step.filterKeys { it != "note" }}"
                val keys = step.keys - "note"
                require(keys in SHAPES) { "$where: a step has the keys of one of $SHAPES, and an optional note" }
                if ("read" in keys) {
                    val read = step["read"] as Map<*, *>
                    require(read.keys == READ) { "$where: a read has the keys $READ" }
                    read(connection, step, where)
                } else if ((step["note"] as? String)?.contains(HELD) != true) {
                    function(connection, step, where)
                } else {
                    // Held: it must fail, by an assertion or as the function fails, but not as the runner can't run it.
                    val failed = try {
                        function(connection, step, where)
                        false
                    } catch (e: AssertionError) {
                        true
                    } catch (e: SQLException) {
                        // SQLite's own refusal to call it, of a function it hasn't or of another arity, is the runner's.
                        if (RUNNER_ERRORS.any { it in e.message.orEmpty() }) throw e
                        true
                    }
                    if (!failed) throw AssertionError("$where: held for layout 2 ($HELD), and passing: take the hold off")
                    held += "${case.name}, step ${index + 1}"
                }
            }
        }
        paired(case)
        return held
    }

    /** The step's function, called with its `args`, gives its `result`; or, called on each of its `ascending` in turn, gives texts that ascend. */
    private fun function(connection: Connection, step: Map<String, Any?>, where: String) {
        val name = step["function"] as String
        require(name.matches(FUNCTION)) { "$where: no function of Drivine's" }
        if ("ascending" in step) {
            val values = (step["ascending"] as List<*>).map(::expanded)
            require(values.size >= 2) { "$where: ascending has two values or more, each before the next" }
            values.zipWithNext().forEach { (a, b) ->
                assertEquals(1L, canonical(call(connection, "SELECT $name(?) < $name(?)", listOf(a, b))), "$where: $a before $b")
            }
            return
        }
        val args = (step["args"] as List<*>).map(::expanded)
        val sql = "SELECT $name(${args.joinToString(", ") { "?" }})"
        if ("error" !in step) return assertEquals(canonical(expanded(step["result"])), canonical(call(connection, sql, args)), where)
        val error = step["error"] as? String ?: throw IllegalArgumentException("$where: error is a text, not ${step["error"]}")
        val failure = assertFailsWith<SQLException>("$where: fails with \"$error\", but gave no error") { call(connection, sql, args) }
        // SQLite's own refusal to call it is the runner's, not the step's error: a held step mustn't count it as failing.
        if (RUNNER_ERRORS.any { it in failure.message.orEmpty() }) throw failure
        assertContains(failure.message.orEmpty(), error, message = where)
    }

    private fun call(connection: Connection, sql: String, args: List<Any?>): Any? = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, arg ->
            when (arg) {
                null -> statement.setNull(index + 1, Types.NULL)
                is String -> statement.setString(index + 1, arg)
                is Long -> statement.setLong(index + 1, arg)
                is Int -> statement.setLong(index + 1, arg.toLong())
                is Double -> statement.setDouble(index + 1, arg)
                else -> error("No SQL value of $arg")
            }
        }
        statement.executeQuery().use { results -> results.next(); results.getObject(1) }
    }

    /** The step's `read`, a column of the `store`'s rows, reads as its `value`. */
    private fun read(connection: Connection, step: Map<String, Any?>, where: String) {
        val read = step["read"] as Map<*, *>
        val nonFinite = read["nonFinite"] as Boolean
        val text = column(read["column"])
        val rows = when (val store = read["store"]) {
            "sqlite" -> connection.prepareStatement("SELECT ? AS v").use { statement ->
                statement.setString(1, text)
                statement.executeQuery().use { JdbcResultMapper.sqliteRows(it, json = true, nonFinite = nonFinite) }
            }
            "postgres" -> JdbcResultMapper.rows(postgresJsonb(text), nonFinite)
            else -> fail("$where: no reader for $store")
        }
        assertEquals(canonical(expanded(step["value"])), canonical(rows.single().values.single()), where)
    }

    /** A column's text: a text, or a list of texts (and `$repeat`s) written one after the other. */
    private fun column(value: Any?): String = when (value) {
        is String -> value
        is List<*> -> value.joinToString("") { expanded(it) as String }
        else -> error("No column text of $value")
    }

    /**
     * A row of one `jsonb` column holding [text], as Postgres's driver gives it: a type named `jsonb`, read with `getString`.
     * The reader asks it for nothing else.
     */
    private fun postgresJsonb(text: String): ResultSet {
        val loader = javaClass.classLoader
        val meta = Proxy.newProxyInstance(loader, arrayOf(ResultSetMetaData::class.java)) { _, method, _ ->
            when (method.name) {
                "getColumnCount" -> 1
                "getColumnLabel", "getColumnName" -> "v"
                "getColumnTypeName" -> "jsonb"
                "getColumnType" -> Types.OTHER
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        var row = 0
        return Proxy.newProxyInstance(loader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> meta
                "next" -> row++ == 0
                "getString" -> text
                "wasNull" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ResultSet
    }

    /**
     * The case's pairs are cases of their files, and rely on its steps: one of them compiles on SQLite to SQL that calls each
     * function its steps call (for `regexp`, SQL that holds the operator `REGEXP`), and one compiles on each store its steps read with the `nonFinite` each read step says.
     */
    private fun paired(case: Case) {
        // drivine_host_version's steps need no pair, as no compiled statement calls it: a case of them alone pairs none.
        if (case.pairs.isEmpty() && case.steps.all { it["function"] == UNPAIRED }) return
        require(case.pairs.isNotEmpty()) { "${case.name}: pairs no store case" }
        val paired = case.pairs.flatMap { (file, names) ->
            val cypher = CypherSpec.load(file)
            names.map { name -> requireNotNull(cypher.cases.find { it.name == name }) { "${case.name}: $file has no case \"$name\"" } }
        }
        fun compiled(store: String, compile: (String, Map<String, Any?>) -> CompiledCypher) = paired.flatMap { it.steps }.filter { it.runsOn(store) }
            .mapNotNull { step -> runCatching { compile(step.cypher, expanded(step.parameters)) }.getOrNull() }
        val sqlite by lazy { compiled("sqlite") { cypher, parameters -> CypherCompile.sqlite("g", cypher, parameters) } }
        case.steps.mapNotNull { it["function"] as String? }.distinct().filter { it != UNPAIRED }.forEach { function ->
            val calls: (String) -> Boolean = if (function == "regexp") ::holdsRegexp else { sql -> "$function(" in sql }
            assertTrue(sqlite.any { calls(it.sql) }, "${case.name}: no step of ${case.pairs} compiles on SQLite to SQL that calls $function")
        }
        case.steps.mapNotNull { it["read"] as Map<*, *>? }.map { it["store"] as String to it["nonFinite"] as Boolean }.distinct().forEach { (store, nonFinite) ->
            val statements = when (store) {
                "sqlite" -> sqlite
                "postgres" -> compiled("postgres") { cypher, parameters -> CypherCompile.postgres("g", cypher, parameters) }
                else -> fail("${case.name}: no compiler for $store")
            }
            assertTrue(statements.any { it.nonFinite == nonFinite }, "${case.name}: no step of ${case.pairs} compiles on $store with nonFinite $nonFinite")
        }
    }

    /** The SQL holds SQLite's operator `REGEXP`, or `NOT REGEXP`, outside its quoted texts and names. */
    private fun holdsRegexp(sql: String) = REGEXP_OPERATOR.containsMatchIn(sql.replace(QUOTED, " "))

    /** A whole number as a Long, whatever gave it. */
    private fun canonical(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Float -> value.toDouble()
        is Map<*, *> -> value.mapValues { (_, item) -> canonical(item) }
        is List<*> -> value.map(::canonical)
        else -> value
    }
}
