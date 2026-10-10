package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.store.StoreIdentity
import org.drivine.store.StoreIdentityResolver
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

    override val supportsSchemaManagement: Boolean
        get() = connectionProvider.supportsSchemaManagement

    /** Cached: the stamp cannot change under a live connection, and every caller wants the same answer. */
    override val storeIdentity: StoreIdentity by lazy { StoreIdentityResolver(this).resolve() }

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
     * manager is otherwise auto-commit. On any failure the whole transaction is rolled back, where
     * the engine has transactions to roll back (see [PersistenceManager.executeBatch]).
     */
    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        queryBatch(specs)
    }

    /**
     * [executeBatch], returning each statement's rows. The connection is released however the batch
     * ends, including when the transaction cannot be started. A commit that fails is reported as a
     * failing statement is, as a [DrivineException] with the engine's error as its cause.
     */
    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        if (specs.isEmpty()) return emptyList()
        val connection = connectionProvider.connect()
        try {
            connection.startTransaction()
        } catch (e: Throwable) {
            connection.release(e)
            throw e
        }
        val results = try {
            specs.map { spec ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    connection.query(spec as QuerySpecification<Any>)
                } catch (e: Exception) {
                    throw DrivineException.withRootCause(e, spec)
                }
            }.also {
                try {
                    connection.commitTransaction()
                } catch (e: Exception) {
                    throw DrivineException.withRootCause(e)
                }
            }
        } catch (e: Throwable) {
            runCatching { connection.rollbackTransaction() }
            connection.release(e)
            throw e
        }
        connection.release()
        return results
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
