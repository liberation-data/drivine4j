package org.drivine.connection

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.DrivineException
import org.drivine.mapper.JdbcResultMapper
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.io.Reader
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Types
import java.time.ZonedDateTime
import kotlin.test.assertContains
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
    private fun answers(values: List<Long>) {
        // The result set is built first: Mockito takes a stubbing begun inside another's `thenReturn` as unfinished.
        val results = resultSet(values)
        `when`(statement.execute()).thenReturn(true)
        `when`(statement.resultSet).thenReturn(results)
    }

    /** A result of one column `V`, an integer, with a row for each of [values]. An integer is read as a `Long`. */
    private fun resultSet(values: List<Long>): ResultSet {
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
            `when`(results.getLong(1)).thenReturn(values.first(), *values.drop(1).toTypedArray())
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
    fun `a long string goes to Postgres as a string, since a CLOB there would be taken for a large object`() {
        val postgres = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES)
        val long = "€".repeat(12_000)
        postgres.query(QuerySpecification.withStatement(sqlStatement("SELECT \$s")).bind(mapOf("s" to long)))
        verify(statement).setString(1, long)
        verify(statement, never()).setClob(eq(1), any(Reader::class.java))
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
        answers(listOf(7L, 8L))

        assertEquals(listOf<Any>(7L, 8L), query("SELECT v FROM t", emptyMap()))
        verify(statement).close()
    }

    @Test
    fun `a block that hands back a result set is asked for it`() {
        val results = resultSet(listOf(42L))
        `when`(statement.execute()).thenReturn(false)
        `when`(statement.moreResults).thenReturn(true)
        `when`(statement.resultSet).thenReturn(results)

        assertEquals(listOf<Any>(42L), query("BEGIN NULL; END;", emptyMap()))
    }

    @Test
    fun `a statement that returns no result set gives no rows`() {
        `when`(statement.execute()).thenReturn(false)
        `when`(statement.moreResults).thenReturn(false)

        assertEquals(emptyList(), query("DELETE FROM t", emptyMap()))
        verify(statement, never()).resultSet
    }

    @Test
    fun `a statement that is Cypher is compiled to SQL, and one of no language is taken as SQL`() {
        connection.query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        val prepared = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).prepareStatement(prepared.capture())
        // What reaches the database is the compiled SQL over the graph's tables, not the Cypher.
        assertTrue(prepared.value.startsWith("SELECT ") && "_node" in prepared.value, prepared.value)

        connection.query(QuerySpecification.withStatement("SELECT 1 FROM dual"))
        verify(jdbc).prepareStatement("SELECT 1 FROM dual")
    }

    @Test
    fun `on Oracle a statement compiled from Cypher is not kept in the driver's cache, and a SQL one, or one on Postgres, is`() {
        // A kept compiled statement holds megabytes of Oracle's memory (#313); Drivine's own SQL is cheap to keep.
        connection.query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        verify(statement).isPoolable = false

        Mockito.reset(statement)
        answers(emptyList())
        connection.query(QuerySpecification.withStatement("SELECT 1 FROM dual"))
        JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES).query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        verify(statement, never()).isPoolable = Mockito.anyBoolean()
    }

    @Test
    fun `a Cypher statement is compiled over the tables of the connection's graph, on a store whose SQL is compiled to`() {
        JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES, "people")
            .query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        val prepared = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).prepareStatement(prepared.capture())
        assertTrue("people_node" in prepared.value, prepared.value)

        val refused = assertFailsWith<DrivineException> {
            JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.NEO4J).query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        }
        assertEquals("A Cypher statement does not run on NEO4J yet.", refused.message)
        verify(jdbc).prepareStatement(anyString())
    }

    @Test
    fun `a Cypher statement over a graph whose name cannot name tables is refused, naming the graph and why, and plain SQL runs`() {
        listOf(DatabaseType.ORACLE to "Oracle", DatabaseType.POSTGRES to "Postgres", DatabaseType.SQLITE to "SQLite").forEach { (type, engine) ->
            val connection = JdbcConnection(jdbc, JdbcResultMapper(), type, "my-graph")

            val refused = assertFailsWith<DrivineException> { connection.query(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n"))) }
            assertTrue(refused.message.orEmpty().startsWith("'my-graph' cannot name a graph on $engine: it names tables"), refused.message)
            connection.query(QuerySpecification.withStatement("SELECT 1 AS one"))
        }
        // Only the plain SQL reached the database.
        verify(jdbc, Mockito.times(3)).prepareStatement(anyString())
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

    /**
     * A build that fails, as an HNSW build that finds no room in `/dev/shm` does, may have run in parallel as far as it
     * got, and left its session holding shared memory: its session is closed out of its pool all the same, and not
     * given back to it (#626).
     */
    @Test
    fun `a Postgres session an index build failed on is closed out of its pool, not given back`() {
        val evicted = mutableListOf<java.sql.Connection>()
        val postgres = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES).apply {
            restoresAutoCommit = true
            noEviction = null
            evictor = { evicted += it }
        }
        val failed = SQLException("could not resize shared memory segment \"/PostgreSQL.1\" to 63999616 bytes: No space left on device")

        assertSame(failed, assertFailsWith<SQLException> { postgres.buildingIndex<Unit> { throw failed } })
        postgres.release(failed)

        assertEquals(listOf(jdbc), evicted)
        verify(jdbc).close()
        // Not turned back to committing nothing of itself, as a session given back to its pool is, and never run with no parallel workers.
        verify(jdbc, never()).autoCommit = false
        verify(jdbc, never()).prepareStatement(Mockito.contains("set_config"))
        verify(jdbc, never()).createStatement()
    }

    /** The statement a session made by [postgresReading] reads its setting with, and resets it with. */
    private lateinit var read: java.sql.Statement

    /**
     * A Postgres connection whose session reads `max_parallel_maintenance_workers` as [workers], a value the session set
     * itself where [own], and sets it with [statement].
     */
    private fun postgresReading(workers: String, own: Boolean = true): JdbcConnection {
        val setting = Mockito.mock(ResultSet::class.java).also {
            `when`(it.getString(1)).thenReturn(workers)
            `when`(it.getBoolean(2)).thenReturn(own)
        }
        read = Mockito.mock(java.sql.Statement::class.java).also { `when`(it.executeQuery(anyString())).thenReturn(setting) }
        `when`(jdbc.createStatement()).thenReturn(read)
        `when`(statement.executeQuery()).thenReturn(setting)
        return JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES)
    }

    /**
     * A transaction Drivine began on the connection would end partway through were its session closed, so an index build
     * in it runs with no parallel workers, and the session is given back to its pool, not closed out of it (#626).
     */
    @Test
    fun `a Postgres index build in a transaction Drivine began runs with no parallel workers, and its session is not evicted`() {
        val evicted = mutableListOf<java.sql.Connection>()
        val refused = mutableListOf<String>()
        val postgres = postgresReading("2").apply {
            noEviction = null
            evictor = { evicted += it }
            evictionRefused = { refused += it }
        }
        postgres.startTransaction()

        postgres.buildingIndex { }
        postgres.commitTransaction()
        postgres.release()

        assertEquals(listOf("it is in a transaction"), refused)
        verify(statement).setString(1, "0")
        verify(statement).setString(1, "2")
        assertEquals(emptyList(), evicted)
    }

    /** A pool that fails to close a session out of it still has the session closed, rather than taken as given back sound. */
    @Test
    fun `a Postgres session whose eviction fails after an index build is closed all the same`() {
        val postgres = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES).apply {
            restoresAutoCommit = true
            noEviction = null
            evictor = { throw IllegalStateException("pool closed") }
        }

        postgres.buildingIndex { }
        postgres.release()

        verify(jdbc).close()
        verify(jdbc, never()).autoCommit = false
    }

    /**
     * In a caller's transaction a failed build aborts it, so setting the workers back fails too: the build's failure is the
     * one thrown, and the transaction's rollback sets the workers back.
     */
    @Test
    fun `a failed Postgres index build in a caller's transaction throws its own failure, not that of setting the workers back`() {
        val postgres = postgresReading("2").apply { noEviction = "its connection is a transaction's that Drivine did not begin" }
        val failed = SQLException("could not resize shared memory segment")
        val aborted = SQLException("current transaction is aborted")
        Mockito.doThrow(aborted).`when`(statement).setString(1, "2")

        assertSame(failed, assertFailsWith<SQLException> { postgres.buildingIndex<Unit> { throw failed } })
        verify(statement).setString(1, "0")
    }

    // ----- A transaction Drivine joins: a Spring or JTA transaction's connection (#724) -----

    /** Runs [block] with what [JdbcConnection] logs at WARN or above, and gives back those messages. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(JdbcConnection::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
    }

    @Test
    fun `a transaction on a joined connection begins nothing, commits nothing, rolls back nothing, and leaves auto-commit alone`() {
        connection.joined = true

        warnings {
            connection.startTransaction()
            query("INSERT INTO t VALUES (1)", emptyMap())
            connection.commitTransaction()
            connection.startTransaction()
            connection.rollbackTransaction()
            connection.startTransaction()
            connection.release(null)
        }

        verify(jdbc).prepareStatement("INSERT INTO t VALUES (1)")
        verify(jdbc, never()).commit()
        verify(jdbc, never()).rollback()
        verify(jdbc, never()).autoCommit = Mockito.anyBoolean()
        verify(jdbc).close()
    }

    @Test
    fun `on SQLite a transaction on a joined connection sends no COMMIT or ROLLBACK of its own`() {
        val sqlite = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.SQLITE).also { it.joined = true }

        warnings {
            sqlite.startTransaction()
            sqlite.commitTransaction()
            sqlite.startTransaction()
            sqlite.rollbackTransaction()
        }

        verify(jdbc, never()).createStatement()
        verify(jdbc, never()).autoCommit = Mockito.anyBoolean()
    }

    @Test
    fun `a joined transaction that rolls back with no Spring transaction and no JTA registry to mark says so, once for each rollback`() {
        connection.joined = true

        val logged = warnings {
            connection.startTransaction()
            connection.rollbackTransaction()
            connection.startTransaction()
            connection.release(null)
            // A commit marks nothing, and says nothing.
            connection.startTransaction()
            connection.commitTransaction()
        }

        assertEquals(2, logged.size, logged.toString())
        logged.forEach {
            assertContains(it, "could not be marked to roll back")
            assertContains(it, JtaTransactions.REGISTRY)
        }
        verify(jdbc, never()).rollback()
    }

    /**
     * A value of `max_parallel_maintenance_workers` the session set itself is set back with `set_config` after a build
     * with no parallel workers, as the session's own again.
     */
    @Test
    fun `a Postgres session's own parallel workers are set back after an index build with none`() {
        val postgres = postgresReading("2", own = true).apply { noEviction = "its pool cannot close a session for good" }
        `when`(jdbc.autoCommit).thenReturn(true)

        postgres.buildingIndex { }

        verify(read).executeQuery(Mockito.contains("pg_settings"))
        // For the session, not a transaction: outside one, a transaction-local value would end with the statement that set it.
        verify(jdbc, Mockito.times(2)).prepareStatement("SELECT set_config('max_parallel_maintenance_workers', ?, false)")
        verify(statement).setString(1, "0")
        verify(statement).setString(1, "2")
        verify(read, never()).execute(Mockito.contains("RESET"))
    }

    /**
     * A value of `max_parallel_maintenance_workers` the session did not set, from `postgresql.conf`, the role or the
     * database, or the default, is reset after a build with no parallel workers, not set as the session's own, so that the
     * session follows a later reload or `ALTER ROLE` as it did before.
     */
    @Test
    fun `parallel workers a Postgres session did not set are reset after an index build with none, not set as its own`() {
        val postgres = postgresReading("2", own = false).apply { noEviction = "its pool cannot close a session for good" }
        `when`(jdbc.autoCommit).thenReturn(true)

        postgres.buildingIndex { }

        verify(statement).setString(1, "0")
        verify(statement, never()).setString(1, "2")
        verify(read).execute("RESET max_parallel_maintenance_workers")
    }

    /**
     * In a transaction, `max_parallel_maintenance_workers` is set to 0 and back for the transaction alone, whatever the
     * value's source, so that its end undoes both, and a value the transaction set for itself alone (`SET LOCAL`), which
     * shows as the session's too, is not made the session's own after it.
     */
    @Test
    fun `a Postgres index build in a transaction sets parallel workers to none and back for the transaction alone`() {
        val postgres = postgresReading("2", own = false).apply { noEviction = "its connection is a transaction's that Drivine did not begin" }
        `when`(jdbc.autoCommit).thenReturn(false)

        postgres.buildingIndex { }

        verify(jdbc, Mockito.times(2)).prepareStatement("SELECT set_config('max_parallel_maintenance_workers', ?, true)")
        verify(jdbc, never()).prepareStatement("SELECT set_config('max_parallel_maintenance_workers', ?, false)")
        verify(statement).setString(1, "0")
        verify(statement).setString(1, "2")
        verify(read, never()).execute(Mockito.contains("RESET"))
    }

    @Test
    fun `on Postgres a plan the database refuses inside a transaction is none, rolled back to a savepoint so the transaction goes on`() {
        val savepoint = Mockito.mock(java.sql.Savepoint::class.java)
        `when`(jdbc.autoCommit).thenReturn(false)
        `when`(jdbc.setSavepoint()).thenReturn(savepoint)
        `when`(statement.executeQuery()).thenThrow(SQLException("ERROR: syntax error at or near \"DO\"\n  Position: 9"))
        val plan = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES)
            .plan(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        assertEquals(emptyList(), plan.lines)
        assertEquals("the database doesn't plan what it compiles to: ERROR: syntax error at or near \"DO\"", plan.none)
        val order = inOrder(jdbc)
        order.verify(jdbc).setSavepoint()
        order.verify(jdbc).rollback(savepoint)
        order.verify(jdbc).releaseSavepoint(savepoint)
        verify(jdbc, never()).rollback()
    }

    @Test
    fun `on Postgres outside a transaction a plan takes no savepoint, and gives EXPLAIN's rows`() {
        `when`(jdbc.autoCommit).thenReturn(true)
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        `when`(meta.columnCount).thenReturn(1)
        val rows = Mockito.mock(ResultSet::class.java)
        `when`(rows.metaData).thenReturn(meta)
        `when`(rows.next()).thenReturn(true, true, false)
        `when`(rows.getString(1)).thenReturn("Seq Scan on drivine_node n", "  Filter: (labels @> '{}')")
        `when`(statement.executeQuery()).thenReturn(rows)
        val plan = JdbcConnection(jdbc, JdbcResultMapper(), DatabaseType.POSTGRES)
            .plan(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        assertEquals(listOf("Seq Scan on drivine_node n", "  Filter: (labels @> '{}')"), plan.lines)
        assertEquals(null, plan.none)
        verify(jdbc, never()).setSavepoint()
        val prepared = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).prepareStatement(prepared.capture())
        assertTrue(prepared.value.startsWith("EXPLAIN "), prepared.value)
    }

    @Test
    fun `on Oracle the plan's PLAN_TABLE rows are deleted after, though DBMS_XPLAN fails`() {
        `when`(statement.execute()).thenReturn(false)
        `when`(statement.executeQuery()).thenThrow(SQLException("ORA-00942: table or view does not exist"))
        val plan = connection.plan(QuerySpecification.withStatement(cypherStatement("MATCH (n) RETURN n")))
        assertEquals("the database doesn't plan what it compiles to: ORA-00942: table or view does not exist", plan.none)
        val prepared = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc, Mockito.times(3)).prepareStatement(prepared.capture())
        val id = Regex("STATEMENT_ID = '([^']+)'").find(prepared.allValues[0])!!.groupValues[1]
        assertEquals("DELETE FROM plan_table WHERE statement_id = ?", prepared.allValues[2])
        verify(statement, Mockito.times(2)).setString(1, id)
        verify(statement).executeUpdate()
    }

    @Test
    fun `a plan of SQL as it is is refused, as explain refuses it`() {
        val refused = assertFailsWith<DrivineException> { connection.plan(QuerySpecification.withStatement(sqlStatement("SELECT 1 FROM dual"))) }
        assertContains(refused.message!!, "this is SQL already")
        verify(jdbc, never()).prepareStatement(anyString())
    }
}
