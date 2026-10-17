package org.drivine.manager

import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Says that a batch of more than one statement runs on a store that cannot undo a statement it has
 * run, so that a failure midway leaves the statements before it applied: once as a warning, for each
 * manager, and as a debug line for each batch after that. See [PersistenceManager.atomicBatches].
 */
internal class NonAtomicBatches(private val logger: Logger, private val database: String) {

    private val warned = AtomicBoolean(false)

    fun report(statements: Int) {
        if (warned.compareAndSet(false, true)) {
            logger.warn(
                """
                A batch of {} statements on '{}' is run statement by statement: the store cannot undo a statement
                it has run, so a failure midway leaves the statements before it applied. A saveAll of fragments
                alone is one statement where they fit in one; set the store to refuse such a batch (FalkorDB
                STRICT mode) to be told.
                Said once; each later batch is logged at debug.
                """.trimIndent(),
                statements, database,
            )
        } else {
            logger.debug("A batch of {} statements on '{}' is run statement by statement, not whole", statements, database)
        }
    }
}
