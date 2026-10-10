package org.drivine.mapper

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
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
 * does. JSON held in a character column is text, and stays text.
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

        /** Whole numbers are read as `Long`, as the graph drivers give them. */
        private val json = ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS)

        /** Reads every row of [results] out of the driver, so the statement can be closed. */
        fun rows(results: ResultSet): List<JdbcRow> {
            val meta = results.metaData
            val columns = 1..meta.columnCount
            val keys = columns.map { meta.getColumnLabel(it) }
            // Oracle's JSON and VECTOR are told by name: their codes are the driver's own, and have moved.
            val types = columns.map { column ->
                when (meta.getColumnTypeName(column)?.uppercase()) {
                    "JSON" -> ORACLE_JSON
                    "VECTOR" -> ORACLE_VECTOR
                    else -> meta.getColumnType(column)
                }
            }
            val rows = mutableListOf<JdbcRow>()
            while (results.next()) {
                rows += JdbcRow(keys, columns.map { column -> value(results, column, types[column - 1]) })
            }
            return rows
        }

        private fun value(results: ResultSet, column: Int, type: Int): Any? = when (type) {
            ORACLE_JSON -> results.getString(column)?.let { json.readValue(it, Any::class.java) }
            Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> results.getString(column)
            Types.NUMERIC, Types.DECIMAL -> results.getBigDecimal(column)?.let(::number)
            Types.FLOAT, Types.DOUBLE, Types.REAL, ORACLE_BINARY_FLOAT, ORACLE_BINARY_DOUBLE ->
                results.getDouble(column).takeUnless { results.wasNull() }
            Types.TIMESTAMP_WITH_TIMEZONE, ORACLE_TIMESTAMP_TZ, ORACLE_TIMESTAMP_LTZ ->
                results.getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()
            Types.TIMESTAMP -> results.getTimestamp(column)?.toLocalDateTime()
            Types.DATE -> results.getDate(column)?.toLocalDate()
            Types.BOOLEAN, Types.BIT -> results.getBoolean(column).takeUnless { results.wasNull() }
            ORACLE_VECTOR -> results.getObject(column, FloatArray::class.java)?.map { it.toDouble() }
            else -> results.getObject(column)
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
