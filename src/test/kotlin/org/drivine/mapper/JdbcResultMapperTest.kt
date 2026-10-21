package org.drivine.mapper

import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class JdbcResultMapperTest {

    /** One column, named [label], of the type [typeName] with the code [type]. */
    private class Column(val label: String, val typeName: String, val type: Int)

    /** A result of one row over [columns], each column's value stubbed by [stub]. */
    private fun resultSet(columns: List<Column>, stub: (ResultSet) -> Unit): ResultSet {
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        `when`(meta.columnCount).thenReturn(columns.size)
        columns.forEachIndexed { i, column ->
            `when`(meta.getColumnLabel(i + 1)).thenReturn(column.label)
            `when`(meta.getColumnTypeName(i + 1)).thenReturn(column.typeName)
            `when`(meta.getColumnType(i + 1)).thenReturn(column.type)
        }
        val results = Mockito.mock(ResultSet::class.java)
        `when`(results.metaData).thenReturn(meta)
        `when`(results.next()).thenReturn(true, false)
        stub(results)
        return results
    }

    private fun single(column: Column, stub: (ResultSet) -> Unit): Any? {
        val rows = JdbcResultMapper.rows(resultSet(listOf(column), stub))
        assertEquals(1, rows.size)
        assertEquals(listOf(column.label), rows.single().keys)
        return rows.single().values.single()
    }

    @Test
    fun `a JSON column is parsed into maps and lists, with whole numbers as Long`() {
        val value = single(Column("DOC", "JSON", 2016)) {
            `when`(it.getString(1)).thenReturn("""{"name":"Ada","age":36,"score":0.5,"tags":["a",1,2.5],"nested":{"n":null}}""")
        }
        assertEquals(
            mapOf("name" to "Ada", "age" to 36L, "score" to 0.5, "tags" to listOf("a", 1L, 2.5), "nested" to mapOf("n" to null)),
            value,
        )
        assertEquals(listOf(1L, 2L), single(Column("DOC", "JSON", 2016)) { `when`(it.getString(1)).thenReturn("[1, 2]") })
        assertNull(single(Column("DOC", "JSON", 2016)) { `when`(it.getString(1)).thenReturn(null) })
    }

    @Test
    fun `a VECTOR column is read as the driver's float array, and given back as a list of doubles`() {
        val value = single(Column("EMBEDDING", "VECTOR", -105)) {
            `when`(it.getObject(1, FloatArray::class.java)).thenReturn(floatArrayOf(1f, 0.5f))
        }
        assertEquals(listOf(1.0, 0.5), value)
    }

    @Test
    fun `a DATE column is read as a date even when the driver reports it as a timestamp`() {
        val value = single(Column("BORN", "DATE", Types.TIMESTAMP)) {
            `when`(it.getDate(1)).thenReturn(java.sql.Date.valueOf("2024-03-01"))
        }
        assertEquals(LocalDate.of(2024, 3, 1), value)
    }

    @Test
    fun `a NUMBER with no fraction is a Long where it fits, otherwise a BigInteger, and one with a fraction a Double`() {
        fun numeric(text: String) = single(Column("N", "NUMBER", Types.NUMERIC)) { `when`(it.getBigDecimal(1)).thenReturn(BigDecimal(text)) }
        assertEquals(5L, numeric("5.00"))
        assertEquals(0.5, numeric("0.5"))
        assertEquals(1000L, numeric("1E+3"))
        assertEquals(BigInteger("92233720368547758070"), numeric("92233720368547758070"))
        assertEquals(-7L, numeric("-7"))
        assertNull(single(Column("N", "NUMBER", Types.DECIMAL)) { `when`(it.getBigDecimal(1)).thenReturn(null) })
    }

    @Test
    fun `a DOUBLE that was null is null, not zero`() {
        val value = single(Column("D", "BINARY_DOUBLE", Types.DOUBLE)) {
            `when`(it.getDouble(1)).thenReturn(0.0)
            `when`(it.wasNull()).thenReturn(true)
        }
        assertNull(value)
        assertEquals(2.5, single(Column("D", "BINARY_DOUBLE", Types.DOUBLE)) { `when`(it.getDouble(1)).thenReturn(2.5) })
    }

    @Test
    fun `a BOOLEAN is a boolean, and a CLOB its whole text`() {
        assertEquals(true, single(Column("B", "BOOLEAN", Types.BOOLEAN)) { `when`(it.getBoolean(1)).thenReturn(true) })
        assertEquals("long text", single(Column("C", "CLOB", Types.CLOB)) { `when`(it.getString(1)).thenReturn("long text") })
    }

    @Test
    fun `a timestamp with a zone is a zoned date time, under the standard code and under each of Oracle's own`() {
        val moment = OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2))
        for (column in listOf(Column("AT", "TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE), Column("AT", "TIMESTAMP WITH TIME ZONE", -101), Column("AT", "TIMESTAMP WITH LOCAL TIME ZONE", -102))) {
            val value = single(column) { `when`(it.getObject(1, OffsetDateTime::class.java)).thenReturn(moment) }
            assertEquals(moment.toZonedDateTime(), value, column.typeName)
        }
        assertNull(single(Column("AT", "TIMESTAMP WITH TIME ZONE", -101)) { `when`(it.getObject(1, OffsetDateTime::class.java)).thenReturn(null) })
    }

    @Test
    fun `a timestamp with no zone is a local date time, and one that is null is null`() {
        val value = single(Column("AT", "TIMESTAMP", Types.TIMESTAMP)) {
            `when`(it.getTimestamp(1)).thenReturn(Timestamp.valueOf("2024-03-01 12:30:15.5"))
        }
        assertEquals(LocalDateTime.of(2024, 3, 1, 12, 30, 15, 500_000_000), value)
        assertNull(single(Column("AT", "TIMESTAMP", Types.TIMESTAMP)) { `when`(it.getTimestamp(1)).thenReturn(null) })
        assertNull(single(Column("BORN", "DATE", Types.TIMESTAMP)) { `when`(it.getDate(1)).thenReturn(null) })
    }

    @Test
    fun `a JSON or a VECTOR column is told by its type's name in any case, whatever code the driver gives it`() {
        // The codes are the driver's own, and have moved: a JSON column reported as OTHER is still a document.
        assertEquals(mapOf("a" to 1L), single(Column("DOC", "json", Types.OTHER)) { `when`(it.getString(1)).thenReturn("""{"a":1}""") })
        assertEquals(listOf(0.25), single(Column("E", "Vector", Types.OTHER)) { `when`(it.getObject(1, FloatArray::class.java)).thenReturn(floatArrayOf(0.25f)) })
        assertNull(single(Column("E", "VECTOR", -105)) { `when`(it.getObject(1, FloatArray::class.java)).thenReturn(null) })
    }

    @Test
    fun `Postgres's jsonb, timestamptz, pgvector and arrays are told by their names`() {
        assertEquals(mapOf("a" to 1L), single(Column("DOC", "jsonb", Types.OTHER)) { `when`(it.getString(1)).thenReturn("""{"a":1}""") })
        val moment = OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(moment.toZonedDateTime(), single(Column("AT", "timestamptz", Types.TIMESTAMP)) { `when`(it.getObject(1, OffsetDateTime::class.java)).thenReturn(moment) })
        assertEquals(listOf(0.25, 1.0, -2.0), single(Column("E", "vector", Types.OTHER)) { `when`(it.getString(1)).thenReturn("[0.25,1,-2]") })
        val labels = Mockito.mock(java.sql.Array::class.java)
        `when`(labels.array).thenReturn(arrayOf("Person", "Author"))
        assertEquals(listOf("Person", "Author"), single(Column("LABELS", "_text", Types.ARRAY)) { `when`(it.getArray(1)).thenReturn(labels) })
        assertEquals(36L, single(Column("AGE", "int4", Types.INTEGER)) { `when`(it.getLong(1)).thenReturn(36L) })
    }

    @Test
    fun `a column whose type has no name is read by its code`() {
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        `when`(meta.columnCount).thenReturn(1)
        `when`(meta.getColumnLabel(1)).thenReturn("N")
        `when`(meta.getColumnTypeName(1)).thenReturn(null)
        `when`(meta.getColumnType(1)).thenReturn(Types.NUMERIC)
        val results = Mockito.mock(ResultSet::class.java)
        `when`(results.metaData).thenReturn(meta)
        `when`(results.next()).thenReturn(true, false)
        `when`(results.getBigDecimal(1)).thenReturn(BigDecimal("7"))

        assertEquals(listOf<Any?>(7L), JdbcResultMapper.rows(results).single().values)
    }

    @Test
    fun `Oracle's binary float and double are doubles, and a boolean that was null is null, not false`() {
        assertEquals(1.5, single(Column("F", "BINARY_FLOAT", 100)) { `when`(it.getDouble(1)).thenReturn(1.5) })
        assertEquals(2.5, single(Column("D", "BINARY_DOUBLE", 101)) { `when`(it.getDouble(1)).thenReturn(2.5) })
        val missing = single(Column("B", "BOOLEAN", Types.BOOLEAN)) {
            `when`(it.getBoolean(1)).thenReturn(false)
            `when`(it.wasNull()).thenReturn(true)
        }
        assertNull(missing)
        assertEquals(false, single(Column("B", "BOOLEAN", Types.BIT)) { `when`(it.getBoolean(1)).thenReturn(false) })
    }

    @Test
    fun `a BLOB is read whole as its bytes, and text of any other kind as the driver gives it`() {
        val bytes = byteArrayOf(1, 2, 3)
        assertSame(bytes, single(Column("B", "BLOB", Types.BLOB)) { `when`(it.getBytes(1)).thenReturn(bytes) })
        assertSame(bytes, single(Column("B", "RAW", Types.VARBINARY)) { `when`(it.getBytes(1)).thenReturn(bytes) })
        assertEquals("text", single(Column("C", "NCLOB", Types.NCLOB)) { `when`(it.getString(1)).thenReturn("text") })
        assertEquals("plain", single(Column("V", "VARCHAR2", Types.VARCHAR)) { `when`(it.getObject(1)).thenReturn("plain") })
        assertNull(single(Column("V", "VARCHAR2", Types.VARCHAR)) { `when`(it.getObject(1)).thenReturn(null) })
    }

    @Test
    fun `every row is read, in order, and a result with none gives none`() {
        fun results(values: List<String>): ResultSet {
            val meta = Mockito.mock(ResultSetMetaData::class.java)
            `when`(meta.columnCount).thenReturn(1)
            `when`(meta.getColumnLabel(1)).thenReturn("V")
            `when`(meta.getColumnTypeName(1)).thenReturn("VARCHAR2")
            `when`(meta.getColumnType(1)).thenReturn(Types.VARCHAR)
            val results = Mockito.mock(ResultSet::class.java)
            `when`(results.metaData).thenReturn(meta)
            `when`(results.next()).thenReturn(values.isNotEmpty(), *Array(values.size) { it + 1 < values.size })
            if (values.isNotEmpty()) `when`(results.getObject(1)).thenReturn(values.first(), *values.drop(1).toTypedArray())
            return results
        }

        assertEquals(listOf<Any?>("a", "b", "c"), JdbcResultMapper.rows(results(listOf("a", "b", "c"))).map { it.values.single() })
        assertEquals(emptyList(), JdbcResultMapper.rows(results(emptyList())))
    }

    @Test
    fun `a row of one column maps to its value, a row of several to the list of them`() {
        val mapper = JdbcResultMapper()
        val spec = QuerySpecification.withStatement(sqlStatement("SELECT 1 FROM dual"))

        val one = JdbcResultMapper.rows(resultSet(listOf(Column("A", "NUMBER", Types.NUMERIC))) {
            `when`(it.getBigDecimal(1)).thenReturn(BigDecimal("1"))
        })
        assertEquals(listOf<Any>(1L), mapper.mapQueryResults(one, spec))

        val two = JdbcResultMapper.rows(resultSet(listOf(Column("A", "NUMBER", Types.NUMERIC), Column("B", "VARCHAR2", Types.VARCHAR))) {
            `when`(it.getBigDecimal(1)).thenReturn(BigDecimal("1"))
            `when`(it.getObject(2)).thenReturn("b")
        })
        assertEquals(listOf("A", "B"), two.single().keys)
        assertEquals(listOf<Any>(listOf(1L, "b")), mapper.mapQueryResults(two, spec))
    }
}
