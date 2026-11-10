package org.drivine.connection

import javax.naming.InitialContext
import javax.naming.NamingException

/**
 * The JTA transaction a connection Drivine was given is enlisted in, where the application server makes one: a
 * connection that refuses to commit each statement, as JDBC refuses on a connection in a distributed transaction (see
 * [JdbcConnectionProvider]). Drivine joins it and cannot end it: a Drivine transaction on it that rolls back marks it
 * to roll back instead (#724), through the `TransactionSynchronizationRegistry` that Jakarta EE (and Java EE before it)
 * binds at [REGISTRY]. It is looked for only then, and called by name, so the core needs no JTA of its own.
 */
internal object JtaTransactions {

    /** Where Jakarta EE binds the transaction synchronization registry. */
    const val REGISTRY = "java:comp/TransactionSynchronizationRegistry"

    /** The registry's interface, in Jakarta EE and in Java EE before it. */
    private val REGISTRY_TYPES = setOf("jakarta.transaction.TransactionSynchronizationRegistry", "javax.transaction.TransactionSynchronizationRegistry")

    /** Every interface [type] implements, its superclasses' and their own superinterfaces too. */
    private fun interfaces(type: Class<*>): Sequence<Class<*>> =
        generateSequence(type) { it.superclass }.flatMap { it.interfaces.asSequence() }.flatMap { sequenceOf(it) + interfaces(it) }

    /** Marks the JTA transaction of this thread to roll back. Whether one was marked: false where no registry is bound. */
    fun markRollbackOnly(): Boolean = try {
        val registry = InitialContext().lookup(REGISTRY) ?: return false
        // Called through the interface: the server's own class that implements it need not be public.
        val type = interfaces(registry.javaClass).firstOrNull { it.name in REGISTRY_TYPES } ?: return false
        type.getMethod("setRollbackOnly").invoke(registry)
        true
    } catch (e: NamingException) {
        false
    } catch (e: ReflectiveOperationException) {
        false
    } catch (e: LinkageError) {
        false
    }
}
