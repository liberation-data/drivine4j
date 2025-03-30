package drivine.manager

import drivine.connection.DatabaseType
import drivine.query.QuerySpecification
import drivine.transaction.TransactionContextHolder
import org.slf4j.LoggerFactory

class DelegatingPersistenceManager(
    override val database: String,
    override val type: DatabaseType,
    val contextHolder: TransactionContextHolder,
    val factory: PersistenceManagerFactory
) : PersistenceManager {

    private val logger = LoggerFactory.getLogger(DelegatingPersistenceManager::class.java)

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T {
        return persistenceManager().getOne(spec)
    }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        return persistenceManager().maybeGetOne(spec)
    }

//    override fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T> {
//        return persistenceManager().openCursor(spec)
//    }

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        return persistenceManager().query(spec)
    }

    override fun execute(spec: QuerySpecification<Unit>) {
        return persistenceManager().execute(spec)
    }

    private fun persistenceManager(): PersistenceManager {
        val type = if (contextHolder.currentTransaction != null) {
            PersistenceManagerType.TRANSACTIONAL
        } else {
            PersistenceManagerType.NON_TRANSACTIONAL
        }
        logger.debug("Using persistence manager: $type")
        return factory.get(database, type)
    }
}
