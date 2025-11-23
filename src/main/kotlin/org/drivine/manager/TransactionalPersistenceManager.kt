package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import org.drivine.transaction.DrivineTransactionObject
import org.drivine.transaction.TransactionContextHolder


class TransactionalPersistenceManager(
    private val contextHolder: TransactionContextHolder,
    override val database: String,
    override val type: DatabaseType
) : PersistenceManager {

    private val finderOperations: FinderOperations = FinderOperations(this)

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val txObject = currentTransactionOrThrow()
        val connection = txObject.getOrCreateConnection(database, contextHolder)
        return connection.query(spec)
    }

    override fun execute(spec: QuerySpecification<*>) {
        val txObject = currentTransactionOrThrow()
        val connection = txObject.getOrCreateConnection(database, contextHolder)
        connection.query(spec as QuerySpecification<Any>)
    }

    override fun <T: Any> getOne(spec: QuerySpecification<T>): T {
        return finderOperations.getOne(spec)
    }

    override fun <T: Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        return finderOperations.maybeGetOne(spec)
    }

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): java.util.Optional<T> {
        return java.util.Optional.ofNullable(maybeGetOne(spec))
    }

//    override fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T> {
//        val txObject = currentTransactionOrThrow()
//        return txObject.openCursor(spec, database)
//    }

    private fun currentTransactionOrThrow(): DrivineTransactionObject {
        val txObject = contextHolder.currentTransaction
        if (txObject == null) {
            throw DrivineException(
                "TransactionalPersistenceManager requires a transaction. Mark the transactional method with the @Transactional annotation"
            )
        }
        return txObject
    }
}
