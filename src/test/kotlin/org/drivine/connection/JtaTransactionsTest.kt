package org.drivine.connection

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.spi.InitialContextFactory
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A JTA transaction's connection is joined, and a Drivine transaction on it that rolls back marks the JTA transaction to
 * roll back through the `TransactionSynchronizationRegistry` bound at [JtaTransactions.REGISTRY] (#724). The registry
 * is a test's own, bound through [RegistryContextFactory] for the test that asks.
 */
class JtaTransactionsTest {

    private val factoryProperty = Context.INITIAL_CONTEXT_FACTORY
    private val factoryBefore: String? = System.getProperty(factoryProperty)

    @AfterEach
    fun unbind() {
        if (factoryBefore == null) System.clearProperty(factoryProperty) else System.setProperty(factoryProperty, factoryBefore)
        RegistryContextFactory.bound = null
    }

    /** Binds [registry] at the registry's JNDI name for the rest of the test. */
    private fun bind(registry: Any) {
        RegistryContextFactory.bound = registry
        System.setProperty(factoryProperty, RegistryContextFactory::class.java.name)
    }

    @Test
    fun `with no naming context, or none bound, or another thing bound, there is no JTA transaction to mark`() {
        System.clearProperty(factoryProperty)
        assertFalse(JtaTransactions.markRollbackOnly())

        System.setProperty(factoryProperty, RegistryContextFactory::class.java.name)
        assertFalse(JtaTransactions.markRollbackOnly())

        bind("not a registry")
        assertFalse(JtaTransactions.markRollbackOnly())
    }

    @Test
    fun `the registry is marked through its interface, though the server's class that implements it is not public`() {
        val registry = ServersRegistry()
        bind(registry)

        assertTrue(JtaTransactions.markRollbackOnly())
        assertEquals(1, registry.marked)
    }

    @Test
    fun `a connection that refuses to commit each statement is joined, and a Drivine transaction on it that rolls back marks the JTA transaction`() {
        val enlisted = Mockito.mock(java.sql.Connection::class.java).also {
            `when`(it.autoCommit).thenReturn(false)
            `when`(it.prepareStatement(Mockito.anyString())).thenAnswer { Mockito.mock(java.sql.PreparedStatement::class.java) }
            Mockito.doThrow(SQLException("Cannot set autocommit during a distributed transaction")).`when`(it).autoCommit = true
        }
        val source = Mockito.mock(DataSource::class.java).also { `when`(it.connection).thenReturn(enlisted) }
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, source, graphName = "my-graph")
        val registry = ServersRegistry()
        bind(registry)

        val connection = provider.connect() as JdbcConnection
        assertTrue(connection.joined)
        connection.startTransaction()
        connection.query(QuerySpecification.withStatement(sqlStatement("INSERT INTO t VALUES (1)")))
        connection.rollbackTransaction()
        assertEquals(1, registry.marked)
        // Released with a transaction open, as after a failure, it is marked too.
        connection.startTransaction()
        connection.release(null)
        assertEquals(2, registry.marked)

        verify(enlisted, never()).rollback()
        verify(enlisted, never()).commit()
        verify(enlisted, never()).autoCommit = false
    }

    /**
     * Spring's `JtaTransactionManager` binds a `ConnectionHolder` for the connection too, but reads only the JTA
     * transaction's mark as it ends: a rollback marks both.
     */
    @Test
    fun `a Spring transaction's connection in a JTA transaction marks both the holder and the JTA transaction`() {
        val held = Mockito.mock(java.sql.Connection::class.java).also { `when`(it.autoCommit).thenReturn(false) }
        val source = Mockito.mock(DataSource::class.java)
        val provider = JdbcConnectionProvider("default", DatabaseType.POSTGRES, TransactionAwareDataSourceProxy(source), graphName = "my-graph")
        val holder = ConnectionHolder(held)
        val registry = ServersRegistry()
        bind(registry)

        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.bindResource(source, holder)
        try {
            (provider.connect() as JdbcConnection).apply {
                assertTrue(joined)
                startTransaction()
                rollbackTransaction()
            }
        } finally {
            TransactionSynchronizationManager.unbindResource(source)
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }

        assertTrue(holder.isRollbackOnly)
        assertEquals(1, registry.marked)
        verify(held, never()).rollback()
    }
}

/** A registry as an application server's own class implements it: not public, so it is called through the interface. */
private class ServersRegistry : jakarta.transaction.TransactionSynchronizationRegistry {
    var marked = 0
    override fun setRollbackOnly() {
        marked++
    }

    // Drivine calls only setRollbackOnly.
    override fun getTransactionKey(): Any = throw UnsupportedOperationException()
    override fun putResource(key: Any?, value: Any?) = throw UnsupportedOperationException()
    override fun getResource(key: Any?): Any = throw UnsupportedOperationException()
    override fun registerInterposedSynchronization(sync: jakarta.transaction.Synchronization?) = throw UnsupportedOperationException()
    override fun getTransactionStatus(): Int = throw UnsupportedOperationException()
    override fun getRollbackOnly(): Boolean = throw UnsupportedOperationException()
}

/** A naming context factory, for `java.naming.factory.initial`, whose context gives [bound] at [JtaTransactions.REGISTRY]. */
class RegistryContextFactory : InitialContextFactory {
    override fun getInitialContext(environment: Hashtable<*, *>?): Context =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Context::class.java)) { _, method, args ->
            when (method.name) {
                "lookup" -> {
                    val name = args[0].toString()
                    if (name == JtaTransactions.REGISTRY) bound ?: throw NameNotFoundException(name) else throw NameNotFoundException(name)
                }
                "close" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Context

    companion object {
        /** What the context gives at the registry's name; null for nothing bound there. */
        @Volatile
        var bound: Any? = null
    }
}
