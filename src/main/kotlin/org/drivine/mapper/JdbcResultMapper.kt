package org.drivine.mapper

import com.fasterxml.jackson.core.exc.InputCoercionException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime

/** One row of a JDBC result, read out of the driver: the column labels and a value for each. */
class JdbcRow(val keys: List<String>, val values: List<Any?>)

/**
 * Maps the rows of a JDBC result as the graph engines' mappers map their records: a row of one column
 * is that column's value, and a row of several is the list of them.
 *
 * A column of the database's JSON type is a document: it is parsed, so a query that returns one JSON
 * column gives the nested map a `@GraphView` is hydrated from, as a Cypher query that returns one map
 * does. JSON held in a character column is text, and stays text. Postgres's `json` and `jsonb` are
 * such a type, an array is a list, and a pgvector `vector` is a list of doubles, as Oracle's is.
 * SQLite has no JSON type, so a statement says its text is JSON instead: see [sqliteRows].
 */
class JdbcResultMapper(subtypeRegistry: SubtypeRegistry? = null) : GraphResultMapper(subtypeRegistry) {

    override fun keys(record: Any): List<String> = (record as JdbcRow).keys

    override fun itemAtIndex(record: Any, index: Int): Any? = (record as JdbcRow).values[index]

    override fun toNative(value: Any?): Any? = value

    companion object {
        /** Oracle's own type codes, for the types `java.sql.Types` has no code for. */
        private const val ORACLE_JSON = 2016
        private const val ORACLE_TIMESTAMP_TZ = -101
        private const val ORACLE_TIMESTAMP_LTZ = -102
        private const val ORACLE_BINARY_FLOAT = 100
        private const val ORACLE_BINARY_DOUBLE = 101
        private const val ORACLE_VECTOR = -105

        /** The driver's own JSON value, which it reads a value it writes no text of as: see [json]. */
        private const val ORACLE_JSON_VALUE = "oracle.sql.json.OracleJsonValue"

        /** Postgres's own types, told by name: the driver reports them as `OTHER`, or a time zone as none. */
        private const val POSTGRES_VECTOR = -10_105

        /** Whole numbers are read as `Long`, as the graph drivers give them. */
        private val json = ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS)

        /** Reads whole numbers past a `Long` as `BigInteger`, for [readJson] to make floats of. */
        private val wideJson = ObjectMapper()

        /** Reads floats as `BigDecimal`, for [nonFiniteRead] to tell a number that stands for NaN or an infinity from a double. */
        private val exactJson = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        /** How a store whose JSON holds no NaN writes it, where a statement is given one: see [SqliteCypherCompiler]. */
        private val NAN_TAG = mapOf("\$numberDouble" to "NaN")

        /**
         * The JSON [text] as maps, lists and values, a whole number as a `Long`. A whole number past a `Long`'s range is a
         * `Double`: no Cypher integer is that large, so it is a float whose fraction the store dropped, as Postgres's
         * `jsonb` writes `1e20` as `100000000000000000000`.
         */
        private fun readJson(text: String): Any? = try {
            json.readValue(text, Any::class.java)
        } catch (e: InputCoercionException) {
            widened(wideJson.readValue(text, Any::class.java))
        }

        /**
         * The JSON [text] as [readJson] reads it, of a statement given NaN or an infinity ([nonFinite]), with each value that stands
         * for one the double it is: see [nonFiniteRead]. Else as [readJson] reads it.
         */
        private fun readJson(text: String, nonFinite: Boolean): Any? =
            if (nonFinite) nonFiniteRead(exactJson.readValue(text, Any::class.java)) else readJson(text)

