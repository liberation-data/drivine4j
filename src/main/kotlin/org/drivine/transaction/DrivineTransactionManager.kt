package org.drivine.transaction

import org.drivine.DrivineException
import org.drivine.connection.Connection
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Spring PlatformTransactionManager implementation for Drivine.
 *
 * Integrates Drivine's vendor-agnostic ConnectionProvider and Connection interfaces
 * with Spring's transaction management infrastructure. This allows @Transactional
 * methods to work with Drivine's graph database abstraction layer.
 *
 * The actual database-specific behavior is delegated to ConnectionProvider and
 * Connection implementations (e.g., Neo4jConnectionProvider, Neo4jConnection).
 */
class DrivineTransactionManager(
    private val contextHolder: TransactionContextHolder
) : AbstractPlatformTransactionManager() {

    private val logger = LoggerFactory.getLogger(DrivineTransactionManager::class.java)

    init {
        // Configure default behavior
        isNestedTransactionAllowed = false
        isValidateExistingTransaction = true
    }

    override fun doGetTransaction(): Any {
        val existingTxObject = contextHolder.currentTransaction
        return existingTxObject ?: DrivineTransactionObject()
    }

    override fun isExistingTransaction(transaction: Any): Boolean {
        val txObject = transaction as DrivineTransactionObject
        return txObject.hasActiveConnections()
    }

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        val txObject = transaction as DrivineTransactionObject

        try {
            logger.debug("Beginning transaction: ${txObject.id}, readOnly=${definition.isReadOnly}")

            // Store in context holder
            contextHolder.currentTransaction = txObject

            // Connections will be lazily acquired when first query is executed
            // via the PersistenceManager

        } catch (ex: Exception) {
            throw DrivineException("Could not begin transaction", ex)
        }
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        val txObject = status.transaction as DrivineTransactionObject

        try {
            logger.debug("Committing transaction: ${txObject.id}, databases: ${txObject.databases}")
            txObject.connections.forEach { it.commitTransaction() }
        } catch (ex: Exception) {
            throw DrivineException("Could not commit transaction: ${txObject.id}", ex)
        }
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        val txObject = status.transaction as DrivineTransactionObject

        try {
            logger.debug("Rolling back transaction: ${txObject.id}, databases: ${txObject.databases}")
            txObject.connections.forEach { it.rollbackTransaction() }
        } catch (ex: Exception) {
            throw DrivineException("Could not rollback transaction: ${txObject.id}", ex)
        }
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        val txObject = transaction as DrivineTransactionObject

        try {
            logger.debug("Cleaning up transaction: ${txObject.id}")

            // Release all connections
            txObject.connections.forEach { connection ->
                try {
                    connection.release(null)
                } catch (ex: Exception) {
                    logger.warn("Error releasing connection in transaction ${txObject.id}: ${ex.message}", ex)
                }
            }

            // Clear connection registry
            txObject.clearConnections()

            // Clear from context holder
            if (contextHolder.currentTransaction === txObject) {
                contextHolder.currentTransaction = null
            }

        } catch (ex: Exception) {
            logger.warn("Error during transaction cleanup: ${ex.message}", ex)
        }
    }

    override fun doSuspend(transaction: Any): Any {
        val txObject = transaction as DrivineTransactionObject
        contextHolder.currentTransaction = null
        return txObject
    }

    override fun doResume(transaction: Any?, suspendedResources: Any) {
        val txObject = suspendedResources as DrivineTransactionObject
        contextHolder.currentTransaction = txObject
    }

    override fun doSetRollbackOnly(status: DefaultTransactionStatus) {
        val txObject = status.transaction as DrivineTransactionObject
        txObject.rollbackOnly = true
    }
}

/**
 * Transaction object that holds connections for the current transaction.
 * Connections are lazily acquired when needed and managed by database key.
 */
class DrivineTransactionObject {
    val id: String = generateShortId()
    private val connectionRegistry = mutableMapOf<String, Connection>()
    private val connectionLock = ReentrantLock()
    var rollbackOnly = false

    val databases: List<String>
        get() = connectionRegistry.keys.toList()

    val connections: List<Connection>
        get() = connectionRegistry.values.toList()

    fun hasActiveConnections(): Boolean = connectionRegistry.isNotEmpty()

    /**
     * Gets or creates a connection for the specified database.
     * Called by PersistenceManager when a query needs to be executed.
     */
    fun getOrCreateConnection(database: String, contextHolder: TransactionContextHolder): Connection {
        connectionLock.withLock {
            return connectionRegistry.getOrPut(database) {
                val connectionProvider = contextHolder.databaseRegistry.connectionProvider(database)
                    ?: throw DrivineException("No database registered with key: $database")

                val connection = connectionProvider.connect()
                connection.startTransaction()
                connection
            }
        }
    }

    fun clearConnections() {
        connectionLock.withLock {
            connectionRegistry.clear()
        }
    }

    companion object {
        private fun generateShortId(): String {
            return UUID.randomUUID().toString().take(7)
        }
    }
}
