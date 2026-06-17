package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import org.slf4j.LoggerFactory

class NonTransactionalPersistenceManager(
    private val connectionProvider: ConnectionProvider,
    override val database: String,
    override val type: DatabaseType,
    private val subtypeRegistry: SubtypeRegistry
) : PersistenceManager {

    override val grammar: CypherGrammar
        get() = connectionProvider.grammar

    override val indexes: IndexManager by lazy { IndexManager(connectionProvider) }

    override val constraints: ConstraintManager by lazy { ConstraintManager(connectionProvider, indexes) }

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

    override fun execute(spec: QuerySpecification<*>) {
        query(spec as QuerySpecification<Any>)
    }

    /**
     * Runs all [specs] on a single connection in one explicit transaction — atomic even though this
     * manager is otherwise auto-commit. On any failure the whole transaction is rolled back.
     */
    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        if (specs.isEmpty()) return
        val connection = connectionProvider.connect()
        connection.startTransaction()
        try {
            specs.forEach { spec ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    connection.query(spec as QuerySpecification<Any>)
                } catch (e: Exception) {
                    throw DrivineException.withRootCause(e, spec)
                }
            }
            connection.commitTransaction()
        } catch (e: Throwable) {
            runCatching { connection.rollbackTransaction() }
            connection.release(e)
            throw e
        }
        connection.release()
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
//        logger.verbose("Open consumer for $spec")
//        return try {
//            throw DrivineError("Not implemented yet, please use TransactionalPersistenceManager")
//        } catch (e: DrivineError) {
//            throw e
//        }
//    }

    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) {
        subtypeRegistry.registerWithLabels(baseClass, labels, subClass)
    }
}