        /**
         * [value], read with its floats as `BigDecimal`, with each whole number a `Long` (past a `Long`'s range a `Double`, as
         * [widened] makes it), each float a `Double`, and each value that stands for NaN or an infinity the double it is: a number
         * past any double's, as Postgres writes one ([PostgresCypherCompiler.read]), and `{"${'$'}numberDouble": "NaN"}`, as SQLite
         * writes NaN ([SqliteCypherCompiler]).
         */
        private fun nonFiniteRead(value: Any?): Any? = when (value) {
            is Int -> value.toLong()
            is BigInteger -> value.toDouble()
            is BigDecimal -> PostgresCypherCompiler.read(value)
            NAN_TAG -> Double.NaN
            is Map<*, *> -> value.mapValuesTo(LinkedHashMap()) { (_, v) -> nonFiniteRead(v) }
            is List<*> -> value.map(::nonFiniteRead)
            else -> value
        }

        /** [value] with each whole number a `Long`, and each past a `Long`'s range a `Double`. */
        private fun widened(value: Any?): Any? = when (value) {
            is Int -> value.toLong()
            is BigInteger -> value.toDouble()
            is Map<*, *> -> value.mapValuesTo(LinkedHashMap()) { (_, v) -> widened(v) }
            is List<*> -> value.map(::widened)
            else -> value
        }

        /**
         * Reads every row of a SQLite result out of the driver. SQLite types a value and not a column,
         * so each value is read as what it is: a whole number as a `Long`, a real as a `Double`, a blob
         * as its bytes, and text as a `String`, or with [json] as the JSON it holds. Drivine's own
         * statements over a SQLite graph return documents as JSON text, and say so (see `JdbcConnection`).
         */
        fun sqliteRows(results: ResultSet, json: Boolean, nonFinite: Boolean = false): List<JdbcRow> {
            val meta = results.metaData
            val columns = 1..meta.columnCount
            val keys = columns.map { meta.getColumnLabel(it) }
            val rows = mutableListOf<JdbcRow>()
            while (results.next()) {
                rows += JdbcRow(keys, columns.map { column ->
                    when (val value = results.getObject(column)) {
                        is Int, is Short, is Byte -> (value as Number).toLong()
                        is Float -> value.toDouble()
                        is String -> if (json) readJson(value, nonFinite) else value
                        else -> value
                    }
                })
            }
            return rows
        }

        /**
         * Reads every row of [results] out of the driver, so the statement can be closed: the rows of a compiled Cypher
         * statement given NaN or an infinity with [nonFinite], whose JSON may hold values that stand for them (see [nonFiniteRead]).
         */
        fun rows(results: ResultSet, nonFinite: Boolean = false): List<JdbcRow> {
            val meta = results.metaData
            val columns = 1..meta.columnCount
            val keys = columns.map { meta.getColumnLabel(it) }
            // Oracle's JSON and VECTOR are told by name: their codes are the driver's own, and have moved.
            // Postgres's are told by name too: `jsonb` and `vector` are OTHER to its driver, and `timestamptz` a TIMESTAMP.
            val types = columns.map { column ->
                when (meta.getColumnTypeName(column)?.uppercase()) {
                    "JSON", "JSONB" -> ORACLE_JSON
                    // Postgres names a type as its catalog does, in lower case, and its driver reads pgvector's as text.
                    "VECTOR" -> if (meta.getColumnTypeName(column) == "vector") POSTGRES_VECTOR else ORACLE_VECTOR
                    "TIMESTAMPTZ" -> Types.TIMESTAMP_WITH_TIMEZONE
                    // The driver reports a DATE as a TIMESTAMP by default (`mapDateToTimestamp`); a date is read as a date.
                    "DATE" -> Types.DATE
                    else -> meta.getColumnType(column)
                }
            }
            val rows = mutableListOf<JdbcRow>()
            while (results.next()) {
                rows += JdbcRow(keys, columns.map { column -> value(results, column, types[column - 1], nonFinite) })
            }
            return rows
        }

