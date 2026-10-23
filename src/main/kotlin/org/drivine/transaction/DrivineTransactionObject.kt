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
 */
class DrivineTransactionObject {
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
     * nothing behind. [Transactions.transaction] sets it when called with `rollback = true`.
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
