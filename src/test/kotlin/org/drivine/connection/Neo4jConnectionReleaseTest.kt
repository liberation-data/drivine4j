package org.drivine.connection

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.mapper.ResultMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Session
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.exceptions.TransientException
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/** What a Neo4j session closed after a failure logs (#473). */
class Neo4jConnectionReleaseTest {

    private val logger = LoggerFactory.getLogger(Neo4jConnection::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var level: Level? = null
    private var closed = 0

    private inline fun <reified T> nothing(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (method.name == "close") closed++
            null
        } as T

    private val connection = Neo4jConnection(nothing<Session>(), nothing<ResultMapper>())

    @BeforeEach
    fun attach() {
        level = logger.level
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        logger.level = level
    }

    @Test
    fun `a statement the engine turned away for another session's is logged at debug, for its caller runs it again`() {
        connection.release(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "Deadlock wait cycle."))
        assertEquals(listOf(Level.DEBUG), appender.list.map { it.level })
        assertEquals(1, closed)
    }

    @Test
    fun `another failure is warned of`() {
        connection.release(ClientException("Neo.ClientError.Statement.SyntaxError", "Invalid input"))
        assertEquals(listOf(Level.WARN), appender.list.map { it.level })
        assertEquals(1, closed)
    }
}
