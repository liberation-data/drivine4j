package org.drivine.schema

import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The lock that serialises the schema DDL of the managers of one Oracle graph, across processes, while one of them runs
 * it (liberation-data/drivine4j#777). Oracle's DDL doesn't wait for another session's: managers that make the same index
 * at once, as replicas starting together do, now and then deadlock on its library-cache lock, and Oracle frees them
 * only after about nine seconds, or turns one away (`ORA-04020`). Held, the DDL runs one manager at a time, and each
 * after the first finds what it would make made, so its `IF NOT EXISTS` does nothing.
 *
 * It is a row lock of the graph's lock table (`<graph>_lock2`, made with the graph since layout 2): `SELECT … FOR UPDATE
 * WAIT 10` on a connection of its own, as DDL commits the session it runs on and would end a lock taken there. It's
 * taken only when a plan runs DDL, so a boot with nothing to make takes none, and released, by a rollback, when the
 * plan ends. A lock not had within [WAIT_SECONDS], as when another manager holds it that long, or one that died holding
 * it hasn't been ended yet, is gone without: its DDL runs unlocked, as it did before the lock, and a warning says so,
 * once for each graph in a process, with how long it waited. So nothing is ever left unmade or skipped, in either schema
 * mode, and the worst case is the deadlock the lock avoids. A store made before layout 2 has no lock table, and its DDL
 * runs unlocked too.
 */
internal class SchemaLock private constructor(private val connection: Connection) : AutoCloseable {

    /**
     * Ends the transaction that holds the row, which frees it, and gives the connection back. A rollback that fails, as on
     * a session Oracle has ended (which frees the row itself), gives the connection back with its error and throws
     * nothing: it's closed as a plan ends, and would hide the plan's own error.
     */
    override fun close() {
        try {
            connection.rollbackTransaction()
        } catch (e: Exception) {
            logger.debug("Freeing a schema lock failed; its session's end frees it: {}", e.message)
            connection.release(e)
            return
        }
        connection.release()
    }

    companion object {
        /** How long a manager waits for another's DDL, in seconds, before it runs its own unlocked. */
        const val WAIT_SECONDS = 10

        private val logger = LoggerFactory.getLogger(SchemaLock::class.java)

        /** The graphs whose lock this process has warned of not having, so it warns once for each. */
        private val warned: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * Another manager held the row past the wait: `ORA-00054` on Oracle 23, which gives the row lock's `ORA-40097`
         * under it, and `ORA-30006: resource busy; acquire with WAIT timeout expired` on earlier versions.
         */
        private val WAIT_EXPIRED = setOf(54, 30006)

        /** `ORA-00942: table or view does not exist`: a store made before layout 2, with no lock table. */
        private const val NO_TABLE = 942

        /** The gate of each provider, made as it's first asked for; weakly held, so an ended provider's goes with it. */
        private val gates: MutableMap<ConnectionProvider, ReentrantLock> = Collections.synchronizedMap(WeakHashMap())

        /**
         * The gate that [provider]'s schema DDL in this process passes one plan at a time, held from before the plan
         * takes the lock until it frees it, where the lock is taken: on Oracle, for a graph. Without it each plan
         * waiting for the row holds a connection of the pool while it waits, and with as many waiting as the pool has
         * connections, the one holding the row has none for its DDL, and waits out the pool's timeout. Gated, at most
         * one plan of a provider holds the lock's connection, and needs one more. A provider is one pool, of one store
         * and graph; plans on other pools of the same graph take turns through the lock itself. A queued plan has no
         * wait of its own: the gate's holder is done once its lock is had, or [WAIT_SECONDS] are out.
         */
        fun gate(provider: ConnectionProvider): ReentrantLock? =
            if (provider.type != DatabaseType.ORACLE || provider.graphName == null) null
            else gates.getOrPut(provider) { ReentrantLock() }

        /** The statement that takes the row of [graph]'s lock table, waiting up to [WAIT_SECONDS] for it. */
        internal fun statement(graph: String): String = OracleDdl(graph).let { ddl ->
            "SELECT name FROM ${ddl.lock} WHERE name = '${ddl.lockRow}' FOR UPDATE WAIT $WAIT_SECONDS"
        }

        /**
         * The lock of [provider]'s graph, held: on Oracle only, as Postgres serialises its own DDL with a lock of the
         * graph's and SQLite has one writer. Null on another store, where the graph has no lock table, and where the lock
         * isn't had within [WAIT_SECONDS]: then the DDL runs unlocked.
         */
        fun take(provider: ConnectionProvider): SchemaLock? {
            if (provider.type != DatabaseType.ORACLE) return null
            val graph = provider.graphName ?: return null
            val connection = provider.connect()
            val started = System.nanoTime()
            try {
                connection.startTransaction()
                connection.query(QuerySpecification.withStatement(statement(graph)))
                return SchemaLock(connection)
            } catch (e: Exception) {
                try {
                    connection.rollbackTransaction()
                } catch (rollback: Exception) {
                    // The transaction may not have begun, or Oracle may have ended the session: the error that brought
                    // us here is the one that counts, and the connection is given back either way.
                    e.addSuppressed(rollback)
                }
                try {
                    connection.release(e)
                } catch (release: Exception) {
                    e.addSuppressed(release)
                }
                when (oracleCode(e)) {
                    NO_TABLE -> {
                        logger.debug("Graph {} has no lock table, as a store made before layout 2: its DDL runs unlocked.", graph)
                        return null
                    }
                    in WAIT_EXPIRED -> {
                        if (warned.add(graph)) {
                            logger.warn(
                                "Couldn't take the schema lock of graph {} in {} ms: another Drivine holds it. Making its schema " +
                                    "unlocked, which may wait out a contention of Oracle's (drivine4j#777).",
                                graph, (System.nanoTime() - started) / 1_000_000,
                            )
                        }
                        return null
                    }
                    else -> throw e
                }
            }
        }

        /** The Oracle error code of [e] or a cause of it, as ORA-30006 is 30006; null where none is an Oracle error. */
        private fun oracleCode(e: Throwable): Int? =
            generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull { it.errorCode != 0 }?.errorCode
    }
}
