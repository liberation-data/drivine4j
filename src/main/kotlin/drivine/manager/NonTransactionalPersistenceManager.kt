package drivine.manager

import drivine.DrivineException
import drivine.connection.ConnectionProvider
import drivine.connection.DatabaseType
import drivine.query.QuerySpecification
import org.slf4j.LoggerFactory

class NonTransactionalPersistenceManager(
    private val connectionProvider: ConnectionProvider,
    override val database: String,
    override val type: DatabaseType
) : PersistenceManager {

    private val logger = LoggerFactory.getLogger(NonTransactionalPersistenceManager::class.java)
    private val finderOperations = FinderOperations(this)

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val connection = connectionProvider.connect()
        return try {
            val result = connection.query(spec)
            connection.release()
            result
        } catch (e: Exception) {
            connection.release(e)
            throw DrivineException.withRootCause(e, spec)
        }
    }

    override fun execute(spec: QuerySpecification<Unit>) {
        query(spec)
    }

    override fun <T: Any> getOne(spec: QuerySpecification<T>): T {
        return finderOperations.getOne(spec)
    }

    override fun <T: Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        return finderOperations.maybeGetOne(spec)
    }

//    override fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T> {
//        logger.verbose("Open consumer for $spec")
//        return try {
//            throw DrivineError("Not implemented yet, please use TransactionalPersistenceManager")
//        } catch (e: DrivineError) {
//            throw e
//        }
//    }
}
