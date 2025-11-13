package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.query.QuerySpecification
import org.drivine.transaction.Transaction
import org.drivine.transaction.TransactionContextHolder


class TransactionalPersistenceManager(
    private val contextHolder: TransactionContextHolder,
    override val database: String,
    override val type: DatabaseType
) : PersistenceManager {

    private val finderOperations: FinderOperations = FinderOperations(this)

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val transaction = currentTransactionOrThrow()
        return transaction.query(spec, database)
    }

    override fun execute(spec: QuerySpecification<*>) {
        val transaction = currentTransactionOrThrow()
        transaction.query(spec as QuerySpecification<Any>, database)
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
//        val transaction = currentTransactionOrThrow()
//        return transaction.openCursor(spec, database)
//    }

    private fun currentTransactionOrThrow(): Transaction {
        val transaction = contextHolder.currentTransaction
        if (transaction == null) {
            throw DrivineException(
                "TransactionalPersistenceManager requires a transaction. Mark the transactional method with the @Transactional() decorator, or use NonTransactionalPersistenceManager"
            )
        }
        return transaction
    }
}
