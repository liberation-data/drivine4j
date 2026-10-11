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
import java.sql.Types
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
