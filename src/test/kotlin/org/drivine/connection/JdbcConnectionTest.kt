package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.mapper.JdbcResultMapper
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.Reader
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcConnectionTest {

    private lateinit var jdbc: java.sql.Connection
    private lateinit var statement: PreparedStatement
    private lateinit var connection: JdbcConnection

    @BeforeEach
    fun setUp() {
        jdbc = Mockito.mock(java.sql.Connection::class.java)
        statement = Mockito.mock(PreparedStatement::class.java)
        `when`(jdbc.prepareStatement(anyString())).thenReturn(statement)
        answers(emptyList())
        connection = JdbcConnection(jdbc, JdbcResultMapper())
    }

    /** Stubs the statement to answer, as a query does, with a result of [values]. */
    private fun answers(values: List<Int>) {
        // The result set is built first: Mockito takes a stubbing begun inside another's `thenReturn` as unfinished.
        val results = resultSet(values)
        `when`(statement.execute()).thenReturn(true)
        `when`(statement.resultSet).thenReturn(results)
    }

    /** A result of one column `V`, an integer, with a row for each of [values]. */
    private fun resultSet(values: List<Int>): ResultSet {
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        `when`(meta.columnCount).thenReturn(1)
        `when`(meta.getColumnLabel(1)).thenReturn("V")
        `when`(meta.getColumnTypeName(1)).thenReturn("INTEGER")
        `when`(meta.getColumnType(1)).thenReturn(Types.INTEGER)
        val results = Mockito.mock(ResultSet::class.java)
        `when`(results.metaData).thenReturn(meta)
        if (values.isEmpty()) {
            `when`(results.next()).thenReturn(false)
        } else {
            `when`(results.next()).thenReturn(true, *Array(values.size) { it + 1 < values.size })
            `when`(results.getObject(1)).thenReturn(values.first(), *values.drop(1).toTypedArray())
        }
        return results
    }

    private fun query(sql: String, parameters: Map<String, Any?>): List<Any> =
        connection.query(QuerySpecification.withStatement(sqlStatement(sql)).bind(parameters))

    @Test
    fun `a parameter named twice is bound twice, in the order the names occur`() {
        query("SELECT * FROM t WHERE a = \$a OR a = \$a", mapOf("a" to 1))

        verify(jdbc).prepareStatement("SELECT * FROM t WHERE a = ? OR a = ?")
        val order = inOrder(statement)
        order.verify(statement).setObject(1, 1)
        order.verify(statement).setObject(2, 1)
    }

    @Test
    fun `parameters are bound by the position they occur at, not the order they were given`() {
        query("SELECT * FROM t WHERE b = \$b AND a = \$a", mapOf("a" to "x", "b" to 2))

        verify(statement).setObject(1, 2)
        verify(statement).setString(2, "x")
    }

    @Test
    fun `a parameter with no value bound to it is refused, by name`() {
        val e = assertFailsWith<DrivineException> { query("SELECT * FROM t WHERE a = \$a AND b = \$missing", mapOf("a" to 1)) }
        assertTrue(e.message!!.contains("\$missing"), e.message)
        verify(statement, never()).execute()
    }

    @Test
    fun `a null is bound as a null of no declared type`() {
        query("SELECT * FROM t WHERE a = \$a", mapOf("a" to null))

        verify(statement).setNull(1, Types.VARCHAR)
    }

    @Test
    fun `a string goes as a CLOB when its bytes are too many for a VARCHAR2, and not merely its characters`() {
        query("SELECT \$s FROM dual", mapOf("s" to "€".repeat(12_000)))
        verify(statement).setClob(eq(1), any(Reader::class.java))
        verify(statement, never()).setString(eq(1), anyString())

        Mockito.reset(statement)
        answers(emptyList())
        val ascii = "a".repeat(12_000)
        query("SELECT \$s FROM dual", mapOf("s" to ascii))
        verify(statement).setString(1, ascii)
        verify(statement, never()).setClob(eq(1), any(Reader::class.java))

        Mockito.reset(statement)
        answers(emptyList())
        query("SELECT \$s FROM dual", mapOf("s" to "a".repeat(40_000)))
        verify(statement).setClob(eq(1), any(Reader::class.java))
    }

    @Test
    fun `a collection or a map is bound as JSON text, and a zoned date time as its offset form`() {
        val moment = ZonedDateTime.parse("2026-03-01T10:15:30+01:00[Europe/Paris]")
        query(
            "SELECT \$ids, \$row, \$at FROM dual",
            mapOf("ids" to listOf(1, 2), "row" to mapOf("name" to "Ada"), "at" to moment),
        )

        verify(statement).setString(1, "[1,2]")
        verify(statement).setString(2, "{\"name\":\"Ada\"}")
        verify(statement).setObject(3, moment.toOffsetDateTime())
    }

    @Test
    fun `a statement the database refuses is closed, and the refusal is the caller's as the driver gave it`() {
        val refusal = SQLException("ORA-00942: table or view does not exist", "42000", 942)
        `when`(statement.execute()).thenThrow(refusal)

        val thrown = assertFailsWith<SQLException> { query("SELECT v FROM missing", emptyMap()) }

        assertSame(refusal, thrown)
        verify(statement).close()
        verify(jdbc, never()).close()
    }

    @Test
    fun `a query answers with its result set`() {
        answers(listOf(7, 8))

        assertEquals(listOf<Any>(7, 8), query("SELECT v FROM t", emptyMap()))
        verify(statement).close()
    }

    @Test
    fun `a block that hands back a result set is asked for it`() {
        val results = resultSet(listOf(42))
        `when`(statement.execute()).thenReturn(false)
        `when`(statement.moreResults).thenReturn(true)
        `when`(statement.resultSet).thenReturn(results)

        assertEquals(listOf<Any>(42), query("BEGIN NULL; END;", emptyMap()))
    }

    @Test
    fun `a statement that returns no result set gives no rows`() {
        `when`(statement.execute()).thenReturn(false)
        `when`(statement.moreResults).thenReturn(false)

        assertEquals(emptyList(), query("DELETE FROM t", emptyMap()))
        verify(statement, never()).resultSet
    }

    @Test
    fun `a statement that is Cypher is refused, and one of no language is taken as SQL`() {
        assertFailsWith<DrivineException> { connection.query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n"))) }
        verify(jdbc, never()).prepareStatement(anyString())

        connection.query(QuerySpecification.withStatement("SELECT 1 FROM dual"))
        verify(jdbc).prepareStatement("SELECT 1 FROM dual")
    }

    @Test
    fun `a transaction is opened once, and committed or rolled back only when open`() {
        assertFailsWith<DrivineException> { connection.commitTransaction() }
        assertFailsWith<DrivineException> { connection.rollbackTransaction() }

        connection.startTransaction()
        verify(jdbc).autoCommit = false
        assertFailsWith<DrivineException> { connection.startTransaction() }

        connection.commitTransaction()
        val order = inOrder(jdbc)
        order.verify(jdbc).commit()
        order.verify(jdbc).autoCommit = true
        assertFailsWith<DrivineException> { connection.commitTransaction() }
    }

    @Test
    fun `releasing a connection with a transaction open rolls it back, restores auto-commit and closes it`() {
        connection.startTransaction()
        connection.release(null)

        val order = inOrder(jdbc)
        order.verify(jdbc).rollback()
        order.verify(jdbc).autoCommit = true
        order.verify(jdbc).close()
        verify(jdbc, never()).commit()
    }

    @Test
    fun `a commit that fails leaves the transaction open, so releasing the connection rolls it back`() {
        connection.startTransaction()
        `when`(jdbc.commit()).thenThrow(SQLException("ORA-02091: transaction rolled back"))

        assertFailsWith<SQLException> { connection.commitTransaction() }
        connection.release(null)

        val order = inOrder(jdbc)
        order.verify(jdbc).rollback()
        order.verify(jdbc).autoCommit = true
        order.verify(jdbc).close()
    }

    @Test
    fun `a connection whose rollback fails on release is closed all the same`() {
        connection.startTransaction()
        `when`(jdbc.rollback()).thenThrow(SQLException("ORA-03113: end-of-file on communication channel"))

        connection.release(null)

        verify(jdbc).close()
    }

    @Test
    fun `releasing a connection with no transaction only closes it`() {
        connection.release(RuntimeException("gone wrong"))

        verify(jdbc).close()
        verify(jdbc, never()).rollback()
    }
}
