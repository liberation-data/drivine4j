package org.drivine.connection

import org.drivine.DrivineException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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
    private const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronization"
    private const val UNEXPECTED_ROLLBACK = "org.springframework.transaction.UnexpectedRollbackException"

    /** Whether [given], or the `ConnectionProxy` it unwraps to, is the connection of a Spring transaction active on this thread: see the object. */
    fun holds(given: java.sql.Connection): Boolean = try {
        holder(given) != null
    } catch (e: ReflectiveOperationException) {
        if (e is InvocationTargetException) e.cause?.let { throw it }
        true
    } catch (e: LinkageError) {
        true
    }

    /**
     * Marks the Spring transaction that holds [given] (see [holds]) to roll back at its end, as a `@Transactional` method
     * that joins it and fails marks it: through the `ConnectionHolder` bound for it, which `DataSourceTransactionManager`
     * reads as it ends the transaction, rolling it back and throwing `UnexpectedRollbackException` where it was to
     * commit (#724). Not every transaction manager reads that mark: `JpaTransactionManager`, and Hibernate's, read only
     * their own. So it also registers a [RollbackVeto] with the transaction, which fails its commit, and the transaction
     * rolls back there instead. Whether one was marked: false where no Spring transaction holds [given], or Spring
     * cannot be asked.
     */
    fun markRollbackOnly(given: java.sql.Connection): Boolean = try {
        val bound = holder(given)
        if (bound != null) {
            bound.javaClass.getMethod("setRollbackOnly").invoke(bound)
            vetoCommit(bound)
        }
        bound != null
    } catch (e: ReflectiveOperationException) {
        false
    } catch (e: LinkageError) {
        false
    }

    /**
     * Registers a [RollbackVeto] for [holder] with the transaction synchronizations of this thread's Spring transaction,
     * found through the holder's class loader, once for each transaction: not where one for [holder] is registered
     * already, as after an earlier Drivine rollback in it. The synchronizations are the transaction's own, set aside while
     * one it suspends runs, and dropped as it ends.
     */
    private fun vetoCommit(holder: Any) {
        val loader = holder.javaClass.classLoader
        val synchronization = Class.forName(SYNCHRONIZATION, true, loader)
        if (synchronization.getMethod("isSynchronizationActive").invoke(null) != true) return
        val registered = synchronization.getMethod("getSynchronizations").invoke(null) as List<*>
        if (registered.any { it != null && Proxy.isProxyClass(it.javaClass) && (Proxy.getInvocationHandler(it) as? RollbackVeto)?.holder === holder }) return
        val type = Class.forName(TRANSACTION_SYNCHRONIZATION, true, loader)
        val veto = Proxy.newProxyInstance(loader, arrayOf(type), RollbackVeto(loader, holder))
        synchronization.getMethod("registerSynchronization", type).invoke(null, veto)
    }

    /**
     * A Spring `TransactionSynchronization`, built as a proxy of the interface so the core needs no Spring of its own,
     * whose `beforeCommit` fails the commit of a transaction a Drivine transaction joined and rolled back: Spring then
     * rolls the transaction back and throws what it threw, an `UnexpectedRollbackException`, as
     * `DataSourceTransactionManager` throws one for the `ConnectionHolder`'s mark. It runs first of the transaction's
     * synchronizations, so that none of them does its work before a commit there is to be none of. It fails it only
     * while [holder] is still marked: a rollback to a savepoint, as of a `NESTED` transaction that failed, clears the
     * mark, and the transaction is then committed, as Spring commits it. Its other methods are the interface's own,
     * which do nothing.
     */
    private class RollbackVeto(private val loader: ClassLoader, val holder: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "beforeCommit" -> if (holder.javaClass.getMethod("isRollbackOnly").invoke(holder) == true) throw rolledBack() else null
            "getOrder" -> Int.MIN_VALUE // Ordered.HIGHEST_PRECEDENCE
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Drivine's rollback veto (#724)"
            else -> if (method.isDefault) InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray())) else null
        }

        /** Spring's `UnexpectedRollbackException`, or a [DrivineException] where it can't be made. */
        private fun rolledBack(): RuntimeException {
            val message = "Transaction rolled back because a Drivine transaction that joined this one rolled back"
            return runCatching {
                Class.forName(UNEXPECTED_ROLLBACK, true, loader).getConstructor(String::class.java).newInstance(message) as RuntimeException
            }.getOrElse { DrivineException(message) }
        }
    }

    /**
     * The `ConnectionHolder` bound to this thread, while a transaction is actually active, whose connection is the
     * target of [given]'s `ConnectionProxy` (or that proxy); null where there is none. Throws where Spring cannot be asked.
     */
    private fun holder(given: java.sql.Connection): Any? {
        val connection = proxyInterface(given.javaClass)?.let { given } ?: unwrapped(given) ?: return null
        val proxy = proxyInterface(connection.javaClass) ?: return null
        val loader = proxy.classLoader
        val synchronization = Class.forName(SYNCHRONIZATION, true, loader)
        if (synchronization.getMethod("isActualTransactionActive").invoke(null) != true) return null
        val target = proxy.getMethod("getTargetConnection").invoke(connection)
        val holder = Class.forName(CONNECTION_HOLDER, true, loader)
        val held = holder.getMethod("getConnection")
        return (synchronization.getMethod("getResourceMap").invoke(null) as Map<*, *>).values.firstOrNull { resource ->
            holder.isInstance(resource) && runCatching { held.invoke(resource) }.getOrNull().let { it === target || it === connection }
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
