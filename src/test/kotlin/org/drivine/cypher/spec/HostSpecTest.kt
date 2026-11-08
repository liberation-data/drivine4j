package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.drivine.backend.sqlite.SqliteFunctions
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.mapper.JdbcResultMapper
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `cypher/host.yaml`: what Drivine's own code does with the values compiled SQL hands it, with no store. Each
 * `function` step calls one of the functions Drivine registers on SQLite ([SqliteFunctions]), on an in-memory database, and
 * each `read` step reads a column's text as [JdbcResultMapper] reads a compiled statement's rows. Each case's `pairs` must
 * name store cases the other spec files have, whose statements rely on its steps: one of them compiles, on SQLite, to SQL
 * that calls each function the case's steps call, and one compiles, on each store its steps read, with the `nonFinite`
 * each read step says. The three libraries read the same file.
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

    private val spec: Spec = ObjectMapper(YAMLFactory()).registerKotlinModule()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(javaClass.getResource("/cypher/host.yaml")) { "No spec file cypher/host.yaml" })

    @TestFactory
    fun host(): List<DynamicTest> = spec.cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
                SqliteFunctions.register(connection)
                case.steps.forEachIndexed { index, step ->
                    val where = "${case.name}, step ${index + 1}: ${step.filterKeys { it != "note" }}"
                    when {
                        step["function"] != null -> function(connection, step, where)
                        step["read"] != null -> read(connection, step, where)
                        else -> fail("$where: a step has a function or a read")
                    }
                }
            }
            paired(case)
        }
    }

    /** The step's function, called with its `args`, gives its `result`; or, called on each of its `ascending` in turn, gives texts that ascend. */
    private fun function(connection: Connection, step: Map<String, Any?>, where: String) {
        val name = step["function"] as String
        require(name.matches(Regex("drivine_[a-z_]+"))) { "$where: no function of Drivine's" }
        if (step.containsKey("ascending")) {
            val values = (step["ascending"] as List<*>).map(::expanded)
            values.zipWithNext().forEach { (a, b) ->
                assertEquals(1L, canonical(call(connection, "SELECT $name(?) < $name(?)", listOf(a, b))), "$where: $a before $b")
            }
            return
        }
        require(step.containsKey("result")) { "$where: a function step has a result or ascending" }
        val args = (step["args"] as List<*>).map(::expanded)
        val sql = "SELECT $name(${args.joinToString(", ") { "?" }})"
        assertEquals(canonical(expanded(step["result"])), canonical(call(connection, sql, args)), where)
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
     * function its steps call, and one compiles on each store its steps read with the `nonFinite` each read step says.
     */
    private fun paired(case: Case) {
        require(case.pairs.isNotEmpty()) { "${case.name}: pairs no store case" }
        val paired = case.pairs.flatMap { (file, names) ->
            val cypher = CypherSpec.load(file)
            names.map { name -> requireNotNull(cypher.cases.find { it.name == name }) { "${case.name}: $file has no case \"$name\"" } }
        }
        fun compiled(store: String, compiler: () -> SqlCypherCompiler) = paired.flatMap { it.steps }.filter { it.runsOn(store) }
            .mapNotNull { step -> runCatching { compiler().compile(step.cypher, expanded(step.parameters)) }.getOrNull() }
        val sqlite by lazy { compiled("sqlite") { SqliteCypherCompiler("g") } }
        case.steps.mapNotNull { it["function"] as String? }.distinct().forEach { function ->
            assertTrue(sqlite.any { function in it.sql }, "${case.name}: no step of ${case.pairs} compiles on SQLite to SQL that calls $function")
        }
        case.steps.mapNotNull { it["read"] as Map<*, *>? }.map { it["store"] as String to it["nonFinite"] as Boolean }.distinct().forEach { (store, nonFinite) ->
            val statements = when (store) {
                "sqlite" -> sqlite
                "postgres" -> compiled("postgres") { PostgresCypherCompiler("g") }
                else -> fail("${case.name}: no compiler for $store")
            }
            assertTrue(statements.any { it.nonFinite == nonFinite }, "${case.name}: no step of ${case.pairs} compiles on $store with nonFinite $nonFinite")
        }
    }

    /** [value] with each `{$repeat: text, times: n}` written out, and each `{$float: …}` the double it names; any other map the map it is. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> expanded(value: T): T = when (value) {
        is Map<*, *> -> when (value.keys) {
            setOf("\$repeat", "times") -> (value["\$repeat"] as String).repeat((value["times"] as Number).toInt())
            setOf("\$float") -> (value["\$float"] as String).toDouble()
            else -> value.mapValues { (_, item) -> expanded(item) }
        } as T
        is List<*> -> value.map { expanded(it) } as T
        else -> value
    }

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
