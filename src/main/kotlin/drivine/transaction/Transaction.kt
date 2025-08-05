package drivine.transaction

import drivine.DrivineException
import drivine.connection.Connection
import drivine.manager.DelegatingPersistenceManager
import drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Transaction(
    options: TransactionOptions,
    private val contextHolder: TransactionContextHolder
) {
    val id: String = generateShortId()
    val callStack = ArrayDeque<String>()
    val connectionRegistry = mutableMapOf<String, Connection>()
//    val cursors = mutableListOf<Cursor<*>>()
    private val logger = LoggerFactory.getLogger(DelegatingPersistenceManager::class.java)
    private val connectionLock = ReentrantLock()
    private var _options: TransactionOptions = options

    init {
        contextHolder.currentTransaction = this
    }

    val description: String
        get() = "$id [${databases}]"

    val databases: List<String>
        get() = connectionRegistry.keys.toList()

    val connections: List<Connection>
        get() = connectionRegistry.values.toList()

    fun <T: Any> query(spec: QuerySpecification<T>, database: String): List<T> {
        check(callStack.isNotEmpty()) { "pushContext() must be called before running a query" }
        return try {
            val connection = connectionFor(database)
            connection.query(spec)
        } catch (e: Exception) {
            throw DrivineException.withRootCause(e, spec)
        }
    }

//    fun <T> openCursor(spec: CursorSpecification<T>, database: String): Cursor<T> {
//        check(callStack.isNotEmpty()) { "pushContext() must be called before running a query" }
//        val connection = connectionFor(database)
//        return connection.openCursor(spec).also { cursors.add(it) }
//    }

    fun pushContext(context: Any) {
        if (callStack.isEmpty()) {
            logger.debug("Starting transaction: $id")
        }
        callStack.addLast(context.toString())
    }

    fun popContext(isRoot: Boolean) {
        callStack.removeLast()
        if (isRoot) {
//            logger.debug("Closing ${cursors.size} open cursors.")
//            cursors.forEach { it.close() }
            if (_options.rollback) {
                logger.debug("Transaction: $description successful, but is marked ROLLBACK. Rolling back.")
                connections.forEach { it.rollbackTransaction() }
            } else {
                logger.debug("Committing transaction: $description.")
                connections.forEach { it.commitTransaction() }
            }
            releaseClient()
        }
    }

    fun popContextWithError(e: Exception, isRoot: Boolean) {
        callStack.removeLast()
        if (isRoot) {
            logger.debug("Rolling back transaction: $description due to error: ${e.message}.")
            connections.forEach { it.rollbackTransaction() }
            releaseClient(e)
        }
    }

    fun markAsRollback() {
        _options = _options.copy(rollback = true)
    }

    var options: TransactionOptions
        get() = _options
        set(value) {
            check(callStack.isEmpty()) { "Can't set options if the transaction is already in flight" }
            _options = value
        }

    private fun connectionFor(database: String): Connection {
        connectionLock.withLock {
            return connectionRegistry.getOrPut(database) {
                val databaseRegistry = contextHolder.databaseRegistry
                val connectionProvider = databaseRegistry.connectionProvider(database)
                    ?: throw DrivineException("There is no database registered with key: $database")
                val connection = connectionProvider.connect()
                connection.startTransaction()
                connection
            }
        }
    }

    private fun releaseClient(error: Exception? = null) {
        logger.debug("Releasing connection(s) for transaction: $id")
        connections.forEach { it.release(error) }
        contextHolder.currentTransaction = null
    }

    companion object {
        fun generateShortId(): String {
            return java.util.UUID.randomUUID().toString().take(7)
        }
    }
}
