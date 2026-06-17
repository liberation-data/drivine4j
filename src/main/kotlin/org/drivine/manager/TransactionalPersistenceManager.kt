package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import org.drivine.transaction.DrivineTransactionObject
import org.drivine.transaction.TransactionContextHolder


class TransactionalPersistenceManager(
    private val contextHolder: TransactionContextHolder,
    override val database: String,
    override val type: DatabaseType,
    private val subtypeRegistry: SubtypeRegistry,
    override val grammar: CypherGrammar,
    /**
     * Source of schema managers — the non-transactional manager for the same database, supplied
     * by [PersistenceManagerFactory]. Schema DDL must execute in auto-commit mode, never through
     * this manager's transactional connection.
     */
    private val schemaManagerSource: PersistenceManager? = null,
) : PersistenceManager {

    private val finderOperations: FinderOperations = FinderOperations(this)

    override val indexes: IndexManager
        get() = schemaManagerSource?.indexes ?: throw DrivineException(
            "Schema management is not available on this TransactionalPersistenceManager instance. " +
                "Obtain managers through PersistenceManagerFactory, or use a non-transactional manager."
        )

    override val constraints: ConstraintManager
        get() = schemaManagerSource?.constraints ?: throw DrivineException(
            "Schema management is not available on this TransactionalPersistenceManager instance. " +
                "Obtain managers through PersistenceManagerFactory, or use a non-transactional manager."
        )

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val txObject = currentTransactionOrThrow()
        val connection = txObject.getOrCreateConnection(database, contextHolder)
        return try {
            connection.query(spec)
        } catch (e: Exception) {
            throw DrivineException.withRootCause(e, spec)
        }
    }

    override fun execute(spec: QuerySpecification<*>) {
        val txObject = currentTransactionOrThrow()
        val connection = txObject.getOrCreateConnection(database, contextHolder)
        try {
            connection.query(spec as QuerySpecification<Any>)
        } catch (e: Exception) {
            throw DrivineException.withRootCause(e, spec)
        }
    }

    /**
     * Runs all [specs] on the ambient transaction's connection. Atomicity is the caller's transaction:
     * a failing statement propagates and the surrounding `@Transactional` rolls the whole thing back.
     */
    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        if (specs.isEmpty()) return
        val txObject = currentTransactionOrThrow()
        val connection = txObject.getOrCreateConnection(database, contextHolder)
        specs.forEach { spec ->
            try {
                @Suppress("UNCHECKED_CAST")
                connection.query(spec as QuerySpecification<Any>)
            } catch (e: Exception) {
                throw DrivineException.withRootCause(e, spec)
            }
        }
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

    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) {
        subtypeRegistry.registerWithLabels(baseClass, labels, subClass)
    }
}
