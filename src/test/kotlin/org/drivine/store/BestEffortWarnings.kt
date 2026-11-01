package org.drivine.store

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

/**
 * Runs [block], and fails if [StoreIdentityResolver] warned while it ran that identity is best-effort: that a manager
 * went on to write its stamp without the key's constraint in force. Managers racing to make the constraint must each
 * end up under the winner's, though the ids they agree on and the one stamp they leave look the same either way.
 */
internal fun assertNoBestEffortWarning(block: () -> Unit) {
    val logger = LoggerFactory.getLogger(StoreIdentityResolver::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    val bestEffort = appender.list
        .filter { it.level == Level.WARN && it.formattedMessage.contains("best-effort") }
        .map { it.formattedMessage }
    assertEquals(emptyList(), bestEffort, "every manager must end up under the key's constraint")
}
