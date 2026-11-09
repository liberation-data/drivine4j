package org.drivine.connection

import java.lang.reflect.InvocationTargetException

/**
 * Whether a connection a `DataSource` gave Drivine is the connection of a Spring transaction running on this thread,
 * which commits or rolls back what is run on it: the application's own `@Transactional` work through
 * `DataSourceTransactionManager` or `JpaTransactionManager`. Drivine leaves such a connection's statements to that
 * transaction, and does not turn it to commit each one, which would commit the transaction partway through (#395).
 *
 * Drivine takes its connections from the `DataSource` itself, never through Spring's `DataSourceUtils`, so a Spring
 * transaction's connection reaches it only through a data source that hands that connection to whoever asks:
 * Spring's `TransactionAwareDataSourceProxy`. The connections it hands out are Spring's `ConnectionProxy`, and a
 * connection that is not one, nor unwraps to one (as a statement logger's wrapper over the proxy's connection does), is
 * held by no Spring transaction. Spring is found by name, through the proxy's own class loader or, for a wrapper,
 * Drivine's, so the core needs no Spring of its own.
 *
 * A proxy whose target is the connection of a `ConnectionHolder` bound to this thread while a transaction is
 * actually active is held. Where Spring cannot be asked, it is taken as held, so that Drivine commits nothing of a
 * transaction it cannot see.
 */
internal object SpringTransactions {

    private const val CONNECTION_PROXY = "org.springframework.jdbc.datasource.ConnectionProxy"
    private const val CONNECTION_HOLDER = "org.springframework.jdbc.datasource.ConnectionHolder"
    private const val SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"

    /** Whether [connection] is the connection of a Spring transaction active on this thread: see the object. */
    fun holds(given: java.sql.Connection): Boolean {
        val connection = proxyInterface(given.javaClass)?.let { given } ?: unwrapped(given) ?: return false
        val proxy = proxyInterface(connection.javaClass) ?: return false
        return try {
            val loader = proxy.classLoader
            val synchronization = Class.forName(SYNCHRONIZATION, true, loader)
            if (synchronization.getMethod("isActualTransactionActive").invoke(null) != true) return false
            val target = proxy.getMethod("getTargetConnection").invoke(connection)
            val holder = Class.forName(CONNECTION_HOLDER, true, loader)
            val held = holder.getMethod("getConnection")
            (synchronization.getMethod("getResourceMap").invoke(null) as Map<*, *>).values.any { resource ->
                holder.isInstance(resource) && runCatching { held.invoke(resource) }.getOrNull().let { it === target || it === connection }
            }
        } catch (e: ReflectiveOperationException) {
            if (e is InvocationTargetException) e.cause?.let { throw it }
            true
        } catch (e: LinkageError) {
            true
        }
    }

    /** Spring's `ConnectionProxy`, as Drivine's class loader finds it, or null where there is no Spring. */
    private val connectionProxy: Class<*>? by lazy {
        runCatching { Class.forName(CONNECTION_PROXY, false, SpringTransactions::class.java.classLoader) }.getOrNull()
    }

    /** The `ConnectionProxy` [connection] wraps, where it wraps one, by JDBC's own unwrapping. */
    private fun unwrapped(connection: java.sql.Connection): java.sql.Connection? {
        val type = connectionProxy ?: return null
        return runCatching { if (connection.isWrapperFor(type)) connection.unwrap(type) as java.sql.Connection else null }.getOrNull()
    }

    /** Spring's `ConnectionProxy`, where [type] implements it, found by name. */
    private fun proxyInterface(type: Class<*>): Class<*>? =
        generateSequence(type) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .firstNotNullOfOrNull { found(it) }

    private fun found(type: Class<*>): Class<*>? =
        if (type.name == CONNECTION_PROXY) type else type.interfaces.firstNotNullOfOrNull { found(it) }
}