        /**
         * The value of [column] in the row [results] is at, read as its [type] asks: as the graph
         * drivers give such a value, and never as a locator or a driver's own class that would not
         * outlive the statement.
         */
        private fun value(results: ResultSet, column: Int, type: Int, nonFinite: Boolean): Any? = when (type) {
            ORACLE_JSON -> json(results, column, nonFinite)
            Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> results.getString(column)
            Types.NUMERIC, Types.DECIMAL -> results.getBigDecimal(column)?.let(::number)
            Types.FLOAT, Types.DOUBLE, Types.REAL, ORACLE_BINARY_FLOAT, ORACLE_BINARY_DOUBLE ->
                results.getDouble(column).takeUnless { results.wasNull() }
            Types.TIMESTAMP_WITH_TIMEZONE, ORACLE_TIMESTAMP_TZ, ORACLE_TIMESTAMP_LTZ ->
                results.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()
            Types.TIMESTAMP -> results.getTimestamp(column)?.toLocalDateTime()
            Types.DATE -> results.getDate(column)?.toLocalDate()
            // Whole numbers come back as `Long`, as the graph drivers give them, whatever the column's width.
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> results.getLong(column).takeUnless { results.wasNull() }
            Types.BOOLEAN, Types.BIT -> results.getBoolean(column).takeUnless { results.wasNull() }
            // Read whole, so a locator does not outlive the statement that owns it.
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> results.getBytes(column)
            ORACLE_VECTOR -> results.getObject(column, FloatArray::class.java)?.map { it.toDouble() }
            // pgvector's text is a JSON array of numbers.
            POSTGRES_VECTOR -> results.getString(column)?.let { json.readValue(it, List::class.java).map { n -> (n as Number).toDouble() } }
            Types.ARRAY -> results.getArray(column)?.let { array -> try { (array.array as Array<*>).toList() } finally { array.free() } }
            else -> results.getObject(column)
        }

        /**
         * The JSON of [column], read as [readJson] reads it. Oracle's driver writes no text of a JSON value that holds a
         * `BINARY_DOUBLE` NaN or infinity, and fails: such a value is read through the driver's own JSON value, by [oracleJson].
         */
        private fun json(results: ResultSet, column: Int, nonFinite: Boolean): Any? = try {
            results.getString(column)?.let { readJson(it, nonFinite) }
        } catch (e: NumberFormatException) {
            val type = Class.forName(ORACLE_JSON_VALUE, true, results.javaClass.classLoader)
            oracleJson(results.getObject(column, type))
        }

        /**
         * [value], Oracle's JSON value (`oracle.sql.json.OracleJsonValue`, which Drivine does not compile against), as maps, lists and
         * values, as [readJson] reads its text: a `BINARY_DOUBLE` or `BINARY_FLOAT` as a `Double`, NaN and the infinities among them,
         * and a `NUMBER` as [number] makes it. A map is a `Map` of such values, and a list a `List`.
         */
        private fun oracleJson(value: Any?): Any? = when (value) {
            null -> null
            is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (key, item) -> key as String to oracleJson(item) }
            is List<*> -> value.map(::oracleJson)
            else -> when (oracleCall(value, "getOracleJsonType").toString()) {
                "NULL" -> null
                "TRUE" -> true
                "FALSE" -> false
                "STRING" -> oracleCall(value, "getString")
                "DOUBLE", "FLOAT" -> (oracleCall(value, "doubleValue") as Number).toDouble()
                "DECIMAL" -> number(oracleCall(value, "bigDecimalValue") as BigDecimal)
                else -> value.toString()
            }
        }

        /** The public method [name] of no arguments of one of the interfaces [target]'s class has, called on [target]. */
        private fun oracleCall(target: Any, name: String): Any? {
            fun interfaces(type: Class<*>): Sequence<Class<*>> =
                (type.interfaces.asSequence() + (type.superclass?.let(::interfaces) ?: emptySequence())).flatMap { sequenceOf(it) + interfaces(it) }
            val method = interfaces(target.javaClass).firstNotNullOf { type -> type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } }
            return method.invoke(target)
        }

        /** A number with no fraction is a `Long` where it fits; any other is a `Double`. */
        private fun number(value: BigDecimal): Number =
            if (value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0) {
                runCatching { value.longValueExact() }.getOrElse { value.toBigInteger() }
            } else {
                value.toDouble()
            }
    }
}
