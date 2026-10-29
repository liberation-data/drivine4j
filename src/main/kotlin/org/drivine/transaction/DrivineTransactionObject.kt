package org.drivine.transaction

import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One transaction, across every database it touches. A connection to each database is opened, and
 * its transaction begun, the first time the transaction runs a statement there (see
 * [getOrCreateConnection]), so a transaction that runs none costs nothing.
 *
 * [Transactions] begins and ends one, and so does the Spring adapter's `DrivineTransactionManager`:
 * both keep the transaction in progress in [TransactionContextHolder], so each joins the other's.
 *
 * @param refusal given, this is not a transaction but stands in for one that may not be used where it
 *   is found, and says why: a statement run in it, or a block that would join it, is refused with
 *   [IllegalTransactionStateException] and this message. `drivine4j-coroutines` gives one to a
 *   coroutine launched inside a transaction block, so that two coroutines never use one transaction's
 *   connection at once.
 */
class DrivineTransactionObject @JvmOverloads constructor(val refusal: String? = null) {
    val id: String = generateShortId()
    private val connectionRegistry = mutableMapOf<String, Connection>()
    private val connectionLock = ReentrantLock()

    /**
     * Set when a part of the transaction that joined it failed: by Spring, for a `@Transactional`
     * method that threw, and by [Transactions] for a block that threw. Nothing reads it: the
     * transaction still commits if the code that began it carries on.
     */
    var rollbackOnly = false

    /**
     * Roll back at the end instead of committing, though nothing failed: for a test that leaves
     * nothing behind. [runToEnd] and [runJoined] set it when given `rollback = true`, as
     * [Transactions.transaction] gives it.
     */
    var rollbackOnCompletion = false

    val databases: List<String>
        get() = connectionRegistry.keys.toList()

    val connections: List<Connection>
        get() = connectionRegistry.values.toList()

    fun hasActiveConnections(): Boolean = connectionRegistry.isNotEmpty()

    /**
     * Gets or creates a connection for the specified database.
     * Called by PersistenceManager when a query needs to be executed. A connection whose
     * transaction can't be begun (FalkorDB in `STRICT` mode refuses one) is released, not kept.
     */
    fun getOrCreateConnection(database: String, contextHolder: TransactionContextHolder): Connection {
        refusal?.let { throw IllegalTransactionStateException(it) }
        connectionLock.withLock {
            return connectionRegistry.getOrPut(database) {
                val connectionProvider = contextHolder.databaseRegistry.connectionProvider(database)
                    ?: throw DrivineException("No database registered with key: $database")

                val connection = connectionProvider.connect()
                try {
                    connection.startTransaction()
                } catch (ex: Exception) {
                    connection.release(ex)
                    throw ex
                }
                connection
            }
        }
    }

    /** Commits the transaction on each database it touched. Not two-phase: a failure leaves those before it committed. */
    fun commit() {
        try {
            logger.debug("Committing transaction: $id, databases: $databases")
            connections.forEach { it.commitTransaction() }
        } catch (ex: Exception) {
            throw DrivineException("Could not commit transaction: $id", ex)
        }
    }

    /** Rolls the transaction back on each database it touched. */
    fun rollback() {
        try {
            logger.debug("Rolling back transaction: $id, databases: $databases")
            connections.forEach { it.rollbackTransaction() }
        } catch (ex: Exception) {
            throw DrivineException("Could not rollback transaction: $id", ex)
        }
    }

    /** Releases every connection, after the transaction has committed or rolled back. A failure is logged, not thrown. */
    fun release() {
        logger.debug("Cleaning up transaction: $id")
        connections.forEach { connection ->
            try {
                connection.release(null)
            } catch (ex: Exception) {
                logger.warn("Error releasing connection in transaction $id: ${ex.message}", ex)
            }
        }
        clearConnections()
    }

    /**
     * Runs [block] as the code that began this transaction: commits when it returns (or rolls back,
     * where [rollback] or [rollbackOnCompletion] asks), rolls back when anything is thrown out of it,
     * a checked exception too, and releases the connections either way. The steps, and their order,
     * are those Spring's `AbstractPlatformTransactionManager` takes through the adapter. It is inline,
     * so [block] may suspend.
     */
    inline fun <T> runToEnd(rollback: Boolean, block: (DrivineTransactionObject) -> T): T {
        if (rollback) rollbackOnCompletion = true
        try {
            val result = try {
                block(this)
            } catch (ex: Throwable) {
                rollBackAfter(ex)
                throw ex
            }
            if (rollbackOnCompletion) {
                rollback()
            } else {
                try {
                    commit()
                } catch (ex: Exception) {
                    rollBackAfter(ex)
                    throw ex
                }
            }
            return result
        } finally {
            release()
        }
    }

    /**
     * Runs [block] as code that joined this transaction, leaving its end to the code that began it.
     * Something thrown out of [block] marks it [rollbackOnly], as Spring marks one for a
     * `@Transactional` method that throws. [rollback] makes the whole transaction roll back at its end.
     *
     * @throws IllegalTransactionStateException where this stands in for a transaction that may not be
     *   used here: see [refusal].
     */
    inline fun <T> runJoined(rollback: Boolean, block: () -> T): T {
        refusal?.let { throw IllegalTransactionStateException(it) }
        if (rollback) rollbackOnCompletion = true
        try {
            return block()
        } catch (ex: Throwable) {
            rollbackOnly = true
            throw ex
        }
    }

    /** Rolls back on the way out of a failure, and keeps a failure to roll back from hiding the one that caused it. */
    @PublishedApi
    internal fun rollBackAfter(cause: Throwable) {
        try {
            rollback()
        } catch (ex: Exception) {
            cause.addSuppressed(ex)
        }
    }

    fun clearConnections() {
        connectionLock.withLock {
            connectionRegistry.clear()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DrivineTransactionObject::class.java)

        private fun generateShortId(): String {
            return UUID.randomUUID().toString().take(7)
        }
    }
}
