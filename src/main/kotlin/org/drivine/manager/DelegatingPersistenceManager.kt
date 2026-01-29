package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.transaction.TransactionContextHolder
import org.slf4j.LoggerFactory

class DelegatingPersistenceManager(
    override val database: String,
    override val type: DatabaseType,
    val contextHolder: TransactionContextHolder,
    val factory: PersistenceManagerFactory,
    private val subtypeRegistry: SubtypeRegistry
) : PersistenceManager {

    private val logger = LoggerFactory.getLogger(DelegatingPersistenceManager::class.java)

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T {
        return persistenceManager().getOne(spec)
    }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        return persistenceManager().maybeGetOne(spec)
    }

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): java.util.Optional<T> {
        return java.util.Optional.ofNullable(maybeGetOne(spec))
    }

//    override fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T> {
//        return persistenceManager().openCursor(spec)
//    }

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        return persistenceManager().query(spec)
    }

    override fun execute(spec: QuerySpecification<*>) {
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

    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) {
        subtypeRegistry.registerWithLabels(baseClass, labels, subClass)
    }
}
