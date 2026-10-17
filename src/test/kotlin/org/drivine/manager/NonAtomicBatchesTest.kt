package org.drivine.manager

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A batch that runs statement by statement is said once as a warning, and after that at debug. */
class NonAtomicBatchesTest {

    @Test
    fun `the first batch is a warning that names the database, and each later one is a debug line`() {
        val logger = LoggerFactory.getLogger("${NonAtomicBatchesTest::class.java.name}.once") as Logger
        logger.level = Level.DEBUG
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        val batches = NonAtomicBatches(logger, "graph")
        batches.report(2)
        batches.report(3)
        batches.report(4)

        assertEquals(listOf(Level.WARN, Level.DEBUG, Level.DEBUG), appender.list.map { it.level })
        assertContains(appender.list.first().formattedMessage, "A batch of 2 statements on 'graph' is run statement by statement")
        assertContains(appender.list.last().formattedMessage, "A batch of 4 statements")
    }

    @Test
    fun `each manager warns once of its own`() {
        val logger = LoggerFactory.getLogger("${NonAtomicBatchesTest::class.java.name}.each") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        NonAtomicBatches(logger, "one").report(2)
        NonAtomicBatches(logger, "two").report(2)

        assertEquals(listOf(Level.WARN, Level.WARN), appender.list.map { it.level })
    }
}
