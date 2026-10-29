package org.drivine

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.session.SessionManager
import org.drivine.transaction.Propagation
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import java.util.concurrent.Callable

/**
 * The registry, factories and transactions that a Spring application gets as beans from the starter,
 * made once, for an application with another framework or none: a `main`, a Ktor or Micronaut
 * server, a batch job or a test.
 *
 * ```kotlin
 * val drivine = Drivine(DataSourceMap(mapOf("neo" to ConnectionProperties(type = DatabaseType.NEO4J, ...))))
 * val gom = drivine.graphObjectManager("neo")
 * drivine.transaction {
 *     gom.save(person)
 * }
 * drivine.close()
 * ```
 *
 * Each part is a property, to hand to code that wants one: [persistenceManagerFactory],
 * [graphObjectManagerFactory], [transactions]. A connection provider the properties can't describe
 * (an `EMBABEL` engine) is registered with `databaseRegistry.register(provider)`.
 */
class Drivine @JvmOverloads constructor(
    dataSourceMap: DataSourceMap,
    val subtypeRegistry: SubtypeRegistry = SubtypeRegistry(),
    indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN,
    sessionMaxEntries: Int = SessionManager.DEFAULT_MAX_ENTRIES,
) : AutoCloseable {

    val databaseRegistry: DatabaseRegistry = DatabaseRegistry(dataSourceMap, subtypeRegistry)

    val transactionContextHolder: TransactionContextHolder = TransactionContextHolder(databaseRegistry)

    val persistenceManagerFactory: PersistenceManagerFactory =
        PersistenceManagerFactory(databaseRegistry, transactionContextHolder)

    val graphObjectManagerFactory: GraphObjectManagerFactory = GraphObjectManagerFactory(
        persistenceManagerFactory, Neo4jObjectMapper.instance, subtypeRegistry, indexAdvice, sessionMaxEntries,
    )

    val transactions: Transactions = Transactions(transactionContextHolder)

    /** The [PersistenceManager] of [database]: it runs in the current transaction, or commits each statement outside one. */
    @JvmOverloads
    fun persistenceManager(database: String = "default"): PersistenceManager = persistenceManagerFactory.get(database)

    /** The [StatelessGraphObjectManager] of [database], which joins the current transaction as [persistenceManager] does. */
    @JvmOverloads
    fun graphObjectManager(database: String = "default"): StatelessGraphObjectManager =
        graphObjectManagerFactory.stateless(database)

    /** Runs [block] in a transaction: see [Transactions.transaction]. */
    @JvmOverloads
    fun <T> transaction(
        propagation: Propagation = Propagation.REQUIRED,
        rollback: Boolean = false,
        block: Callable<T>,
    ): T = transactions.transaction(propagation, rollback, block)

    /** Runs [block], which returns nothing, in a transaction: see [Transactions.transactionWithoutResult]. */
    @JvmOverloads
    fun transactionWithoutResult(
        propagation: Propagation = Propagation.REQUIRED,
        rollback: Boolean = false,
        block: Runnable,
    ) = transactions.transactionWithoutResult(propagation, rollback, block)

    /**
     * Closes every database's connections: their pools and drivers. One that fails to close doesn't
     * keep the others open: the first failure is thrown once all have been tried.
     */
    override fun close() {
        var failure: Exception? = null
        databaseRegistry.databaseNames.forEach { name ->
            try {
                databaseRegistry.connectionProvider(name)?.end()
            } catch (ex: Exception) {
                failure?.addSuppressed(ex) ?: run { failure = ex }
            }
        }
        failure?.let { throw it }
    }
}
