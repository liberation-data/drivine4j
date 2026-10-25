package org.drivine.sqlite

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * A SQLite transaction takes the file's write lock as it begins, and every other writer waits until it ends: one
 * held past the connection's warning is logged as it ends, committed, rolled back or released.
 */
class SqliteLockWarningTest {

    private val logger = LoggerFactory.getLogger(JdbcConnection::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
    }

    private fun connection(type: DatabaseType = DatabaseType.SQLITE, warning: Duration = Duration.ZERO) =
        JdbcConnection(DriverManager.getConnection("jdbc:sqlite::memory:"), JdbcResultMapper(SubtypeRegistry()), type, lockWarning = warning)

    private fun warnings() = appender.list.filter { "write lock" in it.formattedMessage }

    @Test
    fun `a transaction held past the warning is logged as it ends, however it ends`() {
        connection().apply { startTransaction(); commitTransaction(); release(null) }
        connection().apply { startTransaction(); rollbackTransaction(); release(null) }
        connection().apply { startTransaction(); release(null) }
        assertEquals(3, warnings().size)
        assertTrue(warnings().all { it.level.toString() == "WARN" && "no other writer can write" in it.formattedMessage })
    }

    @Test
    fun `a short transaction, a statement outside one, and another store's transaction are not logged`() {
        connection(warning = Duration.ofMinutes(1)).apply { startTransaction(); commitTransaction(); release(null) }
        connection().release(null)
        // Postgres's and Oracle's writers wait for the rows they write, not for the whole database.
        connection(type = DatabaseType.POSTGRES).apply { startTransaction(); commitTransaction(); release(null) }
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `a transaction whose commit SQLite refuses is logged once, as it is rolled back`() {
        val raw = DriverManager.getConnection("jdbc:sqlite::memory:")
        val connection = JdbcConnection(raw, JdbcResultMapper(SubtypeRegistry()), DatabaseType.SQLITE, lockWarning = Duration.ZERO)
        fun run(sql: String) = connection.query(QuerySpecification.withStatement(sql))
        run("PRAGMA foreign_keys = ON")
        run("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE child (parent INTEGER REFERENCES parent (id) DEFERRABLE INITIALLY DEFERRED)")
        connection.startTransaction()
        run("INSERT INTO child (parent) VALUES (1)")
        assertFails { connection.commitTransaction() }
        assertEquals(emptyList(), warnings())
        connection.rollbackTransaction()
        connection.release(null)
        assertEquals(1, warnings().size)
    }

    @Test
    fun `a transaction is logged as it is released, though the driver then fails to go back to committing each statement`() {
        val raw = DriverManager.getConnection("jdbc:sqlite::memory:")
        val failing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
            if (method.name == "setAutoCommit" && args?.single() == true) throw java.sql.SQLException("refused")
            try {
                method.invoke(raw, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as java.sql.Connection
        JdbcConnection(failing, JdbcResultMapper(SubtypeRegistry()), DatabaseType.SQLITE, lockWarning = Duration.ZERO).apply {
            startTransaction()
            release(null)
        }
        assertEquals(1, warnings().size)
    }

    @Test
    fun `a transaction holds the write lock from its start, so another transaction waits for it even to read, and a read outside one does not`() {
        val path = Files.createTempDirectory("drivine-sqlite-lock").resolve("lock.db").toString()
        val provider = JdbcConnectionProvider.sqlite("lock", path, busyTimeoutMillis = 0)
        try {
            fun count(connection: org.drivine.connection.Connection) = connection.query(QuerySpecification.withStatement("SELECT count(*) AS n FROM t")).single()
            provider.connect().apply { query(QuerySpecification.withStatement("CREATE TABLE t (v TEXT)")); release(null) }
            val holder = provider.connect().apply { startTransaction() }
            val other = provider.connect()
            try {
                assertEquals(0L, count(other))
                assertFails { other.startTransaction(); count(other) }
            } finally {
                other.release(null)
                holder.release(null)
            }
        } finally {
            provider.end()
        }
    }
}
