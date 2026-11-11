package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.NonAtomicBatchException
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.PinnedConnections
import org.drivine.connection.ReusedIdsException
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QueryResult
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.ddl.SchemaCommands
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

    override val graphName: String?
        get() = connectionProvider.graphName

    override val atomicBatches: Boolean
        get() = connectionProvider.atomicBatches

    override val schemaGrammar: SchemaGrammar
        get() = connectionProvider.schemaGrammar

    /** Cached: the stamp cannot change under a live connection, and every caller wants the same answer. */
    override val storeIdentity: StoreIdentity by lazy { StoreIdentityResolver(this).resolve() }

    override val indexes: IndexManager by lazy { IndexManager(connectionProvider, owner = this) }

    override val constraints: ConstraintManager by lazy { ConstraintManager(connectionProvider, indexes) }

    /**
     * What runs Neo4j schema DDL given as Cypher to a SQL store (see [SchemaCommands]): the one
     * [PersistenceManagerFactory] made for the database, or, for a manager made without one, one of the
     * manager's own, made when DDL is first run.
     */
    internal var schemaCommands: SchemaCommands? = null

    private val commands: SchemaCommands by lazy { schemaCommands ?: SchemaCommands.standalone(connectionProvider) }

    private val logger = LoggerFactory.getLogger(NonTransactionalPersistenceManager::class.java)
    private val finderOperations = FinderOperations(this)
    private val nonAtomic = NonAtomicBatches(logger, database)

    /**
     * Runs a statement. Neo4j schema DDL (`CREATE INDEX`, `CREATE CONSTRAINT`, `DROP …`, `db.awaitIndexes`)
     * given as Cypher to Postgres, Oracle or SQLite is applied through Drivine's schema machinery, and gives
     * no rows; see [SchemaCommands]. A graph engine is sent it as it is.
     */
    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        SchemaCommands.ddlOf(type, spec)?.let { ddl ->
            commands.run(ddl)
            return emptyList()
        }
        return onConnection(spec) { it.query(spec) }
    }

    override fun queryResult(spec: QuerySpecification<*>): QueryResult {
        QueryResults.requireUnmapped(spec)
        SchemaCommands.ddlOf(type, spec)?.let { ddl ->
            commands.run(ddl)
            return QueryResults.NONE
        }
        return onConnection(spec) { it.queryResult(spec) }
    }

    override fun explain(spec: QuerySpecification<*>): String = onConnection(spec) { it.explain(spec) }

    /** Runs [spec] by [run] on the connection a schema command holds, where one does: see SchemaCommands. */
    private fun <R> onConnection(spec: QuerySpecification<*>, run: (Connection) -> R): R = try {
        PinnedConnections.on(connectionProvider, run)
    } catch (e: ReusedIdsException) {
        // The graph is refused as the connection is had, not the statement: given as it is (#713).
        throw e
    } catch (e: Exception) {
        throw DrivineException.withRootCause(e, spec)
    }

    override fun execute(spec: QuerySpecification<*>) {
        query(spec as QuerySpecification<Any>)
    }

    /**
     * Runs all [specs] on a single connection in one explicit transaction — atomic even though this
     * manager is otherwise auto-commit. On any failure the whole transaction is rolled back, where
     * the engine has transactions to roll back ([atomicBatches]; see [PersistenceManager.executeBatch]).
     */
    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        queryBatch(specs)
    }

    /**
     * [executeBatch], returning each statement's rows. The connection is released however the batch
     * ends, including when the transaction cannot be started. A commit that fails is reported as a
     * failing statement is, as a [DrivineException] with the engine's error as its cause.
     *
     * Where the store cannot roll back ([atomicBatches] is false), a batch of one statement runs as
     * [query] runs it, with no transaction: the statement is applied whole on its own, and a
     * transaction would add nothing but a refusal in FalkorDB's `STRICT` mode. A batch of more is
     * refused with [NonAtomicBatchException] before a connection is taken where the provider says so
     * ([ConnectionProvider.refusesNonAtomicBatches]), and otherwise runs statement by statement,
     * which is reported (see [NonAtomicBatches]).
     */
    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        if (specs.isEmpty()) return emptyList()
        if (specs.any { SchemaCommands.ddlOf(type, it) != null }) {
            @Suppress("UNCHECKED_CAST")
            if (specs.size == 1) return listOf(query(specs.single() as QuerySpecification<Any>))
            throw DrivineException(
                "A schema command is not run in a batch of statements, which runs in one transaction: " +
                    "Drivine applies it on connections of its own. Run it on its own."
            )
        }
        if (specs.size == 1 && !atomicBatches) {
            @Suppress("UNCHECKED_CAST")
            return listOf(query(specs.single() as QuerySpecification<Any>))
        }
        if (!atomicBatches && connectionProvider.refusesNonAtomicBatches) throw NonAtomicBatchException(specs.size)
        val connection = connectionProvider.connect()
        try {
            connection.startTransaction()
        } catch (e: Throwable) {
            connection.release(e)
            throw e
        }
        if (!atomicBatches) nonAtomic.report(specs.size)
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
