package org.drivine.logger

import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class StatementLogger(private val sessionId: String) {

    private val logger = LoggerFactory.getLogger(StatementLogger::class.java)

    fun log(query: QuerySpecification<*>, hrStart: Instant, error: Exception? = null) {
        val hrEnd = Duration.between(hrStart, Instant.now())
        val uSec = hrEnd.toMillis() * 1000

        if (error != null) {
            logger.error(
                "Query failed: {}, SessionId: {}, Elapsed: {} µsec = {} ms, Error: {}",
                query,
                sessionId,
                uSec,
                uSec / 1000,
                error.message
            )
        } else {
            logger.debug(
                "Query: {}, SessionId: {}, Elapsed: {} µsec = {} ms",
                query,
                sessionId,
                uSec,
                uSec / 1000
            )
        }
    }
}
